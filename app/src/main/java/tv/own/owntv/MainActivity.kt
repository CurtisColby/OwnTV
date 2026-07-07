package tv.own.owntv

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import tv.own.owntv.core.launcher.LauncherDeepLink
import tv.own.owntv.features.profiles.ProfileGate
import tv.own.owntv.features.profiles.ProfilesViewModel
import tv.own.owntv.features.setup.Onboarding
import tv.own.owntv.features.shell.OwnTVShell
import tv.own.owntv.features.shell.ShellViewModel
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.UiZoom

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "OwnTVHome"
    }

    private val player: tv.own.owntv.player.OwnTVPlayer by inject()
    private val previewEngine: tv.own.owntv.player.LivePreviewEngine by inject()
    private val heroPreviewEngine: tv.own.owntv.player.HeroPreviewEngine by inject()
    // The SAME ShellViewModel instance koinViewModel() resolves inside setContent (both are scoped
    // to this Activity) — gives onStart() a handle for the foreground refresh hook.
    private val shellViewModel: ShellViewModel by viewModel()
    private var pendingDeepLink by mutableStateOf<LauncherDeepLink?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink = LauncherDeepLink.parse(intent.data)
        Log.d(TAG, "onNewIntent deepLinkHost=${intent.data?.host} deepLinkType=${pendingDeepLink?.javaClass?.simpleName ?: "none"}")
    }

    override fun onStop() {
        super.onStop()
        // Backgrounded (Home / another app), exited, or logged out: stop playback and free the demuxer
        // cache + decoder buffers — holding them while invisible got the process LMK-killed on real TVs.
        if (!isChangingConfigurations) {
            player.onAppBackgrounded()
            // Live runs on ExoPlayer — remember the channel and free the stream (its audio must stop too).
            previewEngine.onAppBackgrounded()
            heroPreviewEngine.stop()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        // Paired with onStop: bring back what was freed while backgrounded (notably the TV screensaver, which
        // kicks in during a long pause) — a VOD restored paused at its position, and a live channel re-tuned
        // to the live edge — so Play resumes instead of sitting on a dead/empty stream. No-op on fresh launch.
        player.onAppForegrounded()
        previewEngine.onAppForegrounded()
        // Android TV keeps the process parked for days, so "opening the app" is usually this
        // resume — not a cold start. Re-run the staleness-gated refreshes (EPG feeds + the
        // "refresh on startup" playlist sync) here; both are free no-ops when under 12h old.
        shellViewModel.onAppForegrounded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLink = LauncherDeepLink.parse(intent.data)
        Log.d(TAG, "onCreate deepLinkHost=${intent.data?.host} deepLinkType=${pendingDeepLink?.javaClass?.simpleName ?: "none"}")
        setContent {
            // Hold the screen on while video is actually playing, so the TV screensaver doesn't
            // start mid-channel/episode; released when paused/stopped (then the screensaver is fine).
            val playing by player.isPlaying.collectAsStateWithLifecycle()
            LaunchedEffect(playing) {
                if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            val viewModel: ShellViewModel = koinViewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val accent by viewModel.accent.collectAsStateWithLifecycle()
            val customAccent by viewModel.customAccent.collectAsStateWithLifecycle()
            val uiZoomPercent by viewModel.uiZoomPercent.collectAsStateWithLifecycle()
            val animationLevel by viewModel.animationLevel.collectAsStateWithLifecycle()
            val avatarId by viewModel.avatarId.collectAsStateWithLifecycle()
            val profileName by viewModel.profileName.collectAsStateWithLifecycle()
            val sourceSummary by viewModel.sourceSummary.collectAsStateWithLifecycle()
            val selectedSection by viewModel.selectedSection.collectAsStateWithLifecycle()
            val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
            val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()

            val profilesVm: ProfilesViewModel = koinViewModel()
            val profiles by profilesVm.profiles.collectAsStateWithLifecycle()
            val defaultProfileId by profilesVm.defaultProfileId.collectAsStateWithLifecycle()
            // gatePassed starts TRUE: cold start auto-routes into the shell (no "Who's watching?").
            // It is flipped to false ONLY when the user taps "Switch Profile" in the shell, which is
            // the only time the picker appears — and it's PIN-gated on locked profiles by the picker.
            var gatePassed by remember { mutableStateOf(true) }
            var addingProfile by remember { mutableStateOf(false) }
            // A backup restore deletes-then-reinserts profiles, so the list is briefly EMPTY while
            // the shell is showing — without this, the shell unmounts and remounts, dumping the user
            // out of Settings → Backup & Restore mid-restore. Only the cold start waits for the load.
            var everHadProfiles by remember { mutableStateOf(false) }
            LaunchedEffect(profiles) { if (profiles.isNotEmpty()) everHadProfiles = true }

            // Cold-start default routing: exactly ONCE per process launch, force the active profile to
            // the launch default. This guarantees a restart always returns to the default (e.g. Main)
            // even if the app was closed while inside a switched-to, PIN-locked profile. The default is
            // the chosen default profile if it's set and still UNLOCKED; otherwise the first unlocked
            // profile; otherwise (no unlocked profile exists at all) the first profile.
            var coldStartRouted by remember { mutableStateOf(false) }
            LaunchedEffect(profiles, defaultProfileId) {
                if (coldStartRouted || profiles.isEmpty()) return@LaunchedEffect
                val chosen = profiles.firstOrNull { it.id == defaultProfileId && it.pinHash == null }
                    ?: profiles.firstOrNull { it.pinHash == null }
                    ?: profiles.first()
                if (activeProfileId != chosen.id) viewModel.setActiveProfileId(chosen.id)
                coldStartRouted = true
            }

            // "Refresh on startup" — re-sync sources once the active profile is known.
            LaunchedEffect(activeProfileId) {
                if ((activeProfileId ?: -1L) >= 0L) viewModel.refreshOnStartIfEnabled()
            }

            OwnTVTheme(themeMode = themeMode, accent = accent, systemInDarkTheme = isSystemInDarkTheme(), customAccent = customAccent, animationLevel = animationLevel) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density * UiZoom.factor(uiZoomPercent), base.fontScale),
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(OwnTVTheme.colors.background)) {
                        val profile = activeProfileId
                        when {
                            profile == null -> Unit // loading
                            // Adding a profile from the gate → onboard the new profile.
                            addingProfile -> Onboarding(
                                firstRun = false,
                                onDone = { addingProfile = false; gatePassed = true },
                                onCancel = { addingProfile = false },
                                modifier = Modifier.fillMaxSize(),
                            )
                            // First run (no profile yet) → full onboarding.
                            profile < 0L -> Onboarding(
                                firstRun = true,
                                onDone = { gatePassed = true },
                                onCancel = {},
                                modifier = Modifier.fillMaxSize(),
                            )
                            // Profiles still loading (≥0 means at least one exists) — avoid a gate/shell flicker.
                            profiles.isEmpty() && !everHadProfiles -> Unit
                            // Picker appears ONLY when the user tapped "Switch Profile" in the shell
                            // (gatePassed flipped false). Cold start never lands here — it auto-routes
                            // to the default below. Locked profiles are PIN-gated inside the picker.
                            !gatePassed -> ProfileGate(
                                onEnter = { gatePassed = true },
                                onAddProfile = { addingProfile = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                            else -> OwnTVShell(
                                selectedSection = selectedSection,
                                onSelectSection = viewModel::selectSection,
                                themeMode = themeMode,
                                uiZoomPercent = uiZoomPercent,
                                onSetZoom = viewModel::setUiZoom,
                                avatarId = avatarId,
                                onSetAvatar = viewModel::setAvatar,
                                profileName = profileName,
                                sourceSummary = sourceSummary,
                                activeProfileId = activeProfileId,
                                pendingDeepLink = pendingDeepLink,
                                onDeepLinkConsumed = { pendingDeepLink = null },
                                isOffline = !isOnline,
                                onExitApp = { finish() },
                                onSwitchProfile = {
                                    // Stop playback and show the profile picker (PIN-gated on locked
                                    // profiles). Session-only: the next cold start returns to the default.
                                    player.onAppBackgrounded(); player.discardBackgroundRestore(); previewEngine.stop(); previewEngine.discardBackgroundRestore(); heroPreviewEngine.stop(); gatePassed = false
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}