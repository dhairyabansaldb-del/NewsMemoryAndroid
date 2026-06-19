package com.dhairya.newsmemory.llm

import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.pipeline.ClusterResult
import com.dhairya.newsmemory.pipeline.Deduper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun `valid partition maps ids and orders representative first`() {
        val content = """
            {"clusters":[
              {"topic":"Markets","headline_ids":[1,3],"representative":3,"entities":["Sensex","FII selling"]},
              {"topic":"Tech","headline_ids":[2],"representative":2,"entities":[]}
            ]}
        """.trimIndent()

        val result = ClusterResponseParser.parse(content, three)

        assertEquals(ClusterResult.MODE_LLM, result.mode)
        assertEquals(2, result.clusters.size)
        val markets = result.clusters[0]
        assertEquals("Markets", markets.topicLabel)
        assertEquals(listOf("Sensex", "FII selling"), markets.entities)
        // representative id 3 → that story must be first (DigestPipeline reads stories.first()).
        assertEquals("FII selling drags Sensex", markets.stories.first().representative.title)
        assertEquals(2, markets.stories.size)
    }

    @Test
    fun `entities are capped at four`() {
        val content = """{"clusters":[{"topic":"Markets","headline_ids":[1,2,3],"representative":1,"entities":["a","b","c","d","e","f"]}]}"""
        val result = ClusterResponseParser.parse(content, three)
        assertEquals(listOf("a", "b", "c", "d"), result.clusters[0].entities)
    }

    @Test
    fun `missing id fails the partition check`() {
        val content = """{"clusters":[{"topic":"Markets","headline_ids":[1,2],"representative":1,"entities":[]}]}"""
        assertThrows(Exception::class.java) { ClusterResponseParser.parse(content, three) }
    }

    @Test
    fun `duplicate id is rejected`() {
        val content = """{"clusters":[{"topic":"A","headline_ids":[1,2],"representative":1,"entities":[]},{"topic":"B","headline_ids":[2,3],"representative":3,"entities":[]}]}"""
        assertThrows(Exception::class.java) { ClusterResponseParser.parse(content, three) }
    }

    @Test
    fun `out of range id is rejected`() {
        val content = """{"clusters":[{"topic":"A","headline_ids":[1,2,4],"representative":1,"entities":[]}]}"""
        assertThrows(Exception::class.java) { ClusterResponseParser.parse(content, three) }
    }

    @Test
    fun `representative outside its cluster is rejected`() {
        val content = """{"clusters":[{"topic":"A","headline_ids":[1,2,3],"representative":9,"entities":[]}]}"""
        assertThrows(Exception::class.java) { ClusterResponseParser.parse(content, three) }
    }

    @Test
    fun `blank topic is rejected`() {
        val content = """{"clusters":[{"topic":"","headline_ids":[1,2,3],"representative":1,"entities":[]}]}"""
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
