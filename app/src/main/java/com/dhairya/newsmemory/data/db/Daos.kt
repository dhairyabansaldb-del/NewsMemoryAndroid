package com.dhairya.newsmemory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RawNotificationDao {

    /** Insert-time exact-dup suppression: UNIQUE(content_hash, window_bucket) + IGNORE. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: RawNotification): Long          // -1 when a dupe was dropped

    @Query("SELECT * FROM raw_notifications WHERE posted_at >= :start AND posted_at < :end ORDER BY posted_at")
    suspend fun inWindow(start: Long, end: Long): List<RawNotification>

    @Query("UPDATE raw_notifications SET window_id = :windowId WHERE id IN (:ids)")
    suspend fun assignWindow(windowId: String, ids: List<Long>)

    @Query("SELECT COUNT(*) FROM raw_notifications")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM raw_notifications WHERE package_name = :pkg")
    suspend fun countForPackage(pkg: String): Int

    @Query("SELECT MAX(captured_at) FROM raw_notifications")
    suspend fun lastCapturedAt(): Long?

    @Query("SELECT MAX(captured_at) FROM raw_notifications")
    fun lastCapturedFlow(): Flow<Long?>

    @Query("SELECT * FROM raw_notifications ORDER BY posted_at DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<RawNotification>

    @Query("SELECT * FROM raw_notifications")
    suspend fun all(): List<RawNotification>
}

@Dao
interface DigestDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDigest(digest: Digest): Long          // IGNORE → idempotent per window_id

    @Insert
    suspend fun insertItem(item: DigestItem): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItemSources(sources: List<ItemSource>)

    @Query("SELECT * FROM digests WHERE id = :id")
    suspend fun digest(id: String): Digest?

    @Query("SELECT * FROM digests ORDER BY created_at DESC")
    fun allDigests(): Flow<List<Digest>>

    @Query("SELECT * FROM digests WHERE created_at >= :since ORDER BY created_at DESC")
    suspend fun digestsSince(since: Long): List<Digest>

    @Query("SELECT * FROM digest_items WHERE digest_id = :digestId ORDER BY position")
    suspend fun itemsFor(digestId: String): List<DigestItem>

    @Query("SELECT * FROM digest_items WHERE digest_id = :digestId ORDER BY position")
    fun itemsForFlow(digestId: String): Flow<List<DigestItem>>

    @Query(
        """SELECT rn.* FROM raw_notifications rn
           JOIN item_sources s ON s.raw_id = rn.id
           WHERE s.item_id = :itemId"""
    )
    suspend fun sourcesFor(itemId: Long): List<RawNotification>

    @Query("UPDATE digests SET opened_at = :openedAt WHERE id = :id AND opened_at IS NULL")
    suspend fun markOpened(id: String, openedAt: Long)

    @Query("SELECT * FROM digests ORDER BY created_at DESC LIMIT 1")
    suspend fun latestDigest(): Digest?

    @Query("SELECT * FROM digests")
    suspend fun all(): List<Digest>

    @Query("SELECT * FROM digest_items")
    suspend fun allItems(): List<DigestItem>

    @Query("SELECT * FROM item_sources")
    suspend fun allItemSources(): List<ItemSource>
}

@Dao
interface EntityDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntity(entity: TrackedEntity): Long   // -1 when normalized already exists

    @Query("SELECT * FROM entities WHERE normalized = :normalized")
    suspend fun byNormalized(normalized: String): TrackedEntity?

    @Query("UPDATE entities SET last_seen = :lastSeen WHERE id = :id")
    suspend fun touch(id: Long, lastSeen: Long)

    /** Upsert by normalized merge key; returns the entity id either way. */
    @Transaction
    suspend fun upsert(name: String, normalized: String, seenAt: Long): Long {
        val inserted = insertEntity(
            TrackedEntity(name = name, normalized = normalized, firstSeen = seenAt, lastSeen = seenAt)
        )
        if (inserted != -1L) return inserted
        val existing = byNormalized(normalized)!!
        touch(existing.id, seenAt)
        return existing.id
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(refs: List<ItemEntityCrossRef>)

    @Query(
        """SELECT e.* FROM entities e
           JOIN item_entities ie ON ie.entity_id = e.id
           WHERE ie.item_id = :itemId"""
    )
    suspend fun entitiesFor(itemId: Long): List<TrackedEntity>

    /** Recurrence core (EDD §7.1): distinct digest items mentioning an entity since a cutoff. */
    @Query(
        """SELECT COUNT(DISTINCT di.id) FROM digest_items di
           JOIN item_entities ie ON ie.item_id = di.id
           JOIN digests d ON d.id = di.digest_id
           WHERE ie.entity_id = :entityId AND d.window_start >= :since"""
    )
    suspend fun itemCountSince(entityId: Long, since: Long): Int

    @Query("SELECT * FROM entities")
    suspend fun all(): List<TrackedEntity>

    @Query("SELECT * FROM item_entities")
    suspend fun allRefs(): List<ItemEntityCrossRef>

    /** Digest items in a digest that have no linked entities (heuristic-mode backfill, EDD §7.3). */
    @Query(
        """SELECT di.* FROM digest_items di
           LEFT JOIN item_entities ie ON ie.item_id = di.id
           WHERE ie.item_id IS NULL"""
    )
    suspend fun itemsWithoutEntities(): List<DigestItem>
}
