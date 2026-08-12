package com.dhairya.newsmemory.memory

import androidx.room.withTransaction
import com.dhairya.newsmemory.SharedConfig
import com.dhairya.newsmemory.data.SettingsStore
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.data.db.ItemEntityCrossRef
import com.dhairya.newsmemory.util.Normalizer

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

    /** What one batch did. `attempted` counts items sent; `linked` counts (item, entity) rows. */
    data class BatchOutcome(val attempted: Int, val linked: Int, val remaining: Int)

    /** False when no Groq key is configured — Settings shows the control as unavailable. */
    val available: Boolean get() = extract != null

    /** Digest items still unattempted. Reaches 0 exactly once the archive is fully covered. */
    suspend fun remaining(): Int =
        db.entityDao().itemsRemainingAfter(settings.backfillWatermarkSnapshot())

    /**
     * Re-run everything from the start. The reason to do this is a changed extraction prompt:
     * items that previously extracted to zero deserve another look under new wording.
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
    suspend fun runBatch(limit: Int = BATCH_SIZE): BatchOutcome {
        val extractOrNull = extract ?: return BatchOutcome(0, 0, remaining())

        val watermark = settings.backfillWatermarkSnapshot()
        val items = db.entityDao().itemsWithoutEntitiesAfter(watermark, limit)
        if (items.isEmpty()) return BatchOutcome(0, 0, remaining())

        val names = try {
            extractOrNull(items)
        } catch (e: Exception) {
            log("batch of ${items.size} failed (${e.javaClass.simpleName}: ${e.message}) " +
                "— watermark held at $watermark, retrying next tick")
            return BatchOutcome(0, 0, remaining())
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

    companion object {
        /**
         * Items per extraction call, from shared/entity-config.json so the offline harness
         * batches identically. Kept below the ~30 mark where gpt-oss-120b starts fumbling id
         * bookkeeping (§5.1), and small enough that a lost batch is cheap to redo.
         */
        const val BATCH_SIZE = SharedConfig.ENTITY_BATCH_SIZE
    }
}
