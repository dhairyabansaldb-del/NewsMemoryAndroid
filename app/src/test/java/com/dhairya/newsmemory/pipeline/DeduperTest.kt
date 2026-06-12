package com.dhairya.newsmemory.pipeline

import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.util.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduperTest {

    private var nextId = 1L
    private fun raw(title: String, pkg: String = "com.news.a") = RawNotification(
        id = nextId++,
        packageName = pkg,
        title = title,
        body = null,
        contentHash = Normalizer.contentHash(title, null),
        postedAt = 0,
        capturedAt = 0,
        windowBucket = "2026-06-10-E"
    )

    /**
     * Fixture of realistic reworded headline groups (EDD §9): each inner list is one
     * story phrased differently by different outlets, sharing enough tokens to clear
     * the 0.55 Jaccard threshold; distinct stories must not merge.
     */
    private val rewordedGroups = listOf(
        listOf(
            "Sensex falls 800 points as FII selling continues",
            "Sensex tumbles 800 points amid relentless FII selling",
            "FII selling drags Sensex down 800 points"
        ),
        listOf(
            "RBI holds repo rate steady at 6.5% in policy review",
            "RBI holds repo rate at 6.5% in latest policy review"
        ),
        listOf(
            "Sarvam AI raises $200M Series B funding",
            "Sarvam AI raises $200M in Series B round"
        ),
        listOf(
            "Monsoon arrives early in Kerala says IMD",
            "Monsoon arrives early in Kerala IMD confirms"
        )
    )

    /**
     * Loose paraphrases that fall BELOW 0.55 — the heuristic deliberately keeps them
     * separate; merging these is the LLM's job in stage 3 (EDD §5.2). This boundary
     * is part of the spec: a heuristic that merged this aggressively would false-merge
     * distinct stories too.
     */
    private val belowThresholdPair = listOf(
        "RBI holds repo rate steady at 6.5%",
        "RBI keeps repo rate unchanged at 6.5%"
    )

    private val distinctSingles = listOf(
        "Apple unveils new MacBook lineup",
        "ISRO announces Gaganyaan launch date",
        "Gold prices hit record high in Delhi"
    )

    @Test
    fun `reworded variants of the same story merge into one`() {
        for (group in rewordedGroups) {
            val rows = group.mapIndexed { i, t -> raw(t, pkg = "com.news.$i") }
            val merged = Deduper.merge(rows)
            assertEquals("group should merge: $group", 1, merged.size)
            assertEquals(group.size, merged[0].members.size)
            assertEquals(group.size, merged[0].sourceCount)
        }
    }

    @Test
    fun `distinct stories never merge`() {
        val rows = distinctSingles.map { raw(it) }
        assertEquals(distinctSingles.size, Deduper.merge(rows).size)
    }

    @Test
    fun `loose paraphrases below threshold stay separate for the LLM stage`() {
        val rows = belowThresholdPair.mapIndexed { i, t -> raw(t, pkg = "com.news.$i") }
        assertEquals(2, Deduper.merge(rows).size)
    }

    @Test
    fun `mixed window merges within groups only`() {
        val rows = (rewordedGroups.flatMapIndexed { g, group ->
            group.mapIndexed { i, t -> raw(t, pkg = "com.news.g$g.$i") }
        } + distinctSingles.map { raw(it) })

        val merged = Deduper.merge(rows)
        assertEquals(rewordedGroups.size + distinctSingles.size, merged.size)
    }

    @Test
    fun `longest headline becomes representative`() {
        val rows = listOf(
            raw("Sensex falls 800 points on FII selling"),
            raw("Sensex falls 800 points as FII selling continues unabated", pkg = "com.news.b")
        )
        val merged = Deduper.merge(rows)
        assertEquals(1, merged.size)
        assertTrue(merged[0].representative.title!!.contains("unabated"))
    }

    @Test
    fun `multi-source stories sort first`() {
        val rows = listOf(
            raw("Gold prices hit record high in Delhi"),
            raw("Sensex falls 800 points as FII selling continues"),
            raw("Sensex tumbles 800 points amid relentless FII selling", pkg = "com.news.b"),
            raw("FII selling drags Sensex down 800 points", pkg = "com.news.c")
        )
        val merged = Deduper.merge(rows)
        assertEquals(3, merged[0].sourceCount)
    }

    @Test
    fun `jaccard basics`() {
        assertEquals(1.0, Deduper.jaccard(setOf("a", "b"), setOf("a", "b")), 1e-9)
        assertEquals(0.0, Deduper.jaccard(setOf("a"), setOf("b")), 1e-9)
        assertEquals(0.0, Deduper.jaccard(emptySet(), setOf("a")), 1e-9)
        assertEquals(1.0 / 3, Deduper.jaccard(setOf("a", "b"), setOf("a", "c")), 1e-9)
    }
}
