@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package tv.own.owntv.features.movies

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.applyCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.FavoriteDao
import tv.own.owntv.core.database.dao.HistoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.database.entity.DownloadEntity
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.WatchHistoryEntity
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.features.live.LiveRailItem
import tv.own.owntv.features.live.LiveKey
import tv.own.owntv.core.download.DownloadManager
import tv.own.owntv.core.storage.StorageAccess
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.player.MediaMeta
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlaylistItem
import tv.own.owntv.ui.components.OwnTVIcon

class MovieViewModel(
    private val movieDao: MovieDao,
    private val categoryDao: CategoryDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val progressDao: ProgressDao,
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val player: OwnTVPlayer,
    private val downloadManager: DownloadManager,
    private val launcherIntegrationRepository: LauncherIntegrationRepository,
) : ViewModel() {

    private data class Ctx(val profileId: Long, val sourceIds: List<Long>)
    // Observe the active profile's sources reactively so adding/removing a playlist refreshes Movies
    // immediately (was read once at startup, so a new playlist showed nothing until app restart).
    private val ctx: StateFlow<Ctx> = settings.activeProfileId
        .flatMapLatest { pid ->
            if (pid < 0) flowOf(Ctx(pid, emptyList()))
            else sourceDao.observeForProfile(pid).map { srcs -> Ctx(pid, srcs.map { it.id }) }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, Ctx(-1L, emptyList()))

    /** List ordering for this section (Provider order vs A–Z), persisted in DataStore. */
    val sortMode: StateFlow<SettingsRepository.SortMode> = settings.sortMovies
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.SortMode.ALPHA)

    fun toggleSort() {
        viewModelScope.launch {
            settings.setSortMovies(
                if (sortMode.value == SettingsRepository.SortMode.PLAYLIST) SettingsRepository.SortMode.ALPHA
                else SettingsRepository.SortMode.PLAYLIST,
            )
        }
    }

    val viewMode: StateFlow<SettingsRepository.VodViewMode> = settings.vodViewMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.VodViewMode.GRID)

    fun toggleViewMode() {
        viewModelScope.launch {
            settings.setVodViewMode(
                if (viewMode.value == SettingsRepository.VodViewMode.GRID) SettingsRepository.VodViewMode.LIST
                else SettingsRepository.VodViewMode.GRID,
            )
        }
    }

    private val _selected = MutableStateFlow<LiveKey>(LiveKey.All)
    val selectedKey: StateFlow<LiveKey> = _selected.asStateFlow()

    private val _search = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _search.asStateFlow()

    private val _selectedMovie = MutableStateFlow<MovieEntity?>(null)
    val selectedMovie: StateFlow<MovieEntity?> = _selectedMovie.asStateFlow()

    private var playingMovie: MovieEntity? = null

    // --- Shuffle Play All state: the URLs of the queue we armed (so a foreign queue's end never
    // triggers a movie reshuffle) and whether shuffle mode is live right now. ---
    private var shuffleActive = false
    private var shuffleUrls: Set<String> = emptySet()

    // --- Browse-queue state: the movies handed to the player when the user deliberately picks a
    // title. Kept so resume-position tracking can follow along when D-pad Down/Up or auto-advance
    // moves to another item in that queue. Empty whenever no browse queue is armed. ---
    private var queuedMovies: List<MovieEntity> = emptyList()

    init {
        // Periodically persist resume position for the movie currently playing.
        viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                saveProgressNow()
            }
        }
        // Continuous shuffle: when the LAST item of a queue finishes and it was OUR shuffle queue,
        // deal a fresh shuffled batch and keep going — surfing never stops until the user backs out.
        viewModelScope.launch {
            player.queueEnded.collect {
                val url = player.currentMediaUrl ?: return@collect
                if (shuffleActive && url in shuffleUrls) startShuffleQueue()
            }
        }
        // Browse-queue surf: when D-pad Down/Up or auto-advance moves the player to a different item
        // in the armed queue, re-point the tracked movie at it. Without this, playingMovie stays
        // stuck on the originally picked title and saveProgressNow's url guard silently stops saving
        // — every movie after the first would lose its resume position.
        viewModelScope.launch {
            player.currentMeta.collect {
                if (queuedMovies.isEmpty()) return@collect
                val url = player.currentMediaUrl ?: return@collect
                if (url == playingMovie?.streamUrl) return@collect
                queuedMovies.firstOrNull { it.streamUrl == url }?.let { playingMovie = it }
            }
        }
        // Profile switch: wipe ALL per-profile UI state the moment the active profile changes, even
        // while this screen is hidden. Leftover state pointed at the OLD profile's rows (a Folder key
        // holds a category-row id of the old profile's source, and category queries have no source
        // guard), which kept showing — and could even shuffle — the previous profile's content until
        // a rail click installed fresh state. A privacy leak between profiles, not just staleness.
        viewModelScope.launch {
            var lastPid = -1L
            ctx.map { it.profileId }.distinctUntilChanged().collect { pid ->
                if (lastPid >= 0 && pid != lastPid) {
                    _selected.value = LiveKey.All
                    selectedFolderTitle = null
                    _search.value = ""
                    _selectedMovie.value = null
                    playingMovie = null
                    shuffleActive = false
                    shuffleUrls = emptySet()
                    queuedMovies = emptyList()
                }
                lastPid = pid
            }
        }
    }

    val railItems: StateFlow<List<LiveRailItem>> = ctx
        .flatMapLatest { c ->
            if (c.profileId < 0) flowOf(defaultRail)
            else combine(
                categoryDao.observe(c.sourceIds, MediaType.MOVIE),
                customize.observe(c.profileId, MediaType.MOVIE),
            ) { cats, cust ->
                defaultRail + cats.applyCustomizations(cust).map { (cat, name) ->
                    LiveRailItem(LiveKey.Folder(cat.id), name.take(3).uppercase(), name)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaultRail)

    /** The selected genre folder's NAME. Category row ids are wiped and re-generated on every sync
     *  (refreshCategories clears + reinserts), so a held Folder(id) can silently start pointing at a
     *  DIFFERENT genre after a background refresh — the grid and Shuffle would then show/play the
     *  wrong content. The name is the stable handle; the id is re-resolved from it below. */
    private var selectedFolderTitle: String? = null

    // Second init (must run AFTER railItems is initialized — viewModelScope launches immediately):
    // whenever the rail rebuilds, verify the selected Folder id still exists; if a sync re-dealt the
    // ids, re-resolve the selection by name, and fall back to All when the genre is gone entirely.
    init {
        viewModelScope.launch {
            railItems.collect { items ->
                val sel = _selected.value
                if (sel !is LiveKey.Folder) return@collect
                val current = items.firstOrNull { it.key == sel }
                if (current != null) { selectedFolderTitle = current.title; return@collect }
                val byName = selectedFolderTitle?.let { t -> items.firstOrNull { it.key is LiveKey.Folder && it.title == t } }
                _selected.value = byName?.key ?: LiveKey.All
            }
        }
    }

    val movies: Flow<PagingData<MovieEntity>> = combine(
        _selected, ctx, _search.map { it.trim() }.debounce(300).distinctUntilChanged(), sortMode,
    ) { key, c, query, sort -> Args(key, c, query, sort) }
        .flatMapLatest { (key, c, query, sort) ->
            Pager(PagingConfig(pageSize = 60, prefetchDistance = 30, initialLoadSize = 90, maxSize = 300)) {
                pagingSource(key, c, query, sort)
            }.flow
        }
        .cachedIn(viewModelScope)

    private data class Args(val key: LiveKey, val ctx: Ctx, val query: String, val sort: SettingsRepository.SortMode)

    val count: StateFlow<Int> = combine(_selected, ctx) { key, c -> key to c }
        .flatMapLatest { (key, c) -> countFlow(key, c) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val favoriteIds: StateFlow<Set<Long>> = ctx
        .flatMapLatest { favoriteDao.observeFavoriteIds(it.profileId, MediaType.MOVIE) }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val selectedProgress: StateFlow<PlaybackProgressEntity?> = combine(_selectedMovie, ctx) { m, c -> m to c }
        .flatMapLatest { (m, c) ->
            if (m == null) flowOf(null) else progressDao.observe(c.profileId, MediaType.MOVIE, m.id)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun select(key: LiveKey) {
        _selected.value = key
        selectedFolderTitle = if (key is LiveKey.Folder) railItems.value.firstOrNull { it.key == key }?.title else null
    }
    fun setSearchQuery(query: String) { _search.value = query }
    fun onMovieFocused(movie: MovieEntity) { _selectedMovie.value = movie }

    /** The user's resume preference (Always / Ask / Never) — the screen drives the prompt. */
    val resumeMode: StateFlow<SettingsRepository.ResumeMode> = settings.resumeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.ResumeMode.ASK)

    /** Saved resume position for [movie] (0 when none) — used by the screen to decide the prompt. */
    suspend fun savedPositionMs(movie: MovieEntity): Long =
        currentProfileId()?.let { progressDao.get(it, MediaType.MOVIE, movie.id)?.positionMs ?: 0 } ?: 0

    /** Shuffle Play All: deal a random queue from the current rail scope (All / genre folder /
     *  Favorites — History falls back to All) and start playing it front to back. The player's
     *  auto-play advances movie→movie at each natural end; queueEnded re-deals so it never stops.
     *  Returns false when the scope has nothing to shuffle (screen stays put). */
    suspend fun shufflePlayAllAsync(): Boolean {
        val ok = startShuffleQueue()
        shuffleActive = ok
        return ok
    }

    /** Builds one shuffled batch and hands it to the player. Shared by the button and the
     *  continuous-reshuffle collector. */
    private suspend fun startShuffleQueue(): Boolean {
        val c = ctx.value
        val ids = c.sourceIds.ifEmpty { return false }
        val batch = when (val key = _selected.value) {
            is LiveKey.Folder -> movieDao.randomInCategory(key.id, ids, SHUFFLE_BATCH)
            LiveKey.Favorites -> movieDao.randomFavorites(c.profileId, SHUFFLE_BATCH)
            else -> movieDao.randomAll(ids, SHUFFLE_BATCH) // All + History both surf the whole scope
        }
        if (batch.isEmpty()) return false
        Log.d(TAG, "shufflePlayAll batch=${batch.size} key=${_selected.value}")
        shuffleUrls = batch.map { it.streamUrl }.toSet()
        playingMovie = null // surf mode: no single tracked movie, so no resume-position writes
        queuedMovies = emptyList() // shuffle owns the player now; no browse queue to track
        player.playEpisodes(
            items = batch.map { m ->
                PlaylistItem(
                    url = m.streamUrl,
                    meta = MediaMeta(title = m.name, subtitle = "Shuffle · Movies", year = m.year?.toString(), logoUrl = m.posterUrl),
                )
            },
            startIndex = 0,
        )
        return true
    }

    /** Play a deliberately picked movie. The rail it was picked from is handed to the player as a
     *  queue with this movie as the start index, so D-pad Down/Up surfs to the neighbouring titles
     *  and a finished movie rolls into the next one — the same feel as Shuffle, but in list order.
     *  Falls back to old single-item playback when the queue can't be built (see [buildBrowseQueue]). */
    fun play(movie: MovieEntity, startPositionMs: Long = 0) {
        shuffleActive = false // a deliberate single pick ends surf mode
        viewModelScope.launch {
            val pid = currentProfileId()
            val queue = buildBrowseQueue()
            val startIndex = queue.indexOfFirst { it.id == movie.id }
            Log.d(
                TAG,
                "play movieId=${movie.id} profile=$pid startPositionMs=$startPositionMs " +
                    "queue=${queue.size} startIndex=$startIndex",
            )
            if (startIndex >= 0 && queue.size > 1) {
                val label = browseScopeLabel()
                queuedMovies = queue
                player.playEpisodes(
                    items = queue.map { m ->
                        PlaylistItem(
                            url = m.streamUrl,
                            meta = MediaMeta(
                                title = m.name,
                                subtitle = label,
                                year = m.year?.toString(),
                                logoUrl = m.posterUrl,
                            ),
                        )
                    },
                    startIndex = startIndex,
                    startPositionMs = startPositionMs,
                )
            } else {
                queuedMovies = emptyList()
                player.play(
                    movie.streamUrl,
                    title = movie.name,
                    year = movie.year?.toString(),
                    isLive = false,
                    startPositionMs = startPositionMs,
                )
            }
            playingMovie = movie
            if (pid != null) {
                runCatching {
                    historyDao.record(WatchHistoryEntity(profileId = pid, mediaType = MediaType.MOVIE, itemId = movie.id))
                }.onFailure { t ->
                    Log.w(TAG, "play history record failed movieId=${movie.id} profile=$pid", t)
                }
            }
        }
    }

    fun playById(movieId: Long, startPositionMs: Long = 0) {
        viewModelScope.launch {
            val movie = movieDao.getById(movieId) ?: return@launch
            play(movie, startPositionMs)
        }
    }

    suspend fun playByIdAsync(movieId: Long, startPositionMs: Long = 0): Boolean {
        val movie = movieDao.getById(movieId) ?: return false
        play(movie, startPositionMs)
        return true
    }

    /** Download states for the currently visible movies, keyed by movie id. */
    val downloadStates: StateFlow<Map<Long, DownloadEntity>> = ctx
        .flatMapLatest { c -> if (c.profileId < 0) flowOf(emptyList()) else downloadManager.observe(c.profileId) }
        .map { list -> list.filter { it.mediaType == MediaType.MOVIE }.associateBy { it.itemId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun download(movie: MovieEntity) {
        viewModelScope.launch {
            val pid = currentProfileId() ?: return@launch
            downloadManager.enqueue(
                profileId = pid,
                mediaType = MediaType.MOVIE,
                itemId = movie.id,
                title = movie.name,
                posterUrl = movie.posterUrl,
                streamUrl = movie.streamUrl,
                relativeDir = "Movies",
                fileName = "${StorageAccess.sanitize(movie.name)}.${movie.containerExt ?: StorageAccess.extOf(movie.streamUrl)}",
            )
        }
    }

    fun toggleFavorite(movie: MovieEntity) {
        viewModelScope.launch {
            val pid = currentProfileId() ?: return@launch
            if (favoriteIds.value.contains(movie.id)) favoriteDao.remove(pid, MediaType.MOVIE, movie.id)
            else favoriteDao.add(FavoriteEntity(profileId = pid, mediaType = MediaType.MOVIE, itemId = movie.id))
        }
    }

    /** Persist the resume position if the player is actually playing the tracked movie. */
    fun saveProgressNow() {
        val m = playingMovie ?: return
        if (player.currentMediaUrl != m.streamUrl || !player.isPlaying.value) return
        val pos = player.position.value
        val dur = player.duration.value
        if (pos > 0 && dur > 0) {
            viewModelScope.launch {
                val pid = currentProfileId() ?: return@launch
                Log.d(TAG, "saveProgressNow movieId=${m.id} profile=$pid positionMs=$pos durationMs=$dur")
                runCatching {
                    progressDao.save(
                        PlaybackProgressEntity(profileId = pid, mediaType = MediaType.MOVIE, itemId = m.id, positionMs = pos, durationMs = dur),
                    )
                }.onFailure { t ->
                    Log.w(TAG, "saveProgressNow progress save failed movieId=${m.id} profile=$pid", t)
                }
                launcherIntegrationRepository.publishMovieProgress(pid, m.id, pos, dur)
            }
        }
    }

    private suspend fun currentProfileId(): Long? {
        val preferred = settings.activeProfileId.first()
        return if (preferred >= 0) profileDao.resolveExistingProfileId(preferred) else null
    }

    /** Materialise the currently browsed rail, in the exact order shown on screen, bounded to
     *  [BROWSE_QUEUE_MAX]. Mirrors [pagingSource] case for case — same scope, same sort, same
     *  search — so the queue the player gets is the list the user is looking at. Returns an empty
     *  list when there are no sources, which makes [play] fall back to single-item playback. */
    private suspend fun buildBrowseQueue(): List<MovieEntity> {
        val c = ctx.value
        if (c.sourceIds.isEmpty()) return emptyList()
        val ids = c.sourceIds
        val query = _search.value.trim()
        val playlist = sortMode.value == SettingsRepository.SortMode.PLAYLIST
        val max = BROWSE_QUEUE_MAX
        return runCatching {
            if (query.isBlank()) when (val key = _selected.value) {
                LiveKey.All -> if (playlist) movieDao.listAllOriginal(ids, max) else movieDao.listAll(ids, max)
                LiveKey.Favorites -> movieDao.listFavorites(c.profileId, max)
                LiveKey.History -> movieDao.listHistory(c.profileId, max)
                is LiveKey.Folder ->
                    if (playlist) movieDao.listByCategory(key.id, ids, max)
                    else movieDao.listByCategoryAlpha(key.id, ids, max)
            } else when (val key = _selected.value) {
                LiveKey.All -> movieDao.searchList(query, ids, max)
                LiveKey.Favorites -> movieDao.searchListFavorites(query, c.profileId, max)
                LiveKey.History -> movieDao.searchListHistory(query, c.profileId, max)
                is LiveKey.Folder -> movieDao.searchListInCategory(query, key.id, ids, max)
            }
        }.onFailure { t ->
            Log.w(TAG, "buildBrowseQueue failed key=${_selected.value}", t)
        }.getOrDefault(emptyList())
    }

    /** What the player's "now watching" card shows underneath the title — the rail being surfed. */
    private fun browseScopeLabel(): String = when (_selected.value) {
        LiveKey.Favorites -> "Favorites · Movies"
        LiveKey.History -> "History · Movies"
        is LiveKey.Folder -> selectedFolderTitle?.let { "$it · Movies" } ?: "Movies"
        else -> "Movies"
    }

    private fun pagingSource(key: LiveKey, c: Ctx, query: String, sort: SettingsRepository.SortMode): PagingSource<Int, MovieEntity> {
        val ids = c.sourceIds.ifEmpty { listOf(-1L) }
        val playlist = sort == SettingsRepository.SortMode.PLAYLIST
        return if (query.isBlank()) when (key) {
            LiveKey.All -> if (playlist) movieDao.pagingAllOriginal(ids) else movieDao.pagingAll(ids)
            LiveKey.Favorites -> movieDao.pagingFavorites(c.profileId)
            LiveKey.History -> movieDao.pagingHistory(c.profileId)
            is LiveKey.Folder -> if (playlist) movieDao.pagingByCategory(key.id) else movieDao.pagingByCategoryAlpha(key.id)
        } else when (key) {
            LiveKey.All -> movieDao.searchAll(query, ids)
            LiveKey.Favorites -> movieDao.searchFavorites(query, c.profileId)
            LiveKey.History -> movieDao.searchHistory(query, c.profileId)
            is LiveKey.Folder -> movieDao.searchInCategory(query, key.id)
        }
    }

    private fun countFlow(key: LiveKey, c: Ctx): Flow<Int> {
        val ids = c.sourceIds.ifEmpty { listOf(-1L) }
        return when (key) {
            LiveKey.All -> movieDao.countAll(ids)
            LiveKey.Favorites -> movieDao.countFavorites(c.profileId)
            LiveKey.History -> historyDao.count(c.profileId, MediaType.MOVIE)
            is LiveKey.Folder -> movieDao.countByCategory(key.id)
        }
    }

    private companion object {
        const val TAG = "OwnTVHome"
        /** Max movies dealt per shuffle round — plenty of runway, and queueEnded re-deals anyway. */
        const val SHUFFLE_BATCH = 300

        /** Max titles pulled into a browse queue on a deliberate pick. Bounds memory on huge rails;
         *  if the picked movie sits past this cut, play() falls back to single-item playback. */
        const val BROWSE_QUEUE_MAX = 1000
        val defaultRail = listOf(
            LiveRailItem(LiveKey.Favorites, "FAV", "Favorites", OwnTVIcon.STAR),
            LiveRailItem(LiveKey.History, "HIS", "History", OwnTVIcon.HISTORY),
            LiveRailItem(LiveKey.All, "ALL", "All Movies"),
        )
    }
}