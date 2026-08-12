package com.dhairya.newsmemory.data.db

import androidx.room.ColumnInfo
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

    /** All items for one day's digests; window_id is "YYYY-MM-DD-{M|E|N}". */
    @Query("SELECT * FROM digest_items WHERE digest_id LIKE :datePrefix || '%' ORDER BY position")
    fun itemsForDate(datePrefix: String): Flow<List<DigestItem>>

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

/** One (item, entity) pairing, fetched for a whole digest in a single query. */
data class ItemEntityName(
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "name") val name: String
)

/** An entity ranked by how much it recurred over a trailing window ("What's building"). */
data class EntityTally(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    @ColumnInfo(name = "day_count") val dayCount: Int
)

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

    /**
     * Every entity linked to any of these items, in one query. Recurrence needs the whole
     * digest's pairings at once; N per-item round-trips would be wasteful on the read path.
     */
    @Query(
        """SELECT ie.item_id AS item_id, e.id AS entity_id, e.name AS name
           FROM entities e
           JOIN item_entities ie ON ie.entity_id = e.id
           WHERE ie.item_id IN (:itemIds)"""
    )
    suspend fun entitiesForItems(itemIds: List<Long>): List<ItemEntityName>

    /**
     * Distinct calendar days on which an entity appeared since a cutoff. digest_id is
     * "YYYY-MM-DD-{M|E|N}", so the date is its first 10 chars — three slots on one day
     * count once. Date arithmetic stays in Kotlin; SQL only reports the days.
     */
    @Query(
        """SELECT DISTINCT substr(di.digest_id, 1, 10) FROM digest_items di
           JOIN item_entities ie ON ie.item_id = di.id
           JOIN digests d ON d.id = di.digest_id
           WHERE ie.entity_id = :entityId AND d.window_start >= :since"""
    )
    suspend fun activeDaysSince(entityId: Long, since: Long): List<String>

    /**
     * Distinct publishers carrying an entity within one digest. Keys on publisher, not
     * package_name, so two outlets inside one aggregator app count separately (§6).
     */
    @Query(
        """SELECT COUNT(DISTINCT COALESCE(rn.publisher, rn.package_name))
           FROM raw_notifications rn
           JOIN item_sources s ON s.raw_id = rn.id
           JOIN digest_items di ON di.id = s.item_id
           JOIN item_entities ie ON ie.item_id = di.id
           WHERE ie.entity_id = :entityId AND di.digest_id = :digestId"""
    )
    suspend fun publisherCountInDigest(entityId: Long, digestId: String): Int

    /** Top recurring entities over a trailing window — the "What's building" data source. */
    @Query(
        """SELECT e.id AS entity_id, e.name AS name,
                  COUNT(DISTINCT di.id) AS item_count,
                  COUNT(DISTINCT substr(di.digest_id, 1, 10)) AS day_count
           FROM entities e
           JOIN item_entities ie ON ie.entity_id = e.id
           JOIN digest_items di ON di.id = ie.item_id
           JOIN digests d ON d.id = di.digest_id
           WHERE d.window_start >= :since
           GROUP BY e.id
           ORDER BY item_count DESC, day_count DESC
           LIMIT :limit"""
    )
    suspend fun topEntitiesSince(since: Long, limit: Int): List<EntityTally>

    @Query("SELECT * FROM entities")
    suspend fun all(): List<TrackedEntity>

    @Query("SELECT * FROM item_entities")
    suspend fun allRefs(): List<ItemEntityCrossRef>

    /**
     * Digest items anywhere in the archive with no linked entities (heuristic-mode backfill,
     * EDD §7.3). Bounded: backfill drains in batches so it never fires an unbounded number of
     * extraction calls in one pass. Oldest first, so history fills in chronologically.
     */
    @Query(
        """SELECT di.* FROM digest_items di
           LEFT JOIN item_entities ie ON ie.item_id = di.id
           WHERE ie.item_id IS NULL
           ORDER BY di.id
           LIMIT :limit"""
    )
    suspend fun itemsWithoutEntities(limit: Int): List<DigestItem>

    /**
     * The next batch to attempt extraction on: unlinked items above the watermark.
     *
     * `id > :afterId` is what makes backfill terminate. Filtering on "has no entities" alone
     * would re-offer every item that legitimately extracted to zero entities — a correct and
     * common outcome — so the job would never finish.
     */
    @Query(
        """SELECT di.* FROM digest_items di
           LEFT JOIN item_entities ie ON ie.item_id = di.id
           WHERE ie.item_id IS NULL AND di.id > :afterId
           ORDER BY di.id
           LIMIT :limit"""
    )
    suspend fun itemsWithoutEntitiesAfter(afterId: Long, limit: Int): List<DigestItem>

    /** Items still unattempted — drives the Settings progress copy, and stops at 0. */
    @Query(
        """SELECT COUNT(*) FROM digest_items di
           LEFT JOIN item_entities ie ON ie.item_id = di.id
           WHERE ie.item_id IS NULL AND di.id > :afterId"""
    )
    suspend fun itemsRemainingAfter(afterId: Long): Int

    /** How much of the archive still needs backfilling — drives the Settings progress copy. */
    @Query(
        """SELECT COUNT(*) FROM digest_items di
           LEFT JOIN item_entities ie ON ie.item_id = di.id
           WHERE ie.item_id IS NULL"""
    )
    suspend fun itemsWithoutEntitiesCount(): Int
}
