package com.dhairya.newsmemory.memory

import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.Digest
import com.dhairya.newsmemory.data.db.DigestDao
import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.data.db.EntityDao
import com.dhairya.newsmemory.data.db.EntityTally
import com.dhairya.newsmemory.data.db.ItemEntityCrossRef
import com.dhairya.newsmemory.data.db.ItemEntityName
import com.dhairya.newsmemory.data.db.ItemSource
import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.data.db.RawNotificationDao
import com.dhairya.newsmemory.testing.inMemoryArchive
import com.dhairya.newsmemory.util.Normalizer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Recurrence is counting, so these tests are about arithmetic at the threshold, not about
 * whether a story "matters". Every case seeds real rows in an in-memory archive and reads
 * them back through the real DAO — the counting lives in SQL, so a mocked DAO would test
 * nothing. The clock is fixed so "7 days ago" is the same instant on every run.
 */
@RunWith(RobolectricTestRunner::class)
class RecurrenceEngineTest {

    private lateinit var db: ArchiveDatabase
    private lateinit var digestDao: DigestDao
    private lateinit var entityDao: EntityDao
    private lateinit var rawDao: RawNotificationDao
    private lateinit var counting: CountingEntityDao
    private lateinit var engine: RecurrenceEngine

    @Before
    fun setup() {
        db = inMemoryArchive()
        digestDao = db.digestDao()
        entityDao = db.entityDao()
        rawDao = db.rawNotificationDao()
        // Seeding goes through the raw DAO; only the engine's reads land in `counting`.
        counting = CountingEntityDao(entityDao)
        engine = RecurrenceEngine(counting, clock = { FIXED_NOW })
    }

    @After
    fun teardown() {
        db.close()
    }

    // ---- rule 1: WEEK_COUNT -------------------------------------------------------------

    @Test
    fun `week count fires at exactly three items in seven days`() = runTest {
        val fii = entity("FII selling")
        story(daysAgo = 2, entities = listOf(fii))
        story(daysAgo = 1, entities = listOf(fii))
        val today = digest(daysAgo = 0)
        val itemId = item(today, entities = listOf(fii))

        val flags = flagsFor(today)

        assertEquals(1, flags.size)
        val flag = flags.getValue(itemId)
        assertEquals(FlagKind.WEEK_COUNT, flag.kind)
        assertEquals(3, flag.count)
        assertEquals("FII selling", flag.entityName)
        assertEquals("3rd this week", flag.chipLabel)
    }

    @Test
    fun `week count stays silent one item below the threshold`() = runTest {
        val fii = entity("FII selling")
        story(daysAgo = 1, entities = listOf(fii))
        val today = digest(daysAgo = 0)
        item(today, entities = listOf(fii))

        assertEquals(emptyMap<Long, RecurrenceFlag>(), flagsFor(today))
    }

    @Test
    fun `week count ignores items older than the seven day window`() = runTest {
        val fii = entity("FII selling")
        story(daysAgo = 10, entities = listOf(fii))
        story(daysAgo = 9, entities = listOf(fii))
        val today = digest(daysAgo = 0)
        item(today, entities = listOf(fii))

        // Three items exist, but only one landed inside the trailing week.
        assertEquals(emptyMap<Long, RecurrenceFlag>(), flagsFor(today))
    }

    // ---- rule 2: MULTI_SOURCE -----------------------------------------------------------

    @Test
    fun `multi source fires at exactly three publishers in one digest`() = runTest {
        val sarvam = entity("Sarvam")
        val today = digest(daysAgo = 0)
        val itemId = item(today, entities = listOf(sarvam))
        sources(itemId, "Mint", "The Hindu", "Inc42")

        val flags = flagsFor(today)

        assertEquals(1, flags.size)
        val flag = flags.getValue(itemId)
        assertEquals(FlagKind.MULTI_SOURCE, flag.kind)
        assertEquals(3, flag.count)
        assertEquals("3 sources", flag.chipLabel)
    }

