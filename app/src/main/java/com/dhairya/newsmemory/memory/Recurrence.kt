package com.dhairya.newsmemory.memory

import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.data.db.EntityDao

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
 * Counts recurrence over the archive.
 *
 * Wave 0 seam: signatures are final and return empty so UI and DI compile against them.
 * The counting logic lands in the recurrence track.
 */
class RecurrenceEngine(
    @Suppress("unused") private val entityDao: EntityDao,
    @Suppress("unused") private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Flags for one digest's items, keyed by `DigestItem.id`. Counts are INCLUSIVE of the
     * digest being read — by the time anything reads a digest its own rows are already
     * committed, so a story appearing for the fourth time reads as "4th", not "3rd".
     */
    suspend fun flagsForDigest(
        @Suppress("unused") digestId: String,
        @Suppress("unused") items: List<DigestItem>
    ): Map<Long, RecurrenceFlag> = emptyMap()

    /** Top recurring entities over the trailing 30 days, most active first. */
    suspend fun building(@Suppress("unused") limit: Int = 1): List<BuildingSignal> = emptyList()

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
    }
}
