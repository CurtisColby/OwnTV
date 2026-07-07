package tv.own.owntv.features.shell

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.ui.theme.AccentColor
import tv.own.owntv.ui.theme.ThemeMode
import tv.own.owntv.ui.theme.UiZoom

/** Top-level navigation destinations rendered in the Layer-1 sidebar. */
enum class MainSection(val label: String) {
    SEARCH("Search"),
    HOME("Home"),
    LIVE_TV("Live TV"),
    MOVIES("Movies"),
    SERIES("Series"),
    DOWNLOADS("Downloads"),
    EPG("Guide"),
    SETTINGS("Settings"); // pinned at the bottom of the nav

    val isBrowse: Boolean get() = this != SETTINGS
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShellViewModel(
    private val settings: SettingsRepository,
    private val sourceRepository: SourceRepository,
    private val profileDao: tv.own.owntv.core.database.dao.ProfileDao,
    connectivity: ConnectivityObserver,
    private val launcherIntegrationRepository: LauncherIntegrationRepository,
    private val epgMigration: tv.own.owntv.core.epg.EpgMigration,
    private val epgSourceStore: tv.own.owntv.core.epg.EpgSourceStore,
    private val epgRepository: tv.own.owntv.core.repository.EpgRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "OwnTVHome"
        /** A profile's guide feed older than this is silently refreshed on app start / profile switch.
         *  The backend's guide is a rolling always-fresh 72h window; the client snapshot silently ages
         *  because there is no other automatic refresh mechanism (Android TV kills background timers). */
        private const val EPG_STALE_MS = 12 * 60 * 60 * 1000L // 12 hours
    }

    // When the "refresh on startup" playlist sync last ran. Replaces the old once-per-process
    // boolean: Android TV keeps the process resident for days, so "app start" almost never means
    // a cold start — reopening the app just resumes the parked process. A timestamp with the same
    // 12h staleness window re-arms the sync so returning after days away actually refreshes.
    private var lastStartupPlaylistSyncAt = 0L
    private var playlistSyncInFlight = false
    // Profiles with an EPG staleness refresh currently in flight (prevents doubling up on a quick
    // profile switch away and back). All access is on viewModelScope's main dispatcher.
    private val epgRefreshInFlight = mutableSetOf<Long>()

    init {
        // One-time: move any existing playlist EPG into the new standalone EPG sources (v2.2.0).
        viewModelScope.launch { runCatching { epgMigration.run() } }
        viewModelScope.launch {
            settings.activeProfileId
                .distinctUntilChanged()
                .collect { pid ->
                    Log.d(TAG, "activeProfileChanged profile=$pid androidTvHomeEnabled=${settings.androidTvHomeEnabled.first()}")
                    if (pid >= 0 && settings.androidTvHomeEnabled.first()) {
                        runCatching { launcherIntegrationRepository.refreshProfile(pid, allowBrowsableRequest = true) }
                    }
                    // Fires on app start AND on every profile switch — the two moments the S30-agreed
                    // staleness design targets. EPG-only, silent, no-op when the guide is fresh.
                    refreshStaleEpgIfNeeded(pid)
                }
        }
    }

    /** Silently refresh this profile's standalone EPG feeds whose last sync is older than
     *  [EPG_STALE_MS]. EPG-only by design — playlists (channels) don't go stale, programmes do.
     *  Failures are logged but do NOT stamp lastSyncAt, so an offline start retries at the next
     *  app open instead of leaving the guide stale for another 12 h. */
    private fun refreshStaleEpgIfNeeded(pid: Long) {
        if (pid < 0 || pid in epgRefreshInFlight) return
        viewModelScope.launch {
            // On a cold start the connectivity observer may not have reported in yet and would
            // momentarily read offline — wait up to 5s for it to come online before giving up.
            // A genuinely offline box still skips (and retries at the next foreground).
            kotlinx.coroutines.withTimeoutOrNull(5_000) { isOnline.first { it } } ?: return@launch
            val now = System.currentTimeMillis()
            val stale = runCatching { epgSourceStore.getForProfile(pid) }.getOrDefault(emptyList())
                .filter { it.lastSyncAt == null || now - it.lastSyncAt > EPG_STALE_MS }
            if (stale.isEmpty()) return@launch
            epgRefreshInFlight += pid
            try {
                Log.i(TAG, "EPG staleness: ${stale.size} feed(s) older than 12h for profile=$pid — refreshing silently")
                stale.forEach { s ->
                    val at = System.currentTimeMillis()
                    runCatching { epgRepository.refreshUrl(s.id, s.url, s.userAgent) }
                        .onSuccess { count ->
                            epgSourceStore.setSynced(s.id, at, null)
                            Log.i(TAG, "EPG staleness: refreshed '${s.name}' ($count programmes) profile=$pid")
                        }
                        .onFailure { t -> Log.w(TAG, "EPG staleness: refresh failed for '${s.name}' profile=$pid", t) }
                }
            } finally {
                epgRefreshInFlight -= pid
            }
        }
    }

    /** Whether the device currently has internet (drives the offline banner). */
    val isOnline: StateFlow<Boolean> = connectivity.isOnline
        .stateIn(viewModelScope, SharingStarted.Eagerly, connectivity.isOnlineNow())

    /** Called from MainActivity.onStart() every time the app comes to the foreground (cold start
     *  OR resuming the parked process days later — the case the old once-per-process design missed).
     *  Both checks below are staleness-gated no-ops when everything is under 12h old, so bouncing
     *  to the launcher and back during an evening costs nothing. */
    fun onAppForegrounded() {
        viewModelScope.launch {
            val pid = currentProfileId() ?: return@launch
            refreshStaleEpgIfNeeded(pid)
            refreshOnStartIfEnabled()
        }
    }

