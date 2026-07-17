package com.dhairya.newsmemory.llm

import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.pipeline.ClusterResult
import com.dhairya.newsmemory.pipeline.Deduper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterResponseParserTest {

    private var nextId = 1L
    private fun story(title: String): Deduper.MergedStory {
        val raw = RawNotification(
            id = nextId++, packageName = "com.news", title = title, body = null,
            contentHash = title, postedAt = 0, capturedAt = 0, windowBucket = "2026-06-10-E"
        )
        return Deduper.MergedStory(representative = raw, members = listOf(raw), sourceCount = 1)
    }

    private val three = listOf(story("Sensex falls on FII selling"), story("Apple unveils MacBook"), story("FII selling drags Sensex"))

    /** Every input story must land in exactly one output cluster, whatever the repairs. */
    private fun assertCoversAll(result: ClusterResult, stories: List<Deduper.MergedStory>) {
        val placed = result.clusters.flatMap { it.stories }
        assertEquals(stories.size, placed.size)
        assertEquals(stories.toSet(), placed.toSet())
    }

    @Test
    fun `valid partition maps ids, headline, and orders representative first`() {
        val content = """
            {"clusters":[
              {"topic":"Markets","headline":"Sensex slides on continued FII selling","headline_ids":[1,3],"representative":3,"entities":["Sensex","FII selling"]},
              {"topic":"Tech","headline_ids":[2],"representative":2,"entities":[]}
            ]}
        """.trimIndent()

        val result = ClusterResponseParser.parse(content, three)

        assertEquals(ClusterResult.MODE_LLM, result.mode)
        assertEquals(2, result.clusters.size)
        val markets = result.clusters[0]
        assertEquals("Markets", markets.topicLabel)
        assertEquals("Sensex slides on continued FII selling", markets.headline)
        assertEquals(listOf("Sensex", "FII selling"), markets.entities)
        // representative id 3 → that story must be first (DigestPipeline reads stories.first()).
        assertEquals("FII selling drags Sensex", markets.stories.first().representative.title)
        assertEquals(2, markets.stories.size)
        assertCoversAll(result, three)
    }

    @Test
    fun `missing headline field leaves the cluster headline null`() {
        val content = """{"clusters":[{"topic":"Markets","headline_ids":[1,2,3],"representative":1,"entities":[]}]}"""
        val result = ClusterResponseParser.parse(content, three)
        assertEquals(null, result.clusters[0].headline)
    }

    @Test
    fun `blank headline field is treated as missing`() {
        val content = """{"clusters":[{"topic":"Markets","headline":"  ","headline_ids":[1,2,3],"representative":1,"entities":[]}]}"""
        val result = ClusterResponseParser.parse(content, three)
        assertEquals(null, result.clusters[0].headline)
    }

    @Test
    fun `concatenated id is split into the two ids it came from`() {
        // gpt-oss-120b's most common mistake: ids 1 and 3 emitted as the single value 13.
        val content = """{"clusters":[{"topic":"Markets","headline_ids":[13],"representative":13,"entities":[]},{"topic":"Tech","headline_ids":[2],"representative":2,"entities":[]}]}"""

        val result = ClusterResponseParser.parse(content, three)

        assertEquals(ClusterResult.MODE_LLM, result.mode)
        assertEquals(2, result.clusters.size)
        assertEquals(2, result.clusters[0].stories.size)   // 13 -> 1 and 3
        assertCoversAll(result, three)
    }

    @Test
    fun `an out-of-range id with no valid split is just dropped`() {
        // n=3: id 99 has no split whose halves are both in range (9 is out of range
        // either way), so it's out-of-range noise, not a dropped comma. ids 1 and 2 are
        // real so the model still clears the coverage floor.
        val content = """{"clusters":[{"topic":"Markets","headline_ids":[1,2,99],"representative":1,"entities":[]}]}"""

        val result = ClusterResponseParser.parse(content, three)

        assertEquals(ClusterResult.MODE_LLM, result.mode)
        assertEquals(2, result.clusters[0].stories.size)   // 1 and 2 kept; 99 dropped
        assertCoversAll(result, three)   // story 3 lands as an orphan singleton
    }

    @Test
    fun `entities are capped at four`() {
        val content = """{"clusters":[{"topic":"Markets","headline_ids":[1,2,3],"representative":1,"entities":["a","b","c","d","e","f"]}]}"""
        val result = ClusterResponseParser.parse(content, three)
        assertEquals(listOf("a", "b", "c", "d"), result.clusters[0].entities)
    }

    @Test
    fun `missing id is repaired into a singleton cluster`() {
        // Model forgot headline 3 entirely.
        val content = """{"clusters":[{"topic":"Markets","headline_ids":[1,2],"representative":1,"entities":[]}]}"""

        val result = ClusterResponseParser.parse(content, three)

        assertEquals(ClusterResult.MODE_LLM, result.mode)
        assertEquals(2, result.clusters.size)
        val orphan = result.clusters[1]
        assertEquals(1, orphan.stories.size)
        assertEquals("FII selling drags Sensex", orphan.stories[0].representative.title)
        assertTrue(orphan.topicLabel.isNotBlank())   // heuristic label, never empty
        assertCoversAll(result, three)
    }

    @Test
    fun `duplicate id is repaired - first cluster wins`() {
        val content = """{"clusters":[{"topic":"A","headline_ids":[1,2],"representative":1,"entities":[]},{"topic":"B","headline_ids":[2,3],"representative":3,"entities":[]}]}"""

        val result = ClusterResponseParser.parse(content, three)

        assertEquals(2, result.clusters.size)
        assertEquals(2, result.clusters[0].stories.size)          // A keeps 1 and 2
        assertEquals(1, result.clusters[1].stories.size)          // B keeps only 3
        assertEquals("FII selling drags Sensex", result.clusters[1].stories[0].representative.title)
        assertCoversAll(result, three)
    }

    @Test
    fun `out of range id is dropped and the story it displaced becomes a singleton`() {
        val content = """{"clusters":[{"topic":"A","headline_ids":[1,2,4],"representative":1,"entities":[]}]}"""

        val result = ClusterResponseParser.parse(content, three)

        assertEquals(2, result.clusters.size)
        assertEquals(2, result.clusters[0].stories.size)          // 4 dropped, 1+2 kept
        assertEquals(1, result.clusters[1].stories.size)          // 3 orphaned → singleton
        assertCoversAll(result, three)
    }

    @Test
    fun `representative outside its cluster is repaired to the first id`() {
        val content = """{"clusters":[{"topic":"A","headline_ids":[1,2,3],"representative":9,"entities":[]}]}"""

        val result = ClusterResponseParser.parse(content, three)

        assertEquals(1, result.clusters.size)
        assertEquals("Sensex falls on FII selling", result.clusters[0].stories.first().representative.title)
        assertCoversAll(result, three)
    }

    @Test
    fun `blank topic gets a heuristic label`() {
        val content = """{"clusters":[{"topic":"","headline_ids":[1,2,3],"representative":1,"entities":[]}]}"""

        val result = ClusterResponseParser.parse(content, three)

        assertTrue(result.clusters[0].topicLabel.isNotBlank())
    }

    @Test
    fun `a cluster left empty after repairs is dropped`() {
        // Second cluster's only ids are a duplicate and an out-of-range id.
        val content = """{"clusters":[{"topic":"A","headline_ids":[1,2,3],"representative":1,"entities":[]},{"topic":"B","headline_ids":[1,7],"representative":1,"entities":[]}]}"""

        val result = ClusterResponseParser.parse(content, three)

        assertEquals(1, result.clusters.size)
        assertCoversAll(result, three)
    }

    @Test
    fun `coverage under half is beyond repair`() {
        // Model placed only 1 of 3 headlines — that's failure, not fumbled bookkeeping.
        val content = """{"clusters":[{"topic":"A","headline_ids":[1],"representative":1,"entities":[]}]}"""
        assertThrows(Exception::class.java) { ClusterResponseParser.parse(content, three) }
    }

    @Test
    fun `malformed json is rejected`() {
        assertThrows(Exception::class.java) { ClusterResponseParser.parse("not json at all", three) }
    }

    @Test
    fun `empty clusters array is rejected`() {
        assertThrows(Exception::class.java) { ClusterResponseParser.parse("""{"clusters":[]}""", three) }
    }
}
