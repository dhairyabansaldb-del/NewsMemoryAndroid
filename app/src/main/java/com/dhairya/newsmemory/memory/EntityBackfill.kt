package com.dhairya.newsmemory.memory

import com.dhairya.newsmemory.data.SettingsStore
import com.dhairya.newsmemory.data.db.ArchiveDatabase
import com.dhairya.newsmemory.data.db.DigestItem

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
    @Suppress("unused") private val log: (String) -> Unit = {}
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
     * Wave 2 seam: signature is final and the body lands with the extractor.
     */
    suspend fun runBatch(@Suppress("unused") limit: Int = BATCH_SIZE): BatchOutcome =
        BatchOutcome(attempted = 0, linked = 0, remaining = remaining())

    companion object {
        /**
         * Items per extraction call. Kept below the ~30 mark where gpt-oss-120b starts fumbling
         * id bookkeeping (§5.1), and small enough that a lost batch is cheap to redo.
         */
        const val BATCH_SIZE = 12
    }
}