    /** Re-sync the sources flagged "refresh on startup" for the active profile — at most once per
     *  12h window, re-armed on every app foreground (not just per process launch). */
    fun refreshOnStartIfEnabled() {
        if (playlistSyncInFlight) return
        if (System.currentTimeMillis() - lastStartupPlaylistSyncAt < EPG_STALE_MS) return
        playlistSyncInFlight = true
        viewModelScope.launch {
          try {
            // Same cold-start grace as the EPG check: don't burn the 12h window while offline.
            kotlinx.coroutines.withTimeoutOrNull(5_000) { isOnline.first { it } } ?: return@launch
            val ids = settings.refreshSourceIds.first()
            if (ids.isEmpty()) return@launch
            val pid = currentProfileId() ?: return@launch
            lastStartupPlaylistSyncAt = System.currentTimeMillis()
            Log.d(TAG, "refreshOnStartIfEnabled profile=$pid sourceIds=$ids androidTvHomeEnabled=${settings.androidTvHomeEnabled.first()}")
            sourceRepository.observeSources(pid).first()
                .filter { it.id in ids }
                .forEach { source ->
                    Log.d(TAG, "refreshOnStartIfEnabled syncing sourceId=${source.id} profile=$pid")
                    runCatching { sourceRepository.sync(source) {} }
                        .onSuccess { Log.d(TAG, "refreshOnStartIfEnabled synced sourceId=${source.id} profile=$pid") }
                        .onFailure { t -> Log.w(TAG, "refreshOnStartIfEnabled sync failed sourceId=${source.id} profile=$pid", t) }
                }
            if (settings.androidTvHomeEnabled.first()) {
                runCatching { launcherIntegrationRepository.refreshProfile(pid, allowBrowsableRequest = true) }
            }
          } finally {
            playlistSyncInFlight = false
          }
        }
    }

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.AMOLED_DARK)

    val uiZoomPercent: StateFlow<Int> = settings.uiZoomPercent
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiZoom.DEFAULT)

    val animationLevel: StateFlow<tv.own.owntv.ui.theme.AnimationLevel> = settings.animationLevel
        .stateIn(viewModelScope, SharingStarted.Eagerly, tv.own.owntv.ui.theme.AnimationLevel.FULL)

    val accent: StateFlow<AccentColor> = settings.accent
        .stateIn(viewModelScope, SharingStarted.Eagerly, AccentColor.TEAL)

    /** Custom accent hex ("#52DBC8"); blank = the preset above is in effect. */
    val customAccent: StateFlow<String> = settings.customAccent
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** The active profile's avatar (so the sidebar reflects profile edits, not a separate setting). */
    val avatarId: StateFlow<Int> = settings.activeProfileId
        .flatMapLatest { pid -> if (pid < 0) flowOf(0) else profileDao.observeById(pid).map { it?.avatarId ?: 0 } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** The active profile's name, shown in the sidebar profile card. */
    val profileName: StateFlow<String> = settings.activeProfileId
        .flatMapLatest { pid -> if (pid < 0) flowOf("") else profileDao.observeById(pid).map { it?.name ?: "" } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** The active (default) source's name for the sidebar; "No source" when the profile has none. */
    val sourceSummary: StateFlow<String> = settings.activeProfileId
        .flatMapLatest { pid -> if (pid < 0) flowOf(emptyList<tv.own.owntv.core.database.entity.SourceEntity>()) else sourceRepository.observeSources(pid) }
        .combine(settings.defaultSourceId) { sources, defaultId ->
            when {
                sources.isEmpty() -> "No source"
                else -> (sources.firstOrNull { it.id == defaultId } ?: sources.first()).name
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "No source")

    /** null = still loading; < 0 = first run (show setup wizard); >= 0 = active profile (show shell). */
    val activeProfileId: StateFlow<Long?> = settings.activeProfileId
        .map<Long, Long?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _selectedSection = MutableStateFlow(MainSection.HOME)
    val selectedSection: StateFlow<MainSection> = _selectedSection.asStateFlow()

    fun selectSection(section: MainSection) {
        _selectedSection.value = section
    }

    /** Force the active profile (used by the cold-start default router and in-app profile switch). */
    fun setActiveProfileId(id: Long) {
        viewModelScope.launch { settings.setActiveProfile(id) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    /** Cycles through the available themes — wired to a temporary button until the Theme screen exists. */
    fun cycleTheme() {
        val next = when (themeMode.value) {
            ThemeMode.AMOLED_DARK -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.SYSTEM
            ThemeMode.SYSTEM -> ThemeMode.AMOLED_DARK
        }
        setThemeMode(next)
    }

    fun setUiZoom(percent: Int) {
        viewModelScope.launch { settings.setUiZoomPercent(UiZoom.clamp(percent)) }
    }

    fun setAccent(accent: AccentColor) {
        viewModelScope.launch { settings.setAccent(accent) }
    }

    fun setAvatar(id: Int) {
        viewModelScope.launch {
            val pid = currentProfileId() ?: return@launch
            profileDao.setAvatar(pid, id)
        }
    }

    /** Cycles through the accent presets. */
    fun cycleAccent() {
        val values = AccentColor.entries
        val next = values[(accent.value.ordinal + 1) % values.size]
        setAccent(next)
    }

    private suspend fun currentProfileId(): Long? {
        val preferred = settings.activeProfileId.first()
        return profileDao.resolveExistingProfileId(preferred)
    }
}