package com.dhairya.newsmemory.llm

import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.llm.prompts.EntityPrompt
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extraction quality is not testable here and these tests do not pretend otherwise — that is
 * what `tools/eval_entities.py` and a month of real captured items are for (§5.1: a clustering
 * prompt that passed every unit test still produced two days of digests worse than the
 * heuristic). What IS testable, and what actually broke Phase 5, is the plumbing around the
 * model: whether a fumbled response is repaired or thrown away, and whether failure is loud.
 *
 * So: every repair path, every failure path, and the prompt contract. No network — Ktor
 * MockEngine throughout, same as GroqClusterEngineTest.
 */
class EntityExtractorTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun envelope(content: String): String =
        Json.encodeToString(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", content)))))

    private var nextId = 100L

    private fun item(headline: String, topic: String = "Dropping") = DigestItem(
        id = nextId++, digestId = "2026-06-10-E", topicLabel = topic,
        headline = headline, sourceCount = 1, position = 0
    )

    private val items = listOf(
        item("Sensex falls on FII selling"),
        item("Apple unveils new MacBook"),
        item("Five-year low")
    )

    /** An extractor whose single mock call replies with [content]. */
    private fun extractor(
        content: String,
        sourcesFor: suspend (Long) -> List<RawNotification> = { emptyList() }
    ) = EntityExtractor(
        GroqClient(
            "k",
            GroqClient.defaultHttpClient(MockEngine { respond(envelope(content), HttpStatusCode.OK, jsonHeaders) }),
            "https://groq.test/"
        ),
        sourcesFor = sourcesFor
    )

    /** Returns the exception thrown by [block], or fails if none was. */
    private suspend fun expectThrow(block: suspend () -> Unit): Exception {
        val caught = try {
            block(); null
        } catch (e: Exception) {
            e
        }
        assertNotNull("expected the extraction to throw", caught)
        return caught!!
    }

    // ---- the happy path ------------------------------------------------------------------

    @Test
    fun `a clean response maps ids onto digest item ids`() = runTest {
        val content = """{"items":[
            {"id":1,"entities":["Sensex","FII selling"]},
            {"id":2,"entities":["Apple","MacBook"]},
            {"id":3,"entities":[]}]}"""

        val out = extractor(content).extract(items)

        assertEquals(listOf("Sensex", "FII selling"), out[items[0].id])
        assertEquals(listOf("Apple", "MacBook"), out[items[1].id])
        assertEquals(emptyList<String>(), out[items[2].id])
    }

    @Test
    fun `every item extracting to zero is a success, not a failure`() = runTest {
        val content = """{"items":[{"id":1,"entities":[]},{"id":2,"entities":[]},{"id":3,"entities":[]}]}"""

        val out = extractor(content).extract(items)

        assertEquals(3, out.size)
        assertTrue(out.values.all { it.isEmpty() })
    }

    // ---- repair paths --------------------------------------------------------------------

    @Test
    fun `an out-of-range id is dropped and the rest survive`() = runTest {
        val content = """{"items":[{"id":1,"entities":["Sensex"]},{"id":9,"entities":["Ghost"]}]}"""

        val out = extractor(content).extract(items)

        assertEquals(1, out.size)
        assertEquals(listOf("Sensex"), out[items[0].id])
        assertFalse(out.values.any { it.contains("Ghost") })
    }

    @Test
    fun `a duplicated id keeps the first answer`() = runTest {
        val content = """{"items":[{"id":1,"entities":["Sensex"]},{"id":1,"entities":["Nifty"]}]}"""

        assertEquals(listOf("Sensex"), extractor(content).extract(items)[items[0].id])
    }

    @Test
    fun `an id the model never answers for is absent, which reads as zero entities`() = runTest {
        val content = """{"items":[{"id":1,"entities":["Sensex"]},{"id":2,"entities":["Apple"]}]}"""

        val out = extractor(content).extract(items)

        assertEquals(2, out.size)
        assertNull(out[items[2].id])
        assertEquals(emptyList<String>(), out[items[2].id].orEmpty())
    }

    @Test
    fun `more than four entities are capped, keeping the most central first`() = runTest {
        val content = """{"items":[{"id":1,"entities":["Sensex","Nifty","RBI","SEBI","FII selling","Rupee"]}]}"""

        assertEquals(
            listOf("Sensex", "Nifty", "RBI", "SEBI"),
            extractor(content).extract(items)[items[0].id]
        )
    }

    @Test
    fun `blank, punctuation-only and non-string names are dropped`() = runTest {
        val content = """{"items":[{"id":1,"entities":["  ","—",42,null,"  Sensex  ","Nifty,"]}]}"""

        assertEquals(listOf("Sensex", "Nifty"), extractor(content).extract(items)[items[0].id])
    }

    @Test
    fun `names that collapse to one entity row are deduped`() = runTest {
        // "Sensex", "sensex" and "Sensex!" all normalize to the same `entities.normalized`
        // key, so linking all three would be three links to one row.
        val content = """{"items":[{"id":1,"entities":["Sensex","sensex","Sensex!"]}]}"""

        assertEquals(listOf("Sensex"), extractor(content).extract(items)[items[0].id])
    }

    @Test
    fun `a comma string instead of a list is salvaged`() = runTest {
        val content = """{"items":[{"id":1,"entities":"Sensex, FII selling"}]}"""

        assertEquals(listOf("Sensex", "FII selling"), extractor(content).extract(items)[items[0].id])
    }

    @Test
    fun `envelope drift is tolerated - bare list`() = runTest {
        val content = """[{"id":1,"entities":["Sensex"]},{"id":2,"entities":[]}]"""

        assertEquals(listOf("Sensex"), extractor(content).extract(items)[items[0].id])
    }

    @Test
    fun `envelope drift is tolerated - results key`() = runTest {
        val content = """{"results":[{"id":2,"entities":["Apple"]}]}"""

        assertEquals(listOf("Apple"), extractor(content).extract(items)[items[1].id])
    }

    @Test
    fun `envelope drift is tolerated - id-keyed object`() = runTest {
        val content = """{"1":["Sensex"],"2":[],"3":["Nifty"]}"""

        val out = extractor(content).extract(items)

        assertEquals(listOf("Sensex"), out[items[0].id])
        assertEquals(listOf("Nifty"), out[items[2].id])
    }

    @Test
    fun `a quoted id is still an id`() = runTest {
        val content = """{"items":[{"id":"1","entities":["Sensex"]}]}"""

        assertEquals(listOf("Sensex"), extractor(content).extract(items)[items[0].id])
    }

    // ---- failure paths (must throw, so the watermark is held) ------------------------------

    @Test
    fun `malformed json is re-asked once and then throws`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond(envelope("this is not json"), HttpStatusCode.OK, jsonHeaders) }
        val extractor = EntityExtractor(GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/"))

        expectThrow { extractor.extract(items) }

        assertEquals(2, calls)
    }

    @Test
    fun `a malformed response gets one re-ask that succeeds`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            val body = if (calls == 1) "not json" else """{"items":[{"id":1,"entities":["Sensex"]}]}"""
            respond(envelope(body), HttpStatusCode.OK, jsonHeaders)
        }
        val out = EntityExtractor(
            GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/")
        ).extract(items)

        assertEquals(2, calls)
        assertEquals(listOf("Sensex"), out[items[0].id])
    }

    @Test
    fun `a response with no usable id throws rather than reporting zero entities`() = runTest {
        // Valid JSON, no ids in range: recording that as "no item has a subject" would burn
        // the whole batch permanently, since the watermark would advance past it.
        val content = """{"items":[{"id":77,"entities":["Ghost"]}]}"""

        expectThrow { extractor(content).extract(items) }
    }

    @Test
    fun `a network failure throws without an extra re-ask`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond("down", HttpStatusCode.InternalServerError) }

        expectThrow {
            EntityExtractor(
                GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/")
            ).extract(items)
        }

        // GroqClient's own retry already fired 1 + MAX_RETRIES; re-asking on top would triple it.
        assertEquals(1 + GroqClient.MAX_RETRIES, calls)
    }

    // ---- degenerate input ------------------------------------------------------------------

    @Test
    fun `an empty batch never touches the network`() = runTest {
        var called = false
        val engine = MockEngine { called = true; respond(envelope("{}"), HttpStatusCode.OK, jsonHeaders) }

        val out = EntityExtractor(
            GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/")
        ).extract(emptyList())

        assertTrue(out.isEmpty())
        assertFalse(called)
    }

    @Test
    fun `a batch of blank headlines never touches the network`() = runTest {
        var called = false
        val engine = MockEngine { called = true; respond(envelope("{}"), HttpStatusCode.OK, jsonHeaders) }

        val out = EntityExtractor(
            GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/")
        ).extract(listOf(item(" "), item("")))

        assertTrue(out.isEmpty())
        assertFalse(called)
    }

    // ---- the prompt contract ---------------------------------------------------------------

    @Test
    fun `the prompt carries headline plus body snippet and never the topic label`() = runTest {
        var user = ""
        val engine = MockEngine { req ->
            user = sentUser(req.body)
            respond(envelope("""{"items":[{"id":1,"entities":[]}]}"""), HttpStatusCode.OK, jsonHeaders)
        }
        val teaser = item("Five-year low", topic = "Kishans")
        val extractor = EntityExtractor(
            GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/"),
            sourcesFor = { listOf(raw(title = "Five-year low", body = "Rupee hits a five-year low against the dollar")) }
        )

        extractor.extract(listOf(teaser))

        assertEquals("Items:\n1. Five-year low — Rupee hits a five-year low against the dollar", user)
        // topic_label is junk on heuristic digests — exactly what this prompt rejects (§5.1).
        assertFalse(user.contains("Kishans"))
    }

    @Test
    fun `a snippet that only repeats the headline is not sent`() = runTest {
        var user = ""
        val engine = MockEngine { req ->
            user = sentUser(req.body)
            respond(envelope("""{"items":[{"id":1,"entities":[]}]}"""), HttpStatusCode.OK, jsonHeaders)
        }
        EntityExtractor(
            GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/"),
            sourcesFor = { listOf(raw(title = "Sensex falls", body = "Sensex falls!")) }
        ).extract(listOf(item("Sensex falls")))

        assertEquals("Items:\n1. Sensex falls", user)
    }

    @Test
    fun `the request uses the configured model, effort and a positive token budget`() = runTest {
        var seen: ChatRequest? = null
        val engine = MockEngine { req ->
            seen = decodeRequest(req.body)
            respond(envelope("""{"items":[{"id":1,"entities":[]}]}"""), HttpStatusCode.OK, jsonHeaders)
        }

        EntityExtractor(
            GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/")
        ).extract(items)

        assertEquals(EntityExtractor.ENTITY_MODEL, seen?.model)
        assertEquals(EntityExtractor.ENTITY_EFFORT, seen?.reasoningEffort)
        assertEquals(EntityPrompt.SYSTEM, seen?.messages?.first()?.content)
        assertTrue((seen?.maxCompletionTokens ?: 0) > 0)
    }

    @Test
    fun `a default-sized batch never needs its snippets shrunk`() = runTest {
        var user = ""
        var maxTokens: Int? = null
        val engine = MockEngine { req ->
            val request = decodeRequest(req.body)
            user = request.messages.last().content
            maxTokens = request.maxCompletionTokens
            respond(envelope("""{"items":[{"id":1,"entities":[]}]}"""), HttpStatusCode.OK, jsonHeaders)
        }
        val batch = (1..12).map { item("Story $it") }
        val body = "y".repeat(400)
        EntityExtractor(
            GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/"),
            sourcesFor = { listOf(raw(title = "t", body = body)) }
        ).extract(batch)

        // BATCH_SIZE is chosen so the whole reservation fits — that is the config's own
        // arithmetic (~1530 input + ~1240 completion), and this pins it.
        assertEquals(EntityPrompt.DEFAULT_BODY_CHARS, user.lines()[1].substringAfter("— ").length)
        assertEquals(400 + 70 * 12, maxTokens)
    }

    @Test
    fun `an oversized batch shrinks its snippets to leave room to answer in`() = runTest {
        var user = ""
        var maxTokens: Int? = null
        val engine = MockEngine { req ->
            val request = decodeRequest(req.body)
            user = request.messages.last().content
            maxTokens = request.maxCompletionTokens
            respond(envelope("""{"items":[{"id":1,"entities":[]}]}"""), HttpStatusCode.OK, jsonHeaders)
        }
        // Far past BATCH_SIZE: input + the completion the answer needs no longer fit the
        // per-call token budget, so snippets shrink rather than the batch silently truncating.
        val fat = (1..100).map { item("Story $it") }
        val body = "y".repeat(400)
        EntityExtractor(
            GroqClient("k", GroqClient.defaultHttpClient(engine), "https://groq.test/"),
            sourcesFor = { listOf(raw(title = "t", body = body)) }
        ).extract(fat)

        val snippet = user.lines()[1].substringAfter("— ")
        assertTrue("snippet should have shrunk, was ${snippet.length}", snippet.length < EntityPrompt.DEFAULT_BODY_CHARS)
        assertTrue("shrinking stops at 40 chars", snippet.length >= 40)
        // Still a real answer budget, never zero or negative.
        assertTrue((maxTokens ?: 0) >= 800)
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private fun raw(title: String?, body: String?) = RawNotification(
        id = 1, packageName = "com.news", title = title, body = body,
        contentHash = "h", postedAt = 0, capturedAt = 0, windowBucket = "2026-06-10-E"
    )

    private fun decodeRequest(body: Any): ChatRequest {
        val bytes = (body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
        return Json.decodeFromString(bytes.decodeToString())
    }

    private fun sentUser(body: Any): String = decodeRequest(body).messages.last().content
}
