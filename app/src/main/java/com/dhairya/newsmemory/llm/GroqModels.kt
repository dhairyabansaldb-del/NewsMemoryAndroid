package com.dhairya.newsmemory.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the Groq chat-completions API (EDD §6) and the clustering output
 * contract (EDD §5.2). The API is OpenAI-compatible.
 */

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ResponseFormat(val type: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    @SerialName("response_format") val responseFormat: ResponseFormat = ResponseFormat("json_object"),
    // Both null by default (omitted from the wire payload — Json.encodeDefaults is false)
    // so older/other models that don't understand these fields are unaffected.
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null
)

@Serializable
data class ChatChoice(val message: ChatMessage)

@Serializable
data class ChatResponse(val choices: List<ChatChoice>)

/**
 * The clustering JSON the model is asked to return (clustering-v2, EDD §5.2):
 * { "clusters": [ { "topic", "headline", "headline_ids":[..], "representative":n, "entities":[..] } ] }
 * headline_ids are 1-based indices into the numbered headline list sent in the prompt.
 * "headline" is a model-synthesized headline covering the whole cluster (added
 * 2026-07-17 — see TopicCluster.headline); older responses without it decode fine,
 * defaulting to "" which the parser treats as absent.
 */
@Serializable
data class LlmClusters(val clusters: List<LlmCluster> = emptyList())

@Serializable
data class LlmCluster(
    val topic: String = "",
    val headline: String = "",
    @SerialName("headline_ids") val headlineIds: List<Int> = emptyList(),
    val representative: Int = -1,
    val entities: List<String> = emptyList()
)