    @Test
    fun `multi source stays silent one publisher below the threshold`() = runTest {
        val sarvam = entity("Sarvam")
        val today = digest(daysAgo = 0)
        val itemId = item(today, entities = listOf(sarvam))
        sources(itemId, "Mint", "The Hindu")

        assertEquals(emptyMap<Long, RecurrenceFlag>(), flagsFor(today))
    }

    // ---- rule 3: DAY_STREAK -------------------------------------------------------------

    @Test
    fun `day streak fires at exactly five distinct days in the month`() = runTest {
        val rupee = entity("Rupee")
        // Spread outside the trailing week so only the day rule can fire.
        listOf(20, 16, 12, 8).forEach { story(daysAgo = it, entities = listOf(rupee)) }
        val today = digest(daysAgo = 0)
        val itemId = item(today, entities = listOf(rupee))

        val flags = flagsFor(today)

        assertEquals(1, flags.size)
        val flag = flags.getValue(itemId)
        assertEquals(FlagKind.DAY_STREAK, flag.kind)
        assertEquals(5, flag.count)
        assertEquals("5 days running", flag.chipLabel)
    }

    @Test
    fun `day streak stays silent one day below the threshold`() = runTest {
        val rupee = entity("Rupee")
        listOf(16, 12, 8).forEach { story(daysAgo = it, entities = listOf(rupee)) }
        val today = digest(daysAgo = 0)
        item(today, entities = listOf(rupee))

        assertEquals(emptyMap<Long, RecurrenceFlag>(), flagsFor(today))
    }

    @Test
    fun `three slots on one calendar day count as a single active day`() = runTest {
        val rupee = entity("Rupee")
        // 4 distinct days spread over 12 slots — still one short of MIN_DAYS_PER_MONTH.
        listOf(16, 12, 8).forEach { daysAgo ->
            listOf("M", "E", "N").forEach { slot ->
                story(daysAgo = daysAgo, slot = slot, entities = listOf(rupee))
            }
        }
        val today = digest(daysAgo = 0)
        item(today, entities = listOf(rupee))
        item(digest(daysAgo = 0, slot = "M"), entities = listOf(rupee))

        assertEquals(emptyMap<Long, RecurrenceFlag>(), flagsFor(today))
    }

    // ---- no rule ------------------------------------------------------------------------

    @Test
    fun `an entity below every threshold produces no flag`() = runTest {
        val sarvam = entity("Sarvam")
        val today = digest(daysAgo = 0)
        val itemId = item(today, entities = listOf(sarvam))
        sources(itemId, "Mint")

        assertEquals(emptyMap<Long, RecurrenceFlag>(), flagsFor(today))
    }

    @Test
    fun `an item with no entities is never flagged`() = runTest {
        val today = digest(daysAgo = 0)
        item(today, entities = emptyList())

        assertEquals(emptyMap<Long, RecurrenceFlag>(), flagsFor(today))
    }

    // ---- one flag per item, and the tie-break -------------------------------------------

    @Test
    fun `an item linking two flagged entities gets one flag and the bigger count wins`() = runTest {
        val quiet = entity("Quiet entity")     // week count 3
        val loud = entity("Loud entity")       // week count 5
        val today = digest(daysAgo = 0)
        val itemId = item(today, entities = listOf(quiet, loud))
        story(daysAgo = 1, entities = listOf(quiet, loud))
        story(daysAgo = 2, entities = listOf(quiet, loud))
        story(daysAgo = 3, entities = listOf(loud))
        story(daysAgo = 4, entities = listOf(loud))

        val flags = flagsFor(today)

        assertEquals(1, flags.size)
        val flag = flags.getValue(itemId)
        assertEquals(loud, flag.entityId)
        assertEquals("Loud entity", flag.entityName)
        assertEquals(5, flag.count)
    }

    @Test
    fun `week count outranks multi source when the counts tie`() = runTest {
        val fii = entity("FII selling")
        story(daysAgo = 2, entities = listOf(fii))
        story(daysAgo = 1, entities = listOf(fii))
        val today = digest(daysAgo = 0)
        val itemId = item(today, entities = listOf(fii))
        sources(itemId, "Mint", "The Hindu", "Inc42")   // both rules land on 3

        val flag = flagsFor(today).getValue(itemId)

        assertEquals(FlagKind.WEEK_COUNT, flag.kind)
        assertEquals(3, flag.count)
    }

    @Test
    fun `multi source outranks day streak when the counts tie`() = runTest {
        val rupee = entity("Rupee")
        // 5 active days, all outside the week, so WEEK_COUNT can't fire and both other
        // rules land on 5.
        listOf(20, 16, 12, 8).forEach { story(daysAgo = it, entities = listOf(rupee)) }
        val today = digest(daysAgo = 0)
        val itemId = item(today, entities = listOf(rupee))
        sources(itemId, "Mint", "The Hindu", "Inc42", "Reuters", "PTI")

        val flag = flagsFor(today).getValue(itemId)

        assertEquals(FlagKind.MULTI_SOURCE, flag.kind)
        assertEquals(5, flag.count)
    }

    // ---- the cap ------------------------------------------------------------------------

    @Test
    fun `flags are capped per digest and the cap keeps the highest counts`() = runTest {
        val today = digest(daysAgo = 0)
        val filler = digest(daysAgo = 1)
        // Seven entities with week counts 3..9, one item each in today's digest.
        (3..9).forEach { weekCount ->
            val eid = entity("Entity $weekCount")
            item(today, entities = listOf(eid))
            repeat(weekCount - 1) { item(filler, entities = listOf(eid)) }
        }

        val flags = flagsFor(today)

        assertEquals(RecurrenceEngine.MAX_FLAGS_PER_DIGEST, flags.size)
        assertEquals(listOf(5, 6, 7, 8, 9), flags.values.map { it.count }.sorted())
        assertTrue(flags.values.all { it.kind == FlagKind.WEEK_COUNT })
    }

    // ---- degenerate input ---------------------------------------------------------------

    @Test
    fun `an empty digest yields an empty map without touching the database`() = runTest {
        val flags = engine.flagsForDigest("2026-08-10-E", emptyList())

        assertEquals(emptyMap<Long, RecurrenceFlag>(), flags)
        assertEquals(0, counting.total)   // never build `WHERE item_id IN ()`
    }

    @Test
    fun `an entity spanning several items in one digest is counted once`() = runTest {
        val fii = entity("FII selling")
        story(daysAgo = 2, entities = listOf(fii))
        story(daysAgo = 1, entities = listOf(fii))
        val today = digest(daysAgo = 0)
        item(today, entities = listOf(fii))
        item(today, entities = listOf(fii))

        val flags = flagsFor(today)

        assertEquals(2, flags.size)                       // both cards carry the flag
        assertEquals(1, counting.count("entitiesForItems"))
        assertEquals(1, counting.count("itemCountSince"))
        assertEquals(1, counting.count("publisherCountInDigest"))
        assertEquals(1, counting.count("activeDaysSince"))
    }

    // ---- the inclusive-count contract ---------------------------------------------------

    @Test
    fun `counts include the digest being read so the fourth appearance reads fourth`() = runTest {
        val fii = entity("FII selling")
        listOf(3, 2, 1).forEach { story(daysAgo = it, entities = listOf(fii)) }
        val today = digest(daysAgo = 0)
        val itemId = item(today, entities = listOf(fii))

        val flag = flagsFor(today).getValue(itemId)

        assertEquals(4, flag.count)
        assertEquals("4th this week", flag.chipLabel)
        assertEquals("4th story on FII selling this week", flag.pushLabel)
    }

    // ---- building() ---------------------------------------------------------------------

