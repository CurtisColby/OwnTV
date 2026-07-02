package tv.own.owntv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import tv.own.owntv.core.network.HttpClient

/**
 * ExoPlayer engine for the Home screen hero preview.
 *
 * It is intentionally small: muted only, VOD start-position support, no HUD integration, and a single surface.
 * The Home screen keeps it alive while the hero is focused so the preview starts quickly and can be
 * reused across hero items without rebuilding the player each time.
 *
 * Retry logic: Plex FAST channels take ~6s for their CDN to spin up a stream session. If ExoPlayer
 * errors before a single frame has rendered (hasStarted == false), we retry up to MAX_RETRIES times
 * with RETRY_DELAY_MS between attempts instead of giving up immediately. This covers the CDN startup
 * window without changing behaviour for streams that genuinely can't play (those still reach ERROR
 * after all retries are exhausted).
 */
@UnstableApi
class HeroPreviewEngine(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    enum class State { IDLE, LOADING, PLAYING, ERROR }

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var hasStarted = false

    // Retry state — reset on every new play() call and on stop().
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var pendingUrl: String? = null
    private var pendingSeekMs: Long = 0L

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    var currentUrl: String? = null
        private set

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (currentUrl == null && playbackState != Player.STATE_IDLE) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (!hasStarted) _state.value = State.LOADING
                }
                Player.STATE_READY -> {
                    hasStarted = true
                    retryCount = 0 // healthy — reset so a future mid-stream hiccup gets fresh retries
                }
                Player.STATE_ENDED -> {
                    // REPEAT_MODE_ONE should keep the current clip looping; this is just a fallback.
                }
                else -> Unit
            }
        }

        override fun onRenderedFirstFrame() {
            if (currentUrl != null) _state.value = State.PLAYING
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w(TAG, "Hero preview error (attempt ${retryCount + 1}/$MAX_RETRIES): ${error.errorCodeName}", error)

            // If a frame was already rendered this is a mid-stream drop — give up immediately,
            // the user can re-focus to trigger a fresh play().
            if (hasStarted) {
                hasStarted = false
                currentUrl = null
                player?.run { stop(); clearMediaItems() }
                _state.value = State.ERROR
                return
            }

            // Stream never opened — could be a CDN spin-up error (Plex FAST takes ~6s).
            // Retry a few times before declaring ERROR.
            if (retryCount < MAX_RETRIES) {
                retryCount++
                val url = pendingUrl ?: run {
                    _state.value = State.ERROR
                    return
                }
                val seekMs = pendingSeekMs
                android.util.Log.i(TAG, "Hero preview retry $retryCount/$MAX_RETRIES for $url in ${RETRY_DELAY_MS}ms")
                _state.value = State.LOADING
                player?.run { stop(); clearMediaItems() }
                mainHandler.postDelayed({
                    if (pendingUrl != url) return@postDelayed // user moved to a different card — don't replay stale URL
                    runCatching {
                        val p = player ?: build().also { player = it }
                        surface?.let { p.setVideoSurface(it) }
                        p.volume = 0f
                        p.repeatMode = Player.REPEAT_MODE_ONE
                        p.setMediaItem(MediaItem.fromUri(url), seekMs.coerceAtLeast(0L))
                        p.prepare()
                        p.playWhenReady = true
                    }.onFailure {
                        android.util.Log.w(TAG, "Hero preview retry play() failed", it)
                        _state.value = State.ERROR
                    }
                }, RETRY_DELAY_MS)
            } else {
                // All retries exhausted — genuine failure.
                android.util.Log.w(TAG, "Hero preview giving up after $MAX_RETRIES retries")
                hasStarted = false
                currentUrl = null
                pendingUrl = null
                player?.run { stop(); clearMediaItems() }
                _state.value = State.ERROR
            }
        }
    }

    fun setSurface(s: Surface?) {
        surface = s
        if (s != null) player?.setVideoSurface(s) else player?.clearVideoSurface()
    }

    fun play(url: String, seekToMs: Long = 0L) {
        mainHandler.removeCallbacksAndMessages(null) // cancel any pending retry
        currentUrl = url
        pendingUrl = url
        pendingSeekMs = seekToMs
        retryCount = 0
        val startPositionMs = seekToMs.coerceAtLeast(0L)
        hasStarted = false
        _state.value = State.LOADING
        runCatching {
            val p = player ?: build().also { player = it }
            surface?.let { p.setVideoSurface(it) }
            p.volume = 0f
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.setMediaItem(MediaItem.fromUri(url), startPositionMs)
            p.prepare()
            p.playWhenReady = true
        }.onFailure {
            android.util.Log.w(TAG, "Hero preview play failed for $url", it)
            hasStarted = false
            currentUrl = null
            pendingUrl = null
            player?.run {
                stop()
                clearMediaItems()
            }
            _state.value = State.ERROR
        }
    }

    fun stop() {
        mainHandler.removeCallbacksAndMessages(null) // cancel any pending retry
        currentUrl = null
        pendingUrl = null
        retryCount = 0
        hasStarted = false
        _state.value = State.IDLE
        player?.run {
            stop()
            clearMediaItems()
        }
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
        surface = null
        hasStarted = false
        currentUrl = null
        pendingUrl = null
        retryCount = 0
        _state.value = State.IDLE
    }

    private fun build(): ExoPlayer {
        val dataSource = OkHttpDataSource.Factory(okHttpClient).setUserAgent(HttpClient.DEFAULT_USER_AGENT)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 8_000, 1_000, 2_000)
            .build()
        return ExoPlayer.Builder(context)
            .setRenderersFactory(DefaultRenderersFactory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .build()
            .apply { addListener(listener) }
    }

    companion object {
        private const val TAG = "HeroPreviewEngine"
        private const val MAX_RETRIES = 3          // 3 retries = up to ~12s of CDN spin-up time covered
        private const val RETRY_DELAY_MS = 4_000L  // wait 4s between attempts (CDN needs ~6s total)
    }
}