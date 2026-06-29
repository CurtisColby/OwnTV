package tv.own.owntv.core.epg

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** A standalone XMLTV EPG feed (not tied to a playlist). Guide data merges into the shared tables. */
data class EpgSource(
    val id: Long,            // synthetic, always negative so it never collides with a Room source id
    val name: String,
    val url: String,
    val profileId: Long = -1L, // owning profile; -1 = legacy/unowned (hidden from every profile under strict scoping)
    val userAgent: String? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
)

private val Context.epgStore: DataStore<Preferences> by preferencesDataStore(name = "owntv_epg_sources")

/**
 * Stores the user's EPG (XMLTV) sources as a JSON list in DataStore — NOT a Room table, because the
 * database does a destructive migration on schema changes (which would wipe all synced content). The
 * parsed guide still lands in the existing epg_channels / epg_programmes tables, keyed by each EPG
 * source's negative [EpgSource.id]; the guide query simply includes those ids alongside the playlist
 * ids, so channels match guide entries from any feed by their EPG id.
 *
 * Each source is OWNED by a profile via [EpgSource.profileId]. Reads are scoped per profile so two
 * profiles' feeds can never collapse into one slot or bleed across a profile switch. A profileId of
 * -1 means "legacy/unowned" — it belongs to no profile under strict scoping and is therefore invisible
 * to every profile (the user re-adds feeds once under each profile, after which they're locked in).
 */
class EpgSourceStore(private val context: Context) {

    private object Keys {
        val LIST = stringPreferencesKey("sources")
        val NEXT_ID = longPreferencesKey("next_id") // counts down: -1, -2, …
        val MIGRATED = longPreferencesKey("migrated_v1")
    }

    /** ALL sources, every profile. Use only where a genuine app-wide view is needed. */
    val sources: Flow<List<EpgSource>> = context.epgStore.data.map { prefs ->
        parse(prefs[Keys.LIST])
    }

    /** Only the given profile's sources (strict: profileId must match exactly). */
    fun sourcesForProfile(profileId: Long): Flow<List<EpgSource>> = context.epgStore.data.map { prefs ->
        parse(prefs[Keys.LIST]).filter { it.profileId == profileId }
    }

    suspend fun getAll(): List<EpgSource> = parse(context.epgStore.data.first()[Keys.LIST])

    /** Only the given profile's sources (strict: profileId must match exactly). */
    suspend fun getForProfile(profileId: Long): List<EpgSource> =
        parse(context.epgStore.data.first()[Keys.LIST]).filter { it.profileId == profileId }

    suspend fun add(name: String, url: String, profileId: Long, userAgent: String? = null): EpgSource {
        var created: EpgSource? = null
        context.epgStore.edit { prefs ->
            val existing = parse(prefs[Keys.LIST])
            // Per-(profile,url) dedup: same URL under a DIFFERENT profile is a distinct row and must
            // never overwrite the other profile's feed. Same URL under the SAME profile reuses the row.
            val match = existing.firstOrNull { it.profileId == profileId && it.url == url.trim() }
            if (match != null) {
                created = match
                return@edit
            }
            val id = (prefs[Keys.NEXT_ID] ?: -1L)
            prefs[Keys.NEXT_ID] = id - 1
            val source = EpgSource(
                id = id,
                name = name.trim().ifBlank { "EPG" },
                url = url.trim(),
                profileId = profileId,
                userAgent = userAgent?.trim()?.takeIf { it.isNotBlank() },
            )
            created = source
            prefs[Keys.LIST] = write(existing + source)
        }
        return created!!
    }

    suspend fun update(source: EpgSource) {
        context.epgStore.edit { prefs ->
            prefs[Keys.LIST] = write(parse(prefs[Keys.LIST]).map { if (it.id == source.id) source else it })
        }
    }

    suspend fun remove(id: Long) {
        context.epgStore.edit { prefs ->
            prefs[Keys.LIST] = write(parse(prefs[Keys.LIST]).filterNot { it.id == id })
        }
    }

    /** Stamp a source after a sync attempt (success → error null; failure → error message). */
    suspend fun setSynced(id: Long, at: Long, error: String?) {
        context.epgStore.edit { prefs ->
            prefs[Keys.LIST] = write(parse(prefs[Keys.LIST]).map { if (it.id == id) it.copy(lastSyncAt = at, lastError = error) else it })
        }
    }

    /** Has the one-time "playlist EPG → EPG source" migration already run? */
    suspend fun isMigrated(): Boolean = context.epgStore.data.first()[Keys.MIGRATED] == 1L
    suspend fun markMigrated() { context.epgStore.edit { it[Keys.MIGRATED] = 1L } }

    // --- backup / restore (the raw JSON list rides inside the sources section) ---

    suspend fun exportJson(): String = context.epgStore.data.first()[Keys.LIST] ?: "[]"

    suspend fun importJson(raw: String?) {
        context.epgStore.edit { prefs ->
            val list = parse(raw)
            if (list.isEmpty()) prefs.remove(Keys.LIST) else prefs[Keys.LIST] = write(list)
            // Keep the id counter below the lowest restored id so new sources never collide.
            prefs[Keys.NEXT_ID] = (list.minOfOrNull { it.id } ?: 0L).coerceAtMost(0L) - 1
        }
    }

    private fun parse(raw: String?): List<EpgSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                EpgSource(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    url = o.getString("url"),
                    profileId = if (o.has("pid")) o.getLong("pid") else -1L, // legacy rows → -1 (unowned)
                    userAgent = o.optString("ua").takeIf { it.isNotEmpty() },
                    lastSyncAt = if (o.has("at")) o.getLong("at") else null,
                    lastError = o.optString("err").takeIf { it.isNotEmpty() },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<EpgSource>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("url", s.url).put("pid", s.profileId)
                    .apply {
                        s.userAgent?.let { put("ua", it) }
                        s.lastSyncAt?.let { put("at", it) }
                        s.lastError?.let { put("err", it) }
                    },
            )
        }
        return arr.toString()
    }
}