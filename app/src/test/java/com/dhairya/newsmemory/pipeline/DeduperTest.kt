package com.dhairya.newsmemory.pipeline

import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.util.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduperTest {

    private var nextId = 1L
    private fun raw(title: String, pkg: String = "com.news.a", body: String? = null) = RawNotification(
        id = nextId++,
        packageName = pkg,
        title = title,
        body = body,
        contentHash = Normalizer.contentHash(title, body),
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

    /**
     * Found in a 30-day live replay (2026-07-17): X/Twitter-style captures whose title
     * is just the account handle ("Inc42") all have Jaccard 1.0 with each other on
     * title alone, so ten distinct posts collapsed into one merged story before Groq
     * ever saw them. Body text must break the tie for degenerate (<=2 token) titles.
     */
    @Test
    fun `identical degenerate titles do not force-merge when bodies differ`() {
        val rows = listOf(
            raw("Inc42", body = "udaan lines up a \$160M financing round to strengthen its balance sheet"),
            raw("Inc42", pkg = "com.news.b", body = "Shark Tank-fame Medial shuts down after failing to raise fresh funds"),
            raw("Inc42", pkg = "com.news.c", body = "Zomato is testing an AI voice bot for food ordering")
        )
        assertEquals(3, Deduper.merge(rows).size)
    }

    @Test
    fun `identical degenerate titles with near-identical bodies still merge`() {
        val rows = listOf(
            raw("Inc42", body = "Healthtech startup Mykare raises \$3.2 million to expand hospital network"),
            raw("Inc42", pkg = "com.news.b", body = "Healthtech startup Mykare raises \$3.2 million to expand its hospital network")
        )
        assertEquals(1, Deduper.merge(rows).size)
    }

    @Test
    fun `a degenerate title with no body is left as its own weak signal`() {
        // No body to enrich with — falls back to the (near-empty) title tokens, same as before.
        val rows = listOf(raw("Inc42"), raw("Inc42", pkg = "com.news.b"))
        assertEquals(1, Deduper.merge(rows).size)
    }

    @Test
    fun `jaccard basics`() {
        assertEquals(1.0, Deduper.jaccard(setOf("a", "b"), setOf("a", "b")), 1e-9)
        assertEquals(0.0, Deduper.jaccard(setOf("a"), setOf("b")), 1e-9)
        assertEquals(0.0, Deduper.jaccard(emptySet(), setOf("a")), 1e-9)
        assertEquals(1.0 / 3, Deduper.jaccard(setOf("a", "b"), setOf("a", "c")), 1e-9)
    }
}
