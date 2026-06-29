package tv.own.owntv.features.settings

import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.repository.EpgRepository

/**
 * Shared semi-auto EPG sync used by both onboarding and Settings → Playlists. Registers the playlist's own
 * guide feed as an [EpgSource][tv.own.owntv.core.epg.EpgSource] in the [store] OWNED BY [profileId]
 * (re-using an existing entry with the same URL under that same profile) so it appears in Settings → EPG
 * for that profile only, then downloads it keyed by that source id while reporting a live programme count
 * through [setState].
 */
suspend fun runSemiAutoEpgSync(
    source: SourceEntity,
    profileId: Long,
    epgRepository: EpgRepository,
    store: EpgSourceStore,
    setState: (EpgSyncUi) -> Unit,
) {
    val url = epgRepository.guideUrl(source) ?: run { setState(EpgSyncUi.Done); return }
    setState(EpgSyncUi.Syncing(0))
    // Show up in Settings → EPG for THIS profile like any other feed (so the user can re-sync / delete it
    // there). add() dedups per-(profile,url), so this never reuses another profile's row.
    val epgSource = store.add(source.name, url, profileId, source.userAgent)
    val now = System.currentTimeMillis()
    runCatching {
        epgRepository.refreshUrl(epgSource.id, epgSource.url, epgSource.userAgent) { count -> setState(EpgSyncUi.Syncing(count)) }
    }
        .onSuccess { store.setSynced(epgSource.id, now, null) }
        .onFailure { store.setSynced(epgSource.id, now, it.message) }
    setState(EpgSyncUi.Done)
}