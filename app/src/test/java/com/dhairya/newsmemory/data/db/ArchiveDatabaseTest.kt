package com.dhairya.newsmemory.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dhairya.newsmemory.util.Normalizer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArchiveDatabaseTest {

    private lateinit var db: ArchiveDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArchiveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun raw(
        title: String,
        body: String? = null,
        pkg: String = "com.example.news",
        bucket: String = "2026-06-10-E",
        postedAt: Long = 1_000L
    ) = RawNotification(
        packageName = pkg,
        title = title,
        body = body,
        contentHash = Normalizer.contentHash(title, body),
        postedAt = postedAt,
        capturedAt = postedAt,
        windowBucket = bucket
    )

    @Test
    fun `exact duplicate in same window is dropped at insert`() = runTest {
        val dao = db.rawNotificationDao()
        val first = dao.insert(raw("Sensex falls 800 points"))
        val dupe = dao.insert(raw("SENSEX falls 800 points!!"))   // same hash post-normalize
        assertTrue(first > 0)
        assertEquals(-1L, dupe)
        assertEquals(1, dao.inWindow(0, 2_000).size)
    }

    @Test
    fun `same story in a different window is kept`() = runTest {
        val dao = db.rawNotificationDao()
        dao.insert(raw("Sensex falls 800 points", bucket = "2026-06-10-E"))
        val second = dao.insert(raw("Sensex falls 800 points", bucket = "2026-06-10-N"))
        assertTrue(second > 0)
    }

    @Test
    fun `digest insert is idempotent per window id`() = runTest {
        val dao = db.digestDao()
        val digest = Digest(
            id = "2026-06-10-E", slot = "EVENING", windowStart = 0, windowEnd = 100,
            createdAt = 100, itemCount = 3, sourceCount = 2, pipelineMode = "LLM"
        )
        assertTrue(dao.insertDigest(digest) > 0)
        assertEquals(-1L, dao.insertDigest(digest.copy(itemCount = 99)))
        assertEquals(3, dao.digest("2026-06-10-E")!!.itemCount)
    }

    @Test
    fun `digest items round-trip with sources`() = runTest {
        val rawDao = db.rawNotificationDao()
        val digestDao = db.digestDao()
        val rawId1 = rawDao.insert(raw("Sensex falls 800 points", pkg = "com.app.a"))
        val rawId2 = rawDao.insert(raw("Markets tumble as FIIs sell", pkg = "com.app.b"))
        digestDao.insertDigest(
            Digest(
                id = "2026-06-10-E", slot = "EVENING", windowStart = 0, windowEnd = 100,
                createdAt = 100, itemCount = 1, sourceCount = 2, pipelineMode = "LLM"
            )
        )
        val itemId = digestDao.insertItem(
            DigestItem(
                digestId = "2026-06-10-E", topicLabel = "Indian Markets",
                headline = "Sensex falls 800 points", sourceCount = 2, position = 0
            )
        )
        digestDao.insertItemSources(listOf(ItemSource(itemId, rawId1), ItemSource(itemId, rawId2)))

        val items = digestDao.itemsFor("2026-06-10-E")
        assertEquals(1, items.size)
        assertEquals(2, digestDao.sourcesFor(itemId).size)
    }

    @Test
    fun `entity upsert merges on normalized key and touches last_seen`() = runTest {
        val dao = db.entityDao()
        val id1 = dao.upsert("FII selling", "fii selling", seenAt = 100)
        val id2 = dao.upsert("FII Selling", "fii selling", seenAt = 200)
        assertEquals(id1, id2)
        val entity = dao.byNormalized("fii selling")!!
        assertEquals(100, entity.firstSeen)
        assertEquals(200, entity.lastSeen)
        assertEquals("FII selling", entity.name)   // first writer wins the canonical name
    }

    @Test
    fun `recurrence count joins through item_entities and respects cutoff`() = runTest {
        val digestDao = db.digestDao()
        val entityDao = db.entityDao()
        val eid = entityDao.upsert("FII selling", "fii selling", seenAt = 0)

        suspend fun digestWithItem(windowId: String, windowStart: Long): Long {
            digestDao.insertDigest(
                Digest(
                    id = windowId, slot = "EVENING", windowStart = windowStart,
                    windowEnd = windowStart + 1, createdAt = windowStart + 1,
                    itemCount = 1, sourceCount = 1, pipelineMode = "LLM"
                )
            )
            val itemId = digestDao.insertItem(
                DigestItem(
                    digestId = windowId, topicLabel = "Markets",
                    headline = "FII selling continues", sourceCount = 1, position = 0
                )
            )
            entityDao.link(listOf(ItemEntityCrossRef(itemId, eid)))
            return itemId
        }

        digestWithItem("2026-06-08-E", windowStart = 800)
        digestWithItem("2026-06-09-E", windowStart = 900)
        digestWithItem("2026-06-10-E", windowStart = 1000)

        assertEquals(3, entityDao.itemCountSince(eid, since = 0))
        assertEquals(2, entityDao.itemCountSince(eid, since = 900))   // cutoff excludes the oldest
    }

    @Test
    fun `mark opened only sets first open`() = runTest {
        val dao = db.digestDao()
        dao.insertDigest(
            Digest(
                id = "2026-06-10-M", slot = "MORNING", windowStart = 0, windowEnd = 1,
                createdAt = 1, itemCount = 0, sourceCount = 0, pipelineMode = "HEURISTIC"
            )
        )
        assertNull(dao.digest("2026-06-10-M")!!.openedAt)
        dao.markOpened("2026-06-10-M", openedAt = 500)
        dao.markOpened("2026-06-10-M", openedAt = 999)   // second open must not overwrite
        assertEquals(500L, dao.digest("2026-06-10-M")!!.openedAt)
    }

    @Test
    fun `items without entities surfaces heuristic digests for backfill`() = runTest {
        val digestDao = db.digestDao()
        val entityDao = db.entityDao()
        digestDao.insertDigest(
            Digest(
                id = "2026-06-10-N", slot = "NIGHT", windowStart = 0, windowEnd = 1,
                createdAt = 1, itemCount = 2, sourceCount = 1, pipelineMode = "HEURISTIC"
            )
        )
        val bare = digestDao.insertItem(
            DigestItem(digestId = "2026-06-10-N", topicLabel = "Tech", headline = "A", sourceCount = 1, position = 0)
        )
        val linked = digestDao.insertItem(
            DigestItem(digestId = "2026-06-10-N", topicLabel = "Tech", headline = "B", sourceCount = 1, position = 1)
        )
        val eid = entityDao.upsert("Sarvam", "sarvam", seenAt = 1)
        entityDao.link(listOf(ItemEntityCrossRef(linked, eid)))

        val orphans = entityDao.itemsWithoutEntities()
        assertEquals(1, orphans.size)
        assertEquals(bare, orphans[0].id)
        assertNotEquals(linked, orphans[0].id)
    }
}