    @Test
    fun `building maps calendar dates onto day offsets back from today`() = runTest {
        val fii = entity("FII selling")
        listOf(10, 3, 0).forEach { story(daysAgo = it, entities = listOf(fii)) }

        val signals = engine.building(limit = 5)

        assertEquals(1, signals.size)
        val signal = signals.single()
        assertEquals(fii, signal.entityId)
        assertEquals("FII selling", signal.entityName)
        assertEquals(3, signal.itemCount)
        assertEquals(3, signal.dayCount)
        assertEquals(setOf(0, 3, 10), signal.activeDayOffsets)
    }

    @Test
    fun `building drops days that fall outside the twenty-eight day matrix`() = runTest {
        val fii = entity("FII selling")
        story(daysAgo = 29, entities = listOf(fii))   // inside the 30-day tally, off the matrix
        story(daysAgo = 0, entities = listOf(fii))

        val signal = engine.building(limit = 5).single()

        assertEquals(setOf(0), signal.activeDayOffsets)
        assertEquals(2, signal.dayCount)              // the tally window is wider than the matrix
    }

    @Test
    fun `building skips an entity with nothing inside the matrix`() = runTest {
        val fii = entity("FII selling")
        story(daysAgo = 29, entities = listOf(fii))

        assertEquals(emptyList<BuildingSignal>(), engine.building(limit = 5))
    }

    @Test
    fun `building defaults to the single most active entity`() = runTest {
        val loud = entity("Loud entity")
        val quiet = entity("Quiet entity")
        listOf(2, 1, 0).forEach { story(daysAgo = it, entities = listOf(loud)) }
        story(daysAgo = 0, slot = "M", entities = listOf(quiet))

        val signals = engine.building()

        assertEquals(1, signals.size)
        assertEquals(loud, signals.single().entityId)
    }

    @Test
    fun `building returns nothing on an empty archive`() = runTest {
        assertEquals(emptyList<BuildingSignal>(), engine.building(limit = 5))
    }

    // ---- label rendering (pure) ---------------------------------------------------------

    @Test
    fun `ordinal renders english suffixes including the teens`() {
        assertEquals("1st", RecurrenceFlag.ordinal(1))
        assertEquals("2nd", RecurrenceFlag.ordinal(2))
        assertEquals("3rd", RecurrenceFlag.ordinal(3))
        assertEquals("4th", RecurrenceFlag.ordinal(4))
        assertEquals("11th", RecurrenceFlag.ordinal(11))
        assertEquals("12th", RecurrenceFlag.ordinal(12))
        assertEquals("13th", RecurrenceFlag.ordinal(13))
        assertEquals("21st", RecurrenceFlag.ordinal(21))
        assertEquals("101st", RecurrenceFlag.ordinal(101))
        assertEquals("111th", RecurrenceFlag.ordinal(111))
    }

    @Test
    fun `chip labels report a count for every rule`() {
        assertEquals("4th this week", flag(FlagKind.WEEK_COUNT, 4).chipLabel)
        assertEquals("3 sources", flag(FlagKind.MULTI_SOURCE, 3).chipLabel)
        assertEquals("5 days running", flag(FlagKind.DAY_STREAK, 5).chipLabel)
    }

    @Test
    fun `push labels name the entity for every rule`() {
        assertEquals("4th story on FII selling this week", flag(FlagKind.WEEK_COUNT, 4).pushLabel)
        assertEquals("FII selling across 3 sources", flag(FlagKind.MULTI_SOURCE, 3).pushLabel)
        assertEquals("FII selling — 5 days running", flag(FlagKind.DAY_STREAK, 5).pushLabel)
    }

    // ---- fixtures -----------------------------------------------------------------------

    private var storyCount = 0

    private fun flag(kind: FlagKind, count: Int) =
        RecurrenceFlag(entityId = 1, entityName = "FII selling", kind = kind, count = count)

    private suspend fun flagsFor(digestId: String) =
        engine.flagsForDigest(digestId, digestDao.itemsFor(digestId))

