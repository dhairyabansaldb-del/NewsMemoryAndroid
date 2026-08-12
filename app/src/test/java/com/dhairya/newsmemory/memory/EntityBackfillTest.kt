package com.dhairya.newsmemory.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dhairya.newsmemory.data.SettingsStore
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.Digest
import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.testing.inMemoryArchive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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

        assertEquals(EntityBackfill.BatchOutcome(0, 0, 2), failed)
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
