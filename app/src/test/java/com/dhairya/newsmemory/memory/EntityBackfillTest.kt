package com.dhairya.newsmemory.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dhairya.newsmemory.data.SettingsStore
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.Digest
import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.testing.inMemoryArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The backfill's job is bookkeeping, and every case here is about the watermark, because the
 * watermark is the only thing standing between "drains the archive once" and either
 * re-extracting the same items forever (quota) or skipping them silently (a hole in the data
 * v1 counts over). Extraction itself is a stub function — quality lives in
 * tools/eval_entities.py, and the wire handling in EntityExtractorTest.
 *
 * The DB is a real in-memory archive and the settings store is the real DataStore: the
 * "not offered again" contract is a SQL predicate plus a persisted long, so faking either
 * would test nothing.
 */
@RunWith(RobolectricTestRunner::class)
class EntityBackfillTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsStore(context)
    private lateinit var db: ArchiveDatabase

    @Before
    fun setup() = runBlocking {
        db = inMemoryArchiveWithDigest()
        // The DataStore delegate outlives a single test, so start from a known watermark.
        settings.resetBackfillWatermark()
    }

    @After
    fun teardown() {
        db.close()
    }

    // ---- the no-key path -------------------------------------------------------------------

    @Test
    fun `with no extractor the control is unavailable and runBatch is a harmless no-op`() = runTest {
        val ids = seedItems("Sensex falls", "Apple ships MacBook")
        val backfill = EntityBackfill(db, settings, extract = null)

        assertFalse(backfill.available)
        val outcome = backfill.runBatch()

        assertEquals(EntityBackfill.BatchOutcome(0, 0, 2), outcome)
        assertEquals(0L, settings.backfillWatermarkSnapshot())
        assertTrue(db.entityDao().allRefs().isEmpty())
        assertEquals(ids, unattempted())
    }

    // ---- draining under a budget -----------------------------------------------------------

    @Test
    fun `drain keeps going until the queue empties`() = runTest {
        val ids = seedItems("one", "two", "three", "four", "five")
        val sent = mutableListOf<Long>()
        val backfill = backfill { items ->
            sent += items.map { it.id }
            items.associate { it.id to listOf("E${it.id}") }
        }

        val outcome = backfill.runDrain(now = { 0L }, pause = {})

        assertEquals(ids, sent)                 // every item, exactly once
        assertEquals(5, outcome.attempted)
        assertEquals(0, outcome.remaining)
    }

    @Test
    fun `drain stops when the time budget is spent and leaves the rest for the next tick`() = runTest {
        seedItems("one", "two", "three", "four", "five", "six")
        var clock = 0L
        val backfill = backfill { items -> items.associate { it.id to listOf("E${it.id}") } }

        // deadline = 30. After batch 1 the clock is 0 (pause not yet taken) so 0+20 < 30 and it
        // sleeps to 20; after batch 2, 20+20 >= 30, so it stops with two batches done.
        val outcome = backfill.runDrain(
            limit = 2,
            budgetMillis = 30L,
            paceMillis = 20L,
            now = { clock },
            pause = { clock += it }
        )

        assertEquals(4, outcome.attempted)      // 2 batches of limit 2
        assertEquals(2, outcome.remaining)
        assertFalse(outcome.failed)
    }

    @Test
    fun `drain stops on the first failure rather than hammering a dead endpoint`() = runTest {
        seedItems("one", "two", "three", "four", "five", "six")
        var calls = 0
        val backfill = backfill { items ->
            calls++
            if (calls == 2) throw IllegalStateException("groq down")
            items.associate { it.id to listOf("E${it.id}") }
        }

        val outcome = backfill.runDrain(limit = 2, now = { 0L }, pause = {})

        assertEquals(2, calls)                  // stopped, did not keep retrying
        assertEquals(2, outcome.attempted)      // only the first batch landed
        assertTrue(outcome.failed)
        assertEquals(2L, settings.backfillWatermarkSnapshot())   // held at the failed batch
    }

    // ---- concurrent callers ----------------------------------------------------------------

    @Test
    fun `concurrent batches do not extract the same items twice`() = runTest {
        // Found on device: CatchupWorker runs twice on app start (scheduleNow enqueued alongside
        // the periodic worker), so two batches read watermark 12 at once and made the SAME Groq
        // call — `watermark 12 -> 24` logged twice. Harmless to the data, but it doubled the
        // quota cost of the whole drain.
        val ids = seedItems("one", "two", "three", "four")
        val sentBatches = Collections.synchronizedList(mutableListOf<List<Long>>())
        val started = CountDownLatch(2)
        val backfill = backfill { items ->
            sentBatches += items.map { it.id }
            // Both callers are inside the extractor at once if the gate is missing.
            started.countDown()
            started.await(1, TimeUnit.SECONDS)
            items.associate { it.id to listOf("E${it.id}") }
        }

        coroutineScope {
            listOf(
                async(Dispatchers.IO) { backfill.runBatch(limit = 2) },
                async(Dispatchers.IO) { backfill.runBatch(limit = 2) }
            ).awaitAll()
        }

        // Two batches ran, but over DIFFERENT items — the second saw the advanced watermark.
        assertEquals(2, sentBatches.size)
        val allSent = sentBatches.flatten()
        assertEquals("no item was sent to extraction twice", allSent.size, allSent.toSet().size)
        assertEquals(ids.toSet(), allSent.toSet())
        assertEquals(ids.last(), settings.backfillWatermarkSnapshot())
    }

    // ---- the success path ------------------------------------------------------------------

    @Test
    fun `a successful batch links entities and advances the watermark to the highest id sent`() = runTest {
        val ids = seedItems("Sensex falls on FII selling", "Apple ships MacBook")
        val backfill = backfill { items ->
            mapOf(items[0].id to listOf("Sensex", "FII selling"), items[1].id to listOf("Apple"))
        }

        val outcome = backfill.runBatch()

        assertEquals(2, outcome.attempted)
        assertEquals(3, outcome.linked)
        assertEquals(0, outcome.remaining)
        assertEquals(ids.last(), settings.backfillWatermarkSnapshot())
        assertEquals(setOf("Sensex", "FII selling", "Apple"), db.entityDao().all().map { it.name }.toSet())
        assertEquals(3, db.entityDao().allRefs().size)
    }

    @Test
    fun `an item extracting to zero still counts as attempted and is never re-offered`() = runTest {
        val ids = seedItems("Sensex falls", "Apply or skip?")
        var seen: List<Long> = emptyList()
        val backfill = backfill { items ->
            seen = items.map { it.id }
            mapOf(items[0].id to listOf("Sensex"), items[1].id to emptyList())
        }

        val first = backfill.runBatch()

        assertEquals(ids, seen)
        assertEquals(2, first.attempted)
        assertEquals(1, first.linked)
        assertEquals(0, first.remaining)
        assertEquals(ids.last(), settings.backfillWatermarkSnapshot())

        // The zero-entity item still has no rows in item_entities, so only the watermark keeps
        // it from being handed back forever. This is the assertion that pins that.
        val second = backfill.runBatch()
        assertEquals(EntityBackfill.BatchOutcome(0, 0, 0), second)
    }

    @Test
    fun `ids the response never covered are cleared too, not left to be re-sent`() = runTest {
        // 12 sent, 9 answered — the model dropped the tail. Those three were attempted; an
        // unanswered id is indistinguishable from a legitimate "no recurring subject", and
        // re-offering them is how a backfill fails to terminate.
        val ids = seedItems(*Array(12) { "Story $it" })
        val backfill = backfill { items -> items.take(9).associate { it.id to listOf("Sensex") } }

        val outcome = backfill.runBatch()

        assertEquals(12, outcome.attempted)
        assertEquals(9, outcome.linked)
        assertEquals(0, outcome.remaining)
        assertEquals(ids.last(), settings.backfillWatermarkSnapshot())
        assertEquals(emptyList<Long>(), unattempted())
    }

    @Test
    fun `two items naming the same subject share one entity row`() = runTest {
        seedItems("Sensex falls", "Sensex rebounds")
        val backfill = backfill { items -> items.associate { it.id to listOf("Sensex") } }

        val outcome = backfill.runBatch()

        assertEquals(2, outcome.linked)                       // links, not names
        assertEquals(1, db.entityDao().all().size)            // merged on `normalized`
    }

    // ---- the failure path ------------------------------------------------------------------

    @Test
    fun `a failed extraction holds the watermark so the same batch is retried`() = runTest {
        val ids = seedItems("Sensex falls", "Apple ships MacBook")
        var attempts = 0
        val backfill = backfill { items ->
            attempts++
            if (attempts == 1) throw IllegalStateException("groq down")
            items.associate { it.id to listOf("Sensex") }
        }

        val failed = backfill.runBatch()

        // failed = true is what distinguishes this from an empty queue: both are (0, 0, n), and
        // without the flag Settings would report "2 to go" forever while nothing was happening.
        assertEquals(EntityBackfill.BatchOutcome(0, 0, 2, failed = true), failed)
        assertEquals(0L, settings.backfillWatermarkSnapshot())
        assertTrue(db.entityDao().allRefs().isEmpty())
        assertEquals(ids, unattempted())

        // Next tick picks up exactly the same items.
        val retried = backfill.runBatch()
        assertEquals(2, retried.attempted)
        assertEquals(ids.last(), settings.backfillWatermarkSnapshot())
    }

    // ---- draining --------------------------------------------------------------------------

    @Test
    fun `the batch is bounded by limit and successive batches drain the archive in id order`() = runTest {
        val ids = seedItems("one", "two", "three", "four", "five")
        val batches = mutableListOf<List<Long>>()
        val backfill = backfill { items ->
            batches += items.map { it.id }
            emptyMap()
        }

        assertEquals(2, backfill.runBatch(limit = 2).attempted)
        assertEquals(3, backfill.remaining())
        assertEquals(2, backfill.runBatch(limit = 2).attempted)
        assertEquals(1, backfill.runBatch(limit = 2).attempted)
        assertEquals(0, backfill.remaining())

        assertEquals(listOf(ids.subList(0, 2), ids.subList(2, 4), ids.subList(4, 5)), batches)
        // Nothing left: no further call is made at all.
        assertEquals(EntityBackfill.BatchOutcome(0, 0, 0), backfill.runBatch(limit = 2))
        assertEquals(3, batches.size)
    }

    @Test
    fun `reset re-offers the items that extracted to nothing`() = runTest {
        val ids = seedItems("Sensex falls", "Apply or skip?")
        val backfill = backfill { items -> mapOf(items[0].id to listOf("Sensex")) }

        backfill.runBatch()
        assertEquals(0, backfill.remaining())

        backfill.reset()

        // Only the empty-handed item comes back — the linked one is excluded by the DAO's
        // "has no entities" predicate, which is what makes a prompt change cheap to re-run.
        assertEquals(1, backfill.remaining())
        assertEquals(listOf(ids[1]), unattempted())
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private fun backfill(extract: suspend (List<DigestItem>) -> Map<Long, List<String>>) =
        EntityBackfill(db, settings, extract)

    /** Items still unattempted, i.e. what the next batch would be handed. */
    private suspend fun unattempted(): List<Long> =
        db.entityDao().itemsWithoutEntitiesAfter(settings.backfillWatermarkSnapshot(), 100).map { it.id }

    private suspend fun seedItems(vararg headlines: String): List<Long> =
        headlines.mapIndexed { position, headline ->
            db.digestDao().insertItem(
                DigestItem(
                    digestId = DIGEST_ID, topicLabel = "Dropping",
                    headline = headline, sourceCount = 1, position = position
                )
            )
        }

    private suspend fun inMemoryArchiveWithDigest(): ArchiveDatabase =
        inMemoryArchive().also {
            it.digestDao().insertDigest(
                Digest(
                    id = DIGEST_ID, slot = "EVENING", windowStart = 0, windowEnd = 1,
                    createdAt = 1, itemCount = 0, sourceCount = 0, pipelineMode = "HEURISTIC"
                )
            )
        }

    private companion object {
        const val DIGEST_ID = "2026-06-10-E"
    }
}
