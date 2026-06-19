package com.dhairya.newsmemory.data

import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.Digest
import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.data.db.ItemEntityCrossRef
import com.dhairya.newsmemory.data.db.ItemSource
import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.data.db.TrackedEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** One self-contained snapshot of every table — the manual data-egress hatch (EDD §3). */
@Serializable
data class ArchiveExport(
    val exportVersion: Int,
    val schemaVersion: Int,
    val exportedAt: Long,
    val rawNotifications: List<RawNotification>,
    val digests: List<Digest>,
    val digestItems: List<DigestItem>,
    val itemSources: List<ItemSource>,
    val entities: List<TrackedEntity>,
    val itemEntities: List<ItemEntityCrossRef>
)

/**
 * Serializes the whole archive to a single JSON document (EDD §3 "Export"). Keeps the
 * no-backend decision honest: insurance against phone loss, written to a user-chosen file
 * via the Storage Access Framework. All six tables, ids preserved so the export is
 * relationally complete.
 */
object ArchiveExporter {

    /** Bump if the export *shape* changes; independent of the Room schema version. */
    const val EXPORT_VERSION = 1

    /** Mirrors the Room @Database(version) — kept in sync by hand (there are only two). */
    const val SCHEMA_VERSION = 2

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun toJson(db: ArchiveDatabase): String {
        val export = ArchiveExport(
            exportVersion = EXPORT_VERSION,
            schemaVersion = SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            rawNotifications = db.rawNotificationDao().all(),
            digests = db.digestDao().all(),
            digestItems = db.digestDao().allItems(),
            itemSources = db.digestDao().allItemSources(),
            entities = db.entityDao().all(),
            itemEntities = db.entityDao().allRefs()
        )
        return json.encodeToString(export)
    }

    fun suggestedFileName(today: LocalDate = LocalDate.now()): String =
        "news-memory-archive-$today.json"
}
