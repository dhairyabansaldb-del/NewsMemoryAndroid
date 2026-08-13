package com.dhairya.newsmemory.memory

import androidx.room.withTransaction
import com.dhairya.newsmemory.SharedConfig
import com.dhairya.newsmemory.data.SettingsStore
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.data.db.ItemEntityCrossRef
import com.dhairya.newsmemory.util.Normalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fills in entities for digest items that never got any (EDD §7.3).
 *
 * Why this exists: entities were only ever written on the LLM clustering path, and heuristic-mode
 * digests wrote none. In the July export 87 of 96 digests ran heuristic — 88 entities across
 * 3,008 items — so almost the whole archive is uncountable, and recurrence has nothing to count
 * over until this runs. Backfilling is what lets v1 be judged on real history instead of waiting
 * 30 days for new data.
 *
 * Extraction is a function rather than a class so the no-key case is just `null`, matching how
 * `AppContainer` wires `clusterEngine`. Returns entity names per digest_item id; an item that
 * legitimately has no recurring subject maps to an empty list, which is a correct answer.
 *
 * Progress is a watermark in [SettingsStore], not a "has entities" check — see the note there
 * for why, and why that keeps the archive at schema v2.
 */
class EntityBackfill(
    private val db: ArchiveDatabase,
    private val settings: SettingsStore,
    private val extract: (suspend (List<DigestItem>) -> Map<Long, List<String>>)?,
    private val log: (String) -> Unit = {}
) {

    /**
     * Serialises batches within the process.
     *
     * Read-watermark → extract → write-links → advance-watermark is not atomic, and there is
     * more than one caller: the Settings button, and CatchupWorker — which itself runs twice on
     * app start, because `scheduleNow` enqueues alongside the periodic worker. Observed on
     * device: two batches read watermark 12 concurrently and made the SAME Groq call, logging
     * `watermark 12 → 24` twice. Harmless to the data (link uses INSERT IGNORE and upsert merges
     * on `normalized`) but it doubled the quota cost of a ~250-batch drain.
     *
     * With the lock the second caller waits, re-reads the advanced watermark, and does the NEXT
     * batch — the duplicated call becomes real progress instead of waste.
     */
    private val gate = Mutex()

    /**
     * What one batch did. `attempted` counts items sent; `linked` counts (item, entity) rows.
     *
     * `failed` exists because without it a failed batch and an empty queue are indistinguishable
     * — both are (0, 0, remaining) — and a backfill that can never reach Groq would sit there
     * reporting "3000 to go" forever. Degradation is never silent in this app (§4.2); that rule
     * is exactly what the permanent-heuristic-fallback bug taught (§5.1).
     */
    data class BatchOutcome(
        val attempted: Int,
        val linked: Int,
        val remaining: Int,
        val failed: Boolean = false
    )

    /** False when no Groq key is configured — Settings shows the control as unavailable. */
    val available: Boolean get() = extract != null

    /** Digest items still unattempted. Reaches 0 exactly once the archive is fully covered. */
    suspend fun remaining(): Int =
        db.entityDao().itemsRemainingAfter(settings.backfillWatermarkSnapshot())

    /**
     * Re-offer every item extraction has so far produced NOTHING for — worth doing after changing
     * the prompt, since items that extracted to zero deserve another look under new wording.
     *
     * Note this is NOT "re-run everything": items that already got entities are excluded by the
     * query's `ie.item_id IS NULL`, so a prompt change can never revise them. Revising those
     * would mean deleting existing item_entities rows, which is destructive and deliberately not
     * offered here.
     */
    suspend fun reset() = settings.resetBackfillWatermark()

    /**
     * Extract and link one bounded batch, then advance the watermark.
     *
     * Bounded on purpose: this runs from the hourly catch-up worker, and an unbounded pass would
     * fire ~250 Groq calls back to back and exhaust the free tier's daily quota. Draining slowly
     * across hours is the intended behaviour, not a limitation.
     *
     * The watermark advances to the highest id SENT, and only after extraction succeeded and the
     * links are committed:
     *  - extraction threw (network, HTTP, unparseable after a re-ask) → watermark untouched, the
     *    same batch is retried next tick. Nothing is silently skipped.
     *  - extraction answered for some ids only → the unanswered ones are recorded as zero
     *    entities and the watermark still clears them. They were attempted, and "no recurring
     *    subject" is what the great majority of them genuinely are; re-offering them forever is
     *    how a backfill never terminates. `reset()` re-runs everything under a changed prompt.
     */
    suspend fun runBatch(limit: Int = BATCH_SIZE): BatchOutcome = gate.withLock {
        val extractOrNull = extract ?: return BatchOutcome(0, 0, remaining())

        val watermark = settings.backfillWatermarkSnapshot()
        val items = db.entityDao().itemsWithoutEntitiesAfter(watermark, limit)
        if (items.isEmpty()) return BatchOutcome(0, 0, remaining())

        val names = try {
            extractOrNull(items)
        } catch (e: Exception) {
            log("batch of ${items.size} failed (${e.javaClass.simpleName}: ${e.message}) " +
                "— watermark held at $watermark, retrying next tick")
            return BatchOutcome(0, 0, remaining(), failed = true)
        }

        // Same write path as the LLM clustering digest (DigestPipeline): upsert on the
        // normalized merge key, then link. One transaction, so a crash mid-batch leaves no
        // half-linked item behind the watermark.
        val now = System.currentTimeMillis()
        val linked = db.withTransaction {
            var count = 0
            for (item in items) {
                val entities = names[item.id].orEmpty()
                if (entities.isEmpty()) continue
                val refs = entities
                    .map { name ->
                        ItemEntityCrossRef(item.id, db.entityDao().upsert(name, Normalizer.normalize(name), now))
                    }
                    .distinctBy { it.entityId }   // two spellings can share one entity row
                db.entityDao().link(refs)
                count += refs.size
            }
            count
        }

        val highest = items.maxOf { it.id }
        settings.setBackfillWatermark(highest)
        log("attempted ${items.size} items → $linked links, watermark $watermark → $highest")
        return BatchOutcome(attempted = items.size, linked = linked, remaining = remaining())
    }

    /**
     * Run batches back to back, paced, until the queue empties, a batch fails, or the time
     * budget runs out. Returns the totals.
     *
     * Why this exists: one batch per hourly tick meant 3,008 items / 12 = 250 hours — **ten
     * days** to drain. The whole argument for backfilling rather than waiting for new data was
     * to avoid a month-long wait, so ten days largely gave that back. The original bound was
     * justified as protecting the free tier's daily quota, but that reasoning doesn't hold: the
     * daily allowance is ~14,400 requests and the entire backfill is 250. The real limit is TPM,
     * which permits about one batch every 21s — so the whole archive is ~90 minutes of API time.
     *
     * Bounded by wall clock rather than batch count because WorkManager kills a worker at 10
     * minutes; [DRAIN_BUDGET_MS] leaves headroom for the digest catch-up that runs before it.
     *
     * `pace` and `now` are injectable so tests don't sleep.
     */
    suspend fun runDrain(
        limit: Int = BATCH_SIZE,
        budgetMillis: Long = DRAIN_BUDGET_MS,
        paceMillis: Long = PACE_MS,
        now: () -> Long = System::currentTimeMillis,
        pause: suspend (Long) -> Unit = { delay(it) }
    ): BatchOutcome {
        val deadline = now() + budgetMillis
        var attempted = 0
        var linked = 0
        var batches = 0

        while (true) {
            val outcome = runBatch(limit)
            attempted += outcome.attempted
            linked += outcome.linked
            // Stop on failure rather than hammering a dead endpoint for the whole budget; the
            // watermark held, so the next tick retries from the same place.
            if (outcome.failed) {
                log("drain stopped after $batches batches — extraction failing")
                return BatchOutcome(attempted, linked, outcome.remaining, failed = true)
            }
            if (outcome.attempted == 0) return BatchOutcome(attempted, linked, outcome.remaining)
            batches++
            // Check before sleeping, so the budget bounds the work rather than the work plus a
            // final pointless pause.
            if (now() + paceMillis >= deadline) {
                log("drain paused after $batches batches ($attempted items) — budget spent")
                return BatchOutcome(attempted, linked, outcome.remaining)
            }
            pause(paceMillis)
        }
    }

    companion object {
        /**
         * Items per extraction call, from shared/entity-config.json so the offline harness
         * batches identically. Kept below the ~30 mark where gpt-oss-120b starts fumbling id
         * bookkeeping (§5.1), and small enough that a lost batch is cheap to redo.
         */
        const val BATCH_SIZE = SharedConfig.ENTITY_BATCH_SIZE

        /**
         * Spacing between batches. Groq's free tier pre-counts input + max_completion_tokens
         * against a hard 8000 TPM cap; at batchSize 12 a call reserves ~2,770, giving ~2.9 calls
         * per rolling minute. The maths is spelled out in shared/entity-config.json.
         *
         * App-internal rather than shared (§7): the harness paces with its own rolling-window
         * limiter, so there is no value here for it to agree with.
         */
        const val PACE_MS = 21_000L

        /** Under WorkManager's 10-minute ceiling, with room for the catch-up work that precedes it. */
        const val DRAIN_BUDGET_MS = 5 * 60 * 1000L
    }
}
