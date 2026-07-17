package com.dhairya.newsmemory.pipeline

import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.util.Normalizer

/**
 * Stage 2 — heuristic near-duplicate merge (EDD §5.2).
 * Token Jaccard on normalized title words (stopwords removed); ≥ 0.55 → same story.
 * Union-find forms groups; longest headline represents; source_count = distinct packages.
 * Runs ALWAYS: shrinks the LLM payload and is the v0 dedup guarantee independent of Groq.
 */
object Deduper {

    const val JACCARD_THRESHOLD = 0.55

    data class MergedStory(
        val representative: RawNotification,
        val members: List<RawNotification>,
        val sourceCount: Int
    )

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.size + b.size - intersection
        return intersection.toDouble() / union
    }

    /**
     * Dedup tokens for one row. When the title alone yields too few tokens to be
     * meaningful (an account-name title like "Inc42" from an X-style capture, or a
     * one-word teaser), mix in the body — otherwise every notification sharing that
     * degenerate title has Jaccard 1.0 with every other and stage 2 force-merges
     * unrelated stories into one blob (found in a 30-day replay: ten distinct "Inc42"
     * posts collapsed into a single merged story before Groq ever saw them).
     */
    private fun dedupTokens(row: RawNotification): Set<String> {
        val titleTokens = Normalizer.titleTokens(row.title ?: row.body)
        if (titleTokens.size <= 2 && !row.body.isNullOrBlank()) {
            return titleTokens + Normalizer.titleTokens(row.body)
        }
        return titleTokens
    }

    fun merge(rows: List<RawNotification>): List<MergedStory> {
        if (rows.isEmpty()) return emptyList()
        val tokens = rows.map { dedupTokens(it) }

        val parent = IntArray(rows.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        for (i in rows.indices) {
            for (j in i + 1 until rows.size) {
                if (jaccard(tokens[i], tokens[j]) >= JACCARD_THRESHOLD) union(i, j)
            }
        }

        return rows.indices.groupBy { find(it) }.values.map { indices ->
            val members = indices.map { rows[it] }
            MergedStory(
                representative = members.maxBy { (it.title ?: it.body ?: "").length },
                members = members,
                // Distinct publisher (falls back to package) — so multiple outlets surfaced
                // through one aggregator app still count as separate sources.
                sourceCount = members.map { it.publisher ?: it.packageName }.distinct().size
            )
        }.sortedByDescending { it.sourceCount }
    }
}
