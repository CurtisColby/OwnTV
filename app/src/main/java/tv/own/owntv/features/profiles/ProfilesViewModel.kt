package tv.own.owntv.features.profiles

import kotlinx.coroutines.flow.first
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.util.Pin
import tv.own.owntv.features.settings.data.SettingsRepository

/**
 * Phase 6.5 — profile creation/switching and the launch gate's data. Shared by the "Who's watching?"
 * gate and the Settings → Profiles management screen.
 */
class ProfilesViewModel(
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val launcherIntegrationRepository: LauncherIntegrationRepository,
) : ViewModel() {

    val profiles: StateFlow<List<ProfileEntity>> = profileDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Make [profile] active (routes the app into the shell) once the preference write commits. */
    fun switchTo(profile: ProfileEntity, onSwitched: () -> Unit = {}) {
        viewModelScope.launch {
            settings.setActiveProfile(profile.id)
            onSwitched()
        }
    }

    fun verifyPin(profile: ProfileEntity, pin: String): Boolean = Pin.verify(pin, profile.pinHash)

    /**
     * Create a new profile. New profiles start EMPTY (no sources) — each profile owns its own
     * channel lineup. Add sources to it from Settings → Manage Sources while that profile is active.
     * Favorites/history are already per-profile.
     */
    fun create(name: String, avatarId: Int, isKids: Boolean, pin: String?, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = profileDao.insert(
                ProfileEntity(
                    name = name.ifBlank { "Profile" },
                    avatarColor = 0,
                    avatarId = avatarId,
                    isKids = isKids,
                    pinHash = pin?.takeIf { it.isNotBlank() }?.let { Pin.hash(it) },
                ),
            )
            // New profiles start with no sources by design (separate lineups per profile).
            onCreated(id)
        }
    }

    /** Apply edits from the editor dialog. [pin]: null = keep the existing PIN, "" = remove it. */
    fun edit(profile: ProfileEntity, name: String, avatarId: Int, isKids: Boolean, pin: String?) {
        viewModelScope.launch {
            val pinHash = when {
                pin == null -> profile.pinHash
                pin.isEmpty() -> null
                else -> Pin.hash(pin)
            }
            profileDao.update(profile.copy(name = name.ifBlank { profile.name }, avatarId = avatarId, isKids = isKids, pinHash = pinHash))
        }
    }

    fun rename(profile: ProfileEntity, name: String) {
        viewModelScope.launch { profileDao.update(profile.copy(name = name.ifBlank { profile.name })) }
    }

    fun setKids(profile: ProfileEntity, isKids: Boolean) {
        viewModelScope.launch { profileDao.update(profile.copy(isKids = isKids)) }
    }

    fun setPin(profile: ProfileEntity, pin: String?) {
        viewModelScope.launch { profileDao.setPin(profile.id, pin?.takeIf { it.isNotBlank() }?.let { Pin.hash(it) }) }
    }

    fun delete(profile: ProfileEntity) {
        viewModelScope.launch {
            // Never delete the last profile.
            if (profileDao.count() <= 1) return@launch
            val activeProfileId = settings.activeProfileId.first()
            val remainingProfileId = profileDao.getAllOnce().firstOrNull { it.id != profile.id }?.id
            runCatching { launcherIntegrationRepository.clearProfile(profile.id) }
            profileDao.delete(profile)
            if (activeProfileId == profile.id) {
                settings.setActiveProfile(remainingProfileId ?: -1L)
            }
            // If the deleted profile was the launch default, clear it so the launch resolver falls
            // back to the first unlocked profile rather than pointing at a row that no longer exists.
            if (settings.defaultProfileId.first() == profile.id) {
                settings.setDefaultProfile(-1L)
            }
        }
    }

    /** The profile id the app opens into on cold start (-1 = not set; resolver falls back to first unlocked). */
    val defaultProfileId: StateFlow<Long> = settings.defaultProfileId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1L)

    /**
     * Mark [profile] as the launch default. Guarded: a PIN-locked profile can never be the default,
     * because launch must open without a PIN prompt. Callers should not offer this for locked
     * profiles; this is the backstop.
     */
    fun setDefaultProfile(profile: ProfileEntity) {
        if (profile.pinHash != null) return
        viewModelScope.launch { settings.setDefaultProfile(profile.id) }
    }
}