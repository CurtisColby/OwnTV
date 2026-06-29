@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package tv.own.owntv.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.epg.EpgSource
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.util.friendlySyncError
import tv.own.owntv.features.settings.data.SettingsRepository

/** Manage standalone EPG (XMLTV) sources: list, add (auto-sync), edit, re-sync, delete. */
class EpgSourcesViewModel(
    private val store: EpgSourceStore,
    private val epgRepository: EpgRepository,
    private val sourceRepository: SourceRepository,
    private val settings: SettingsRepository,
    private val connectivity: ConnectivityObserver,
    private val epgDao: tv.own.owntv.core.database.dao.EpgDao,
    private val channelDao: tv.own.owntv.core.database.dao.ChannelDao,
) : ViewModel() {

    sealed interface SyncState {
        data object Idle : SyncState
        data class Working(val name: String, val count: Int = 0) : SyncState
        data class Done(val message: String) : SyncState
        data class Failed(val message: String) : SyncState
    }

    /** An existing playlist's EPG feed, offered as a one-tap "fill from playlist" option. */
    data class PlaylistEpg(val name: String, val url: String)

    val sources: StateFlow<List<EpgSource>> =
        settings.activeProfileId
            .flatMapLatest { pid -> if (pid < 0) kotlinx.coroutines.flow.flowOf(emptyList()) else store.sourcesForProfile(pid) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    fun add(name: String, url: String, userAgent: String? = null) {
        viewModelScope.launch {
            val pid = settings.activeProfileId.first()
            if (pid < 0) { _sync.value = SyncState.Failed("No active profile"); return@launch }
            sync(store.add(name, url, pid, userAgent))
        }
    }

    fun resync(source: EpgSource) {
        viewModelScope.launch { sync(source) }
    }

    fun update(source: EpgSource, name: String, url: String, userAgent: String?) {
        viewModelScope.launch {
            val updated = source.copy(name = name.trim().ifBlank { source.name }, url = url.trim(), userAgent = userAgent?.trim()?.takeIf { it.isNotBlank() })
            store.update(updated)
            sync(updated)
        }
    }

    fun delete(source: EpgSource) {
        viewModelScope.launch {
            store.remove(source.id)
            epgRepository.clear(source.id)
        }
    }

    private suspend fun sync(source: EpgSource) {
        _sync.value = SyncState.Working(source.name)
        val now = System.currentTimeMillis()
        runCatching { epgRepository.refreshUrl(source.id, source.url, source.userAgent) { c -> _sync.value = SyncState.Working(source.name, c) } }
            .onSuccess { count ->
                store.setSynced(source.id, now, null)
                _sync.value = SyncState.Done("${source.name}: $count EPG programmes synced")
            }
            .onFailure {
                store.setSynced(source.id, now, it.message)
                _sync.value = SyncState.Failed(friendlySyncError(it.message, connectivity.isOnlineNow()))
            }
    }

    fun resetSync() { _sync.value = SyncState.Idle }

    /** A source's status line: guide channels, stored programmes, and how many of the profile's
     *  channels advertise catch-up (the last is playlist-wide, shown so it's visible alongside the EPG). */
    suspend fun counts(id: Long): Triple<Int, Int, Int> {
        val channels = epgDao.countGuideChannels(listOf(id))
        val programmes = epgDao.countForSources(listOf(id))
        val pid = settings.activeProfileId.first()
        val playlistIds = if (pid < 0) emptyList() else sourceRepository.observeSources(pid).first().map { it.id }
        val catchup = if (playlistIds.isEmpty()) 0 else channelDao.countCatchup(playlistIds)
        return Triple(channels, programmes, catchup)
    }

    /** Playlists whose own EPG can pre-fill the add form (Xtream xmltv.php / M3U url-tvg). */
    suspend fun playlistEpgOptions(): List<PlaylistEpg> {
        val pid = settings.activeProfileId.first()
        if (pid < 0) return emptyList()
        return sourceRepository.observeSources(pid).first()
            .mapNotNull { src -> epgRepository.guideUrl(src)?.let { PlaylistEpg(src.name, it) } }
    }
}