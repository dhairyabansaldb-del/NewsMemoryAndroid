package com.dhairya.newsmemory.memory

import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.data.db.EntityDao
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The v1 memory layer: recurrence by COUNTING over item_entities, never by asking a model
 * whether a headline matters (EDD §7.1, PROJECT_MEMORY §4.5). Headlines are engagement bait;
 * a memory layer that let an LLM judge their importance would inherit that bait. Counting is
 * the defence, so nothing in this package calls Groq.
 *
 * Recurrence is derived at read time — `digest_items` carries no flag column, so the archive
 * needs no schema change and no migration (PROJECT_MEMORY §9 landmine).
 */

/** Which counting rule fired. One flag per item; highest count wins when several apply. */
enum class FlagKind { WEEK_COUNT, MULTI_SOURCE, DAY_STREAK }

data class RecurrenceFlag(
    val entityId: Long,
    val entityName: String,
    val kind: FlagKind,
    val count: Int
) {
    /** Short form for the chip on a digest item card (handoff §Story card, rec tag). */
    val chipLabel: String
        get() = when (kind) {
            FlagKind.WEEK_COUNT -> "${ordinal(count)} this week"
            FlagKind.MULTI_SOURCE -> "$count sources"
            FlagKind.DAY_STREAK -> "$count days running"
        }

    /** Long form for push copy — the "4th story on FII selling this week" of the PRD. */
    val pushLabel: String
        get() = when (kind) {
            FlagKind.WEEK_COUNT -> "${ordinal(count)} story on $entityName this week"
            FlagKind.MULTI_SOURCE -> "$entityName across $count sources"
            FlagKind.DAY_STREAK -> "$entityName — $count days running"
        }

    companion object {
        fun ordinal(n: Int): String {
            val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
                1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
            }
            return "$n$suffix"
        }
    }
}

/** A recurring entity for the Home "What's building" card. */
data class BuildingSignal(
    val entityId: Long,
    val entityName: String,
    val itemCount: Int,
    val dayCount: Int,
    /** Days back from today on which this entity appeared — drives the dot matrix. */
    val activeDayOffsets: Set<Int>
)

/**
 * Counts recurrence over the archive. Three counting rules, no interpretation — see the
 * file header for why there is no Groq client here.
 *
 * `clock` is injected so the trailing-window arithmetic is testable; production passes
 * `System::currentTimeMillis`.
 */
class RecurrenceEngine(
    private val entityDao: EntityDao,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Flags for one digest's items, keyed by `DigestItem.id`. Counts are INCLUSIVE of the
     * digest being read — by the time anything reads a digest its own rows are already
     * committed, so a story appearing for the fourth time reads as "4th", not "3rd".
     */
    suspend fun flagsForDigest(
        digestId: String,
        items: List<DigestItem>
    ): Map<Long, RecurrenceFlag> {
        // Guard the empty IN list rather than letting Room build `WHERE item_id IN ()`.
        if (items.isEmpty()) return emptyMap()

        val pairings = entityDao.entitiesForItems(items.map { it.id })
        if (pairings.isEmpty()) return emptyMap()

        val now = clock()
        val weekCutoff = now - Duration.ofDays(WEEK_DAYS.toLong()).toMillis()
        val monthCutoff = now - Duration.ofDays(MONTH_DAYS.toLong()).toMillis()

        // One entity usually rides several items in the same digest, and its counts don't
        // depend on which item we're looking at — so evaluate each entity once. Without this
        // the read path fires three queries per (item, entity) pair for identical answers.
        val perEntity = HashMap<Long, RecurrenceFlag?>()
        for (pairing in pairings) {
            if (perEntity.containsKey(pairing.entityId)) continue
            perEntity[pairing.entityId] =
                bestFlagFor(pairing.entityId, pairing.name, digestId, weekCutoff, monthCutoff)
        }

        // One flag per item (EDD §7.1): an item can link several qualifying entities, so the
        // strongest single flag represents it. FLAG_ORDER makes "strongest" total and stable.
        val perItem = LinkedHashMap<Long, RecurrenceFlag>()
        for (pairing in pairings) {
            val flag = perEntity[pairing.entityId] ?: continue
            val held = perItem[pairing.itemId]
            if (held == null || FLAG_ORDER.compare(flag, held) < 0) perItem[pairing.itemId] = flag
        }

        // Flags stop meaning anything if every card carries one: keep the strongest few.
        if (perItem.size <= MAX_FLAGS_PER_DIGEST) return perItem
        val strongestFirst = Comparator<Map.Entry<Long, RecurrenceFlag>> { a, b ->
            val byFlag = FLAG_ORDER.compare(a.value, b.value)
            // Item id only as a last resort, purely so the trim is reproducible.
            if (byFlag != 0) byFlag else a.key.compareTo(b.key)
        }
        return perItem.entries
            .sortedWith(strongestFirst)
            .take(MAX_FLAGS_PER_DIGEST)
            .associate { it.key to it.value }
    }

    /**
     * The strongest rule this entity satisfies right now, or null when it satisfies none.
     * All three counts are read because the winner is chosen by count, not by rule.
     */
    private suspend fun bestFlagFor(
        entityId: Long,
        entityName: String,
        digestId: String,
        weekCutoff: Long,
        monthCutoff: Long
    ): RecurrenceFlag? {
        val candidates = ArrayList<RecurrenceFlag>(3)

        val weekItems = entityDao.itemCountSince(entityId, weekCutoff)
        if (weekItems >= MIN_ITEMS_PER_WEEK) {
            candidates += RecurrenceFlag(entityId, entityName, FlagKind.WEEK_COUNT, weekItems)
        }

        val sources = entityDao.publisherCountInDigest(entityId, digestId)
        if (sources >= MIN_SOURCES_IN_DIGEST) {
            candidates += RecurrenceFlag(entityId, entityName, FlagKind.MULTI_SOURCE, sources)
        }

        val activeDays = entityDao.activeDaysSince(entityId, monthCutoff).size
        if (activeDays >= MIN_DAYS_PER_MONTH) {
            candidates += RecurrenceFlag(entityId, entityName, FlagKind.DAY_STREAK, activeDays)
        }

        return candidates.minWithOrNull(FLAG_ORDER)
    }

    /** Top recurring entities over the trailing 30 days, most active first. */
    suspend fun building(limit: Int = 1): List<BuildingSignal> {
        val now = clock()
        val since = now - Duration.ofDays(MONTH_DAYS.toLong()).toMillis()
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()

        return entityDao.topEntitiesSince(since, limit).mapNotNull { tally ->
            val offsets = entityDao.activeDaysSince(tally.entityId, since)
                .mapNotNull { dayOffsetOf(it, today) }
                .toSet()
            // The tally window (30 days) is wider than the matrix (28), so an entity can be
            // ranked yet have nothing to draw. Nothing to draw means nothing to show.
            if (offsets.isEmpty()) null else BuildingSignal(
                entityId = tally.entityId,
                entityName = tally.name,
                itemCount = tally.itemCount,
                dayCount = tally.dayCount,
                activeDayOffsets = offsets
            )
        }
    }

    /** "YYYY-MM-DD" → days back from today (0 = today), or null if off the dot matrix. */
    private fun dayOffsetOf(day: String, today: LocalDate): Int? {
        val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return null
        val offset = ChronoUnit.DAYS.between(date, today)
        return if (offset in 0 until MATRIX_DAYS.toLong()) offset.toInt() else null
    }

    companion object {
        /** EDD §7.1 thresholds. Policy lives here so the rules are readable in one place. */
        const val WEEK_DAYS = 7
        const val MONTH_DAYS = 30
        const val MIN_ITEMS_PER_WEEK = 3
        const val MIN_SOURCES_IN_DIGEST = 3
        const val MIN_DAYS_PER_MONTH = 5

        /** Caps: one flag per item, and a bounded number per digest so flags stay meaningful. */
        const val MAX_FLAGS_PER_DIGEST = 5

        /** Dot matrix on the Home card is 7×4 (handoff §What's building). */
        const val MATRIX_DAYS = 28

        /**
         * The single ordering used everywhere flags compete: one entity satisfying several
         * rules, several flagged entities on one item, and trimming to MAX_FLAGS_PER_DIGEST.
         *
         * 1. **count descending** — the loudest recurrence wins, because the count is the
         *    whole message ("6th this week" beats "3 sources").
         * 2. **rule precedence WEEK_COUNT > MULTI_SOURCE > DAY_STREAK** (EDD §7.1's stated
         *    priority; it is also the enum's declaration order, so `kind` compares directly).
         *    Only reached on equal counts, where the counts mean different things and the
         *    priority list is the tie-breaker the spec already fixed.
         * 3. **entity id ascending** — carries no meaning, exists so that two runs over
         *    unchanged data produce byte-identical flags instead of coin-flipping on
         *    row order.
         */
        private val FLAG_ORDER: Comparator<RecurrenceFlag> =
            compareByDescending<RecurrenceFlag> { it.count }
                .thenBy { it.kind }
                .thenBy { it.entityId }
    }
}
