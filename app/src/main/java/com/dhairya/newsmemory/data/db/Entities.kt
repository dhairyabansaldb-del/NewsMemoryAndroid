package com.dhairya.newsmemory.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Every captured notification, immutable. EDD §3.
 *
 * [windowBucket] is computed at insert time from the digest times configured at that
 * moment (the EDD's "derived col" made concrete — SQLite can't index a true derived
 * column through Room). UNIQUE(content_hash, window_bucket) kills exact dupes at insert.
 * [windowId] is assigned at digest time and stays null until the window's digest runs.
 */
@Entity(
    tableName = "raw_notifications",
    indices = [
        Index(value = ["content_hash", "window_bucket"], unique = true),
        Index(value = ["posted_at"]),
        Index(value = ["window_id"])
    ]
)
data class RawNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "package_name") val packageName: String,
    val title: String?,
    val body: String?,
    @androidx.room.ColumnInfo(name = "content_hash") val contentHash: String,
    @androidx.room.ColumnInfo(name = "posted_at") val postedAt: Long,
    @androidx.room.ColumnInfo(name = "captured_at") val capturedAt: Long,
    @androidx.room.ColumnInfo(name = "window_bucket") val windowBucket: String,
    @androidx.room.ColumnInfo(name = "window_id") val windowId: String? = null
)

/** One row per digest run, including empty ones. id = window_id "YYYY-MM-DD-{M|E|N}". */
@Entity(
    tableName = "digests",
    indices = [Index(value = ["created_at"])]
)
data class Digest(
    @PrimaryKey val id: String,
    val slot: String,                                                  // MORNING | EVENING | NIGHT
    @androidx.room.ColumnInfo(name = "window_start") val windowStart: Long,
    @androidx.room.ColumnInfo(name = "window_end") val windowEnd: Long,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long,
    @androidx.room.ColumnInfo(name = "item_count") val itemCount: Int,
    @androidx.room.ColumnInfo(name = "source_count") val sourceCount: Int,
    @androidx.room.ColumnInfo(name = "pipeline_mode") val pipelineMode: String,  // LLM | HEURISTIC
    @androidx.room.ColumnInfo(name = "opened_at") val openedAt: Long? = null     // null = archived silently
)

/** One row per merged story cluster inside a digest. */
@Entity(
    tableName = "digest_items",
    foreignKeys = [
        ForeignKey(
            entity = Digest::class,
            parentColumns = ["id"],
            childColumns = ["digest_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["digest_id"])]
)
data class DigestItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "digest_id") val digestId: String,
    @androidx.room.ColumnInfo(name = "topic_label") val topicLabel: String,
    val headline: String,
    @androidx.room.ColumnInfo(name = "source_count") val sourceCount: Int,
    val position: Int
)

/** Membership: which raw notifications fed which cluster. */
@Entity(
    tableName = "item_sources",
    primaryKeys = ["item_id", "raw_id"],
    foreignKeys = [
        ForeignKey(
            entity = DigestItem::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["raw_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["raw_id"])]
)
data class ItemSource(
    @androidx.room.ColumnInfo(name = "item_id") val itemId: Long,
    @androidx.room.ColumnInfo(name = "raw_id") val rawId: Long
)

/** Canonical entities/topics. v1 read path, v0 write path. */
@Entity(
    tableName = "entities",
    indices = [Index(value = ["normalized"], unique = true)]
)
data class TrackedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                                                  // canonical: "FII selling"
    val normalized: String,                                            // lowercase, trimmed; merge key
    @androidx.room.ColumnInfo(name = "first_seen") val firstSeen: Long,
    @androidx.room.ColumnInfo(name = "last_seen") val lastSeen: Long
)

@Entity(
    tableName = "item_entities",
    primaryKeys = ["item_id", "entity_id"],
    foreignKeys = [
        ForeignKey(
            entity = DigestItem::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackedEntity::class,
            parentColumns = ["id"],
            childColumns = ["entity_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["entity_id"])]
)
data class ItemEntityCrossRef(
    @androidx.room.ColumnInfo(name = "item_id") val itemId: Long,
    @androidx.room.ColumnInfo(name = "entity_id") val entityId: Long
)
