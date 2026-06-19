package com.dhairya.newsmemory.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.io.IOException

class GroqException(message: String) : IOException(message)

/**
 * Ktor client for the Groq chat-completions API (EDD §6). Timeout 30 s; retry ×2 with
 * exponential backoff on 429/5xx and network errors; any failure after retries throws.
 * Callers turn a throw into a heuristic fallback — Groq is never on the critical path.
 *
 * The HttpClient is injectable so tests drive it with Ktor's MockEngine (no network).
 */
class GroqClient(
    private val apiKey: String,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val baseUrl: String = "https://api.groq.com/openai/v1/chat/completions"
) {

    /** @return the assistant message content (a JSON string when response_format=json_object). */
    suspend fun complete(model: String, system: String, user: String): String {
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage("system", system), ChatMessage("user", user))
        )

        var attempt = 0
        while (true) {
            val response: HttpResponse = try {
                httpClient.post(baseUrl) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            } catch (e: IOException) {
                if (attempt >= MAX_RETRIES) throw e
                attempt++; delay(backoffMillis(attempt)); continue
            }

            val code = response.status.value
            when {
                code in 200..299 ->
                    return response.body<ChatResponse>().choices.firstOrNull()?.message?.content
                        ?: throw GroqException("Groq response had no choices")

                (code == 429 || code in 500..599) && attempt < MAX_RETRIES -> {
                    attempt++; delay(backoffMillis(attempt))
                }

                else -> throw GroqException("Groq HTTP $code")
            }
        }
    }

    companion object {
        const val MAX_RETRIES = 2
        const val TIMEOUT_MS = 30_000L

        /** 1st retry ~500 ms, 2nd ~1 s. Under runTest the virtual clock makes this instant. */
        private fun backoffMillis(attempt: Int): Long = 500L * (1L shl (attempt - 1))

        fun defaultHttpClient(engine: HttpClientEngine? = null): HttpClient {
            val block: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(HttpTimeout) {
                    requestTimeoutMillis = TIMEOUT_MS
                    connectTimeoutMillis = TIMEOUT_MS
                    socketTimeoutMillis = TIMEOUT_MS
                }
            }
            return if (engine != null) HttpClient(engine, block) else HttpClient(CIO, block)
        }
    }
}