    private suspend fun entity(name: String): Long =
        entityDao.upsert(name, name.lowercase(), seenAt = FIXED_NOW)

    /**
     * A digest [daysAgo] before the fixed now. The id carries the calendar date (that's what
     * `activeDaysSince` reads) while `window_start` carries the millis (that's what the
     * cutoffs read) — both derived from the same offset so they can't disagree.
     */
    private suspend fun digest(daysAgo: Int, slot: String = "E"): String {
        val id = "${TODAY.minusDays(daysAgo.toLong())}-$slot"
        val start = FIXED_NOW - daysAgo * DAY_MS
        digestDao.insertDigest(
            Digest(
                id = id, slot = "EVENING", windowStart = start, windowEnd = start + 1,
                createdAt = start + 1, itemCount = 1, sourceCount = 1, pipelineMode = "LLM"
            )
        )
        return id
    }

    private suspend fun item(digestId: String, entities: List<Long>): Long {
        val itemId = digestDao.insertItem(
            DigestItem(
                digestId = digestId, topicLabel = "Markets",
                headline = "Story ${++storyCount}", sourceCount = 1, position = storyCount
            )
        )
        if (entities.isNotEmpty()) {
            entityDao.link(entities.map { ItemEntityCrossRef(itemId, it) })
        }
        return itemId
    }

    /** One digest holding one item — the common "this entity appeared on day N" fixture. */
    private suspend fun story(daysAgo: Int, slot: String = "E", entities: List<Long>): Long =
        item(digest(daysAgo, slot), entities)

    /** Attach raw notifications from distinct publishers, which is what MULTI_SOURCE counts. */
    private suspend fun sources(itemId: Long, vararg publishers: String) {
        val refs = publishers.map { publisher ->
            val title = "$publisher on item $itemId"
            val rawId = rawDao.insert(
                RawNotification(
                    packageName = "com.example.news", publisher = publisher,
                    title = title, body = null,
                    contentHash = Normalizer.contentHash(title, null),
                    postedAt = FIXED_NOW, capturedAt = FIXED_NOW,
                    windowBucket = "bucket-$itemId"
                )
            )
            ItemSource(itemId, rawId)
        }
        digestDao.insertItemSources(refs)
    }

    /**
     * Wraps the real DAO so tests can assert the engine's query volume (the per-entity cache)
     * without mocking away the SQL that does the actual counting.
     */
    private class CountingEntityDao(private val delegate: EntityDao) : EntityDao by delegate {

        private val calls = mutableMapOf<String, Int>()
        val total: Int get() = calls.values.sum()

        fun count(name: String): Int = calls[name] ?: 0

        private fun record(name: String) {
            calls[name] = (calls[name] ?: 0) + 1
        }

        override suspend fun entitiesForItems(itemIds: List<Long>): List<ItemEntityName> {
            record("entitiesForItems")
            return delegate.entitiesForItems(itemIds)
        }

        override suspend fun itemCountSince(entityId: Long, since: Long): Int {
            record("itemCountSince")
            return delegate.itemCountSince(entityId, since)
        }

        override suspend fun publisherCountInDigest(entityId: Long, digestId: String): Int {
            record("publisherCountInDigest")
            return delegate.publisherCountInDigest(entityId, digestId)
        }

        override suspend fun activeDaysSince(entityId: Long, since: Long): List<String> {
            record("activeDaysSince")
            return delegate.activeDaysSince(entityId, since)
        }

        override suspend fun topEntitiesSince(since: Long, limit: Int): List<EntityTally> {
            record("topEntitiesSince")
            return delegate.topEntitiesSince(since, limit)
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Fixed clock; every fixture date is derived from it, so the host zone is irrelevant. */
        val FIXED_NOW: Long = Instant.parse("2026-08-10T09:30:00Z").toEpochMilli()
        val TODAY: LocalDate =
            Instant.ofEpochMilli(FIXED_NOW).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
