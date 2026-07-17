package com.dhairya.newsmemory.pipeline

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dhairya.newsmemory.data.NotificationRepository
import com.dhairya.newsmemory.data.SettingsStore
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class DigestPipelineTest {

    private lateinit var db: ArchiveDatabase
    private lateinit var repo: NotificationRepository
    private lateinit var pipeline: DigestPipeline
    private val zone = ZoneId.of("Asia/Kolkata")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArchiveDatabase::class.java)
            .allowMainThreadQueries().build()
        val settings = SettingsStore(context)
        repo = NotificationRepository(db.rawNotificationDao(), settings) { zone }
        pipeline = DigestPipeline(db, settings, zone = { zone })   // no notifier in tests
    }

    @After
    fun teardown() = db.close()

    private fun millisAt(h: Int, m: Int): Long =
        LocalDateTime.of(2026, 6, 10, h, m).atZone(zone).toInstant().toEpochMilli()

    private suspend fun seedEveningWindow() {
        // Three sources, one story (should merge) + two distinct stories
        repo.insertRaw("com.news.a", "Sensex falls 800 points as FII selling continues", null, millisAt(10, 0))
        repo.insertRaw("com.news.b", "Sensex tumbles 800 points amid relentless FII selling", null, millisAt(11, 0))
        repo.insertRaw("com.news.c", "FII selling drags Sensex down 800 points", null, millisAt(12, 0))
        repo.insertRaw("com.news.a", "ISRO announces Gaganyaan launch date", null, millisAt(13, 0))
        repo.insertRaw("com.news.b", "Monsoon arrives early in Kerala this year", null, millisAt(14, 0))
    }

    @Test
    fun `end-to-end heuristic digest for a window`() = runTest {
        seedEveningWindow()

        val outcome = pipeline.runForWindow("2026-06-10-E")
        assertTrue(outcome is DigestPipeline.Outcome.Created)

        val digest = db.digestDao().digest("2026-06-10-E")!!
        assertEquals(3, digest.itemCount)                       // merged story + 2 singles
        assertEquals(3, digest.sourceCount)                     // a, b, c
        assertEquals("HEURISTIC", digest.pipelineMode)
        assertEquals("EVENING", digest.slot)

        val items = db.digestDao().itemsFor("2026-06-10-E")
        assertEquals(3, items.size)
        // Multi-source story first (EDD §5.2 ordering)
        assertEquals(3, items[0].sourceCount)
        assertEquals(3, db.digestDao().sourcesFor(items[0].id).size)

        // Raw rows got their window assigned
        val raws = db.rawNotificationDao().all()
        assertTrue(raws.all { it.windowId == "2026-06-10-E" })
    }

    @Test
    fun `pipeline is idempotent per window id`() = runTest {
        seedEveningWindow()

        val first = pipeline.runForWindow("2026-06-10-E")
        val second = pipeline.runForWindow("2026-06-10-E")

        assertTrue(first is DigestPipeline.Outcome.Created)
        assertTrue(second is DigestPipeline.Outcome.AlreadyExists)
        assertEquals(3, db.digestDao().itemsFor("2026-06-10-E").size)   // not duplicated
    }

    @Test
    fun `empty window writes a zero-item digest row`() = runTest {
        val outcome = pipeline.runForWindow("2026-06-10-E")
        assertTrue(outcome is DigestPipeline.Outcome.Created)

        val digest = db.digestDao().digest("2026-06-10-E")
        assertNotNull(digest)
        assertEquals(0, digest!!.itemCount)
        assertEquals(0, db.digestDao().itemsFor("2026-06-10-E").size)
    }

    @Test
    fun `overnight morning window picks up late-night captures`() = runTest {
        repo.insertRaw("com.news.a", "Overnight market crash in US trading", null, millisAt(23, 0))

        val outcome = pipeline.runForWindow("2026-06-11-M")
        assertTrue(outcome is DigestPipeline.Outcome.Created)
        assertEquals(1, db.digestDao().digest("2026-06-11-M")!!.itemCount)
    }

    @Test
    fun `entities from clusters are written and linked`() = runTest {
        seedEveningWindow()
        // Simulate the LLM path (Phase 5) providing entities through the cluster engine
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context)
        val llmLike = DigestPipeline(db, settings, clusterEngine = { stories ->
            ClusterResult(
                listOf(
                    TopicCluster("Indian Markets", stories, entities = listOf("FII selling", "Sensex"))
                ),
                ClusterResult.MODE_LLM
            )
        }, zone = { zone })

        llmLike.runForWindow("2026-06-10-E")

        val digest = db.digestDao().digest("2026-06-10-E")!!
        assertEquals("LLM", digest.pipelineMode)
        val items = db.digestDao().itemsFor("2026-06-10-E")
        val entities = db.entityDao().entitiesFor(items[0].id)
        assertEquals(setOf("fii selling", "sensex"), entities.map { it.normalized }.toSet())
    }

    @Test
    fun `synthesized cluster headline is used over the raw representative`() = runTest {
        seedEveningWindow()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context)
        val llmLike = DigestPipeline(db, settings, clusterEngine = { stories ->
            ClusterResult(
                listOf(TopicCluster("Markets", stories, headline = "Sensex slides as FII selling continues")),
                ClusterResult.MODE_LLM
            )
        }, zone = { zone })

        llmLike.runForWindow("2026-06-10-E")

        val items = db.digestDao().itemsFor("2026-06-10-E")
        assertEquals("Sensex slides as FII selling continues", items[0].headline)
    }

    @Test
    fun `blank synthesized headline falls back to the representative story`() = runTest {
        seedEveningWindow()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsStore(context)
        val llmLike = DigestPipeline(db, settings, clusterEngine = { stories ->
            ClusterResult(
                listOf(TopicCluster("Markets", stories, headline = "   ")),   // blank, not null
                ClusterResult.MODE_LLM
            )
        }, zone = { zone })

        llmLike.runForWindow("2026-06-10-E")

        val items = db.digestDao().itemsFor("2026-06-10-E")
        val rawTitles = db.rawNotificationDao().all().mapNotNull { it.title }.toSet()
        assertTrue(items[0].headline in rawTitles)
    }
}
