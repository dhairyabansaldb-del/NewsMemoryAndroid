package com.dhairya.newsmemory.llm

import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.pipeline.ClusterResult
import com.dhairya.newsmemory.pipeline.Deduper
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqClusterEngineTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private var nextId = 1L
    private fun story(title: String): Deduper.MergedStory {
        val raw = RawNotification(
            id = nextId++, packageName = "com.news", title = title, body = null,
            contentHash = title, postedAt = 0, capturedAt = 0, windowBucket = "2026-06-10-E"
        )
        return Deduper.MergedStory(raw, listOf(raw), 1)
    }

    private val stories = listOf(
        story("Sensex falls on FII selling"),
        story("Apple unveils new MacBook"),
        story("FII selling drags Sensex lower")
    )

    /** Builds an engine wired to return [content] as the assistant message at HTTP 200. */
    private fun engineReturning(content: String) = MockEngine {
        val envelope = Json.encodeToString(
            ChatResponse(listOf(ChatChoice(ChatMessage("assistant", content))))
        )
        respond(envelope, HttpStatusCode.OK, jsonHeaders)
    }

    private fun engine(content: String): GroqClusterEngine =
        GroqClusterEngine(
            GroqClient("k", GroqClient.defaultHttpClient(engineReturning(content)), "https://groq.test/")
        )

    @Test
    fun `valid response yields an LLM result`() = runTest {
        val content = """{"clusters":[
            {"topic":"Markets","headline_ids":[1,3],"representative":3,"entities":["Sensex"]},
            {"topic":"Tech","headline_ids":[2],"representative":2,"entities":[]}]}"""

        val result = engine(content).cluster(stories)

        assertEquals(ClusterResult.MODE_LLM, result.mode)
        assertEquals(2, result.clusters.size)
        assertEquals("Markets", result.clusters[0].topicLabel)
        assertEquals(listOf("Sensex"), result.clusters[0].entities)
    }

    @Test
    fun `malformed json falls back to heuristic`() = runTest {
        val result = engine("this is not json").cluster(stories)
        assertEquals(ClusterResult.MODE_HEURISTIC, result.mode)
        // Heuristic = one cluster per merged story.
        assertEquals(stories.size, result.clusters.size)
    }

    @Test
    fun `response with a dropped id is repaired and stays LLM`() = runTest {
        // Model forgot story 3 — the parser gives it a singleton cluster instead of rejecting.
        val content = """{"clusters":[{"topic":"Markets","headline_ids":[1,2],"representative":1,"entities":[]}]}"""
        val result = engine(content).cluster(stories)
        assertEquals(ClusterResult.MODE_LLM, result.mode)
        assertEquals(2, result.clusters.size)
        assertEquals(stories.size, result.clusters.sumOf { it.stories.size })
    }

    @Test
    fun `response covering under half the stories falls back to heuristic`() = runTest {
        val content = """{"clusters":[{"topic":"Markets","headline_ids":[1],"representative":1,"entities":[]}]}"""
        val result = engine(content).cluster(stories)
        assertEquals(ClusterResult.MODE_HEURISTIC, result.mode)
        assertEquals(stories.size, result.clusters.size)
    }

    @Test
    fun `http error falls back to heuristic`() = runTest {
        val failing = GroqClusterEngine(
            GroqClient(
                "k",
                GroqClient.defaultHttpClient(MockEngine { respond("down", HttpStatusCode.InternalServerError) }),
                "https://groq.test/"
            )
        )
        val result = failing.cluster(stories)
        assertEquals(ClusterResult.MODE_HEURISTIC, result.mode)
        assertEquals(stories.size, result.clusters.size)
    }

    @Test
    fun `empty input short-circuits without calling the network`() = runTest {
        var called = false
        val neverCalled = GroqClusterEngine(
            GroqClient(
                "k",
                GroqClient.defaultHttpClient(MockEngine { called = true; respond("{}", HttpStatusCode.OK, jsonHeaders) }),
                "https://groq.test/"
            )
        )
        val result = neverCalled.cluster(emptyList())
        assertEquals(ClusterResult.MODE_HEURISTIC, result.mode)
        assertTrue(result.clusters.isEmpty())
        assertEquals(false, called)
    }

    @Test
    fun `malformed response gets one reask that succeeds`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            val body = if (calls == 1) "not valid json at all"
            else """{"clusters":[{"topic":"Markets","headline_ids":[1,2,3],"representative":1,"entities":[]}]}"""
            respond(
                Json.encodeToString(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", body))))),
                HttpStatusCode.OK, jsonHeaders
            )
        }
        val result = GroqClusterEngine(
            GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/")
        ).cluster(stories)

        assertEquals(2, calls)
        assertEquals(ClusterResult.MODE_LLM, result.mode)
    }

    @Test
    fun `malformed response on both attempts falls back to heuristic after exactly two calls`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                Json.encodeToString(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", "still not json"))))),
                HttpStatusCode.OK, jsonHeaders
            )
        }
        val result = GroqClusterEngine(
            GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/")
        ).cluster(stories)

        assertEquals(2, calls)
        assertEquals(ClusterResult.MODE_HEURISTIC, result.mode)
    }

    @Test
    fun `network failure falls back without an extra reask call`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond("down", HttpStatusCode.InternalServerError) }
        val result = GroqClusterEngine(
            GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/")
        ).cluster(stories)

        // GroqClient's own internal retry (429/5xx) already fired 1 + MAX_RETRIES calls;
        // the engine must not additionally re-ask on top of a network/HTTP failure.
        assertEquals(1 + GroqClient.MAX_RETRIES, calls)
        assertEquals(ClusterResult.MODE_HEURISTIC, result.mode)
    }

    @Test
    fun `synthesized headline flows through to the cluster`() = runTest {
        val content = """{"clusters":[{"topic":"Markets","headline":"Sensex slides as FII selling continues","headline_ids":[1,3],"representative":3,"entities":[]}]}"""
        val result = engine(content).cluster(stories)
        assertEquals("Sensex slides as FII selling continues", result.clusters[0].headline)
    }

    @Test
    fun `request uses the configured model and reasoning effort`() = runTest {
        var seenModel: String? = null
        var seenEffort: String? = null
        val engine = MockEngine { req ->
            val bytes = (req.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
            val body = Json.decodeFromString<ChatRequest>(bytes.decodeToString())
            seenModel = body.model
            seenEffort = body.reasoningEffort
            respond(
                Json.encodeToString(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", """{"clusters":[{"topic":"Markets","headline_ids":[1,2,3],"representative":1,"entities":[]}]}"""))))),
                HttpStatusCode.OK, jsonHeaders
            )
        }
        GroqClusterEngine(
            GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/")
        ).cluster(stories)

        assertEquals(GroqClusterEngine.CLUSTERING_MODEL, seenModel)
        assertEquals(GroqClusterEngine.REASONING_EFFORT, seenEffort)
    }
}
