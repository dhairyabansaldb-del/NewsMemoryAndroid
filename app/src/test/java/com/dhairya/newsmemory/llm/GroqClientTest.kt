package com.dhairya.newsmemory.llm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqClientTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** A Groq chat-completions envelope whose assistant content is [content]. */
    private fun envelope(content: String): String =
        Json.encodeToString(ChatResponse(listOf(ChatChoice(ChatMessage("assistant", content)))))

    private fun client(engine: MockEngine): GroqClient =
        GroqClient("test-key", GroqClient.defaultHttpClient(engine), baseUrl = "https://groq.test/")

    /** Returns the GroqException thrown by [block], or fails if none was thrown. */
    private suspend fun expectGroqError(block: suspend () -> Unit): GroqException {
        val caught = try {
            block(); null
        } catch (e: GroqException) {
            e
        }
        assertNotNull("expected a GroqException", caught)
        return caught!!
    }

    @Test
    fun `returns assistant content on 200`() = runTest {
        val clusters = """{"clusters":[]}"""
        val engine = MockEngine { respond(envelope(clusters), HttpStatusCode.OK, jsonHeaders) }

        assertEquals(clusters, client(engine).complete("m", "sys", "usr"))
    }

    @Test
    fun `sends bearer auth header`() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers[HttpHeaders.Authorization]
            respond(envelope("{}"), HttpStatusCode.OK, jsonHeaders)
        }
        client(engine).complete("m", "s", "u")
        assertEquals("Bearer test-key", seenAuth)
    }

    @Test
    fun `retries on 429 then succeeds`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) respond("rate limited", HttpStatusCode.TooManyRequests)
            else respond(envelope("""{"ok":true}"""), HttpStatusCode.OK, jsonHeaders)
        }

        val out = client(engine).complete("m", "s", "u")

        assertEquals(2, calls)
        assertEquals("""{"ok":true}""", out)
    }

    @Test
    fun `retries on 5xx up to the limit then throws`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond("boom", HttpStatusCode.InternalServerError) }

        val ex = expectGroqError { client(engine).complete("m", "s", "u") }

        assertEquals(1 + GroqClient.MAX_RETRIES, calls)   // initial + 2 retries
        assertTrue(ex.message!!.contains("500"))
    }

    @Test
    fun `4xx other than 429 fails immediately without retry`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond("nope", HttpStatusCode.Unauthorized) }

        expectGroqError { client(engine).complete("m", "s", "u") }

        assertEquals(1, calls)
    }

    @Test
    fun `empty choices throws`() = runTest {
        val engine = MockEngine { respond("""{"choices":[]}""", HttpStatusCode.OK, jsonHeaders) }
        expectGroqError { client(engine).complete("m", "s", "u") }
    }
}
