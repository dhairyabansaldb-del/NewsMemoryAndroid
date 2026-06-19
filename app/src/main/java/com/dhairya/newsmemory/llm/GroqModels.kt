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
    @SerialName("response_format") val responseFormat: ResponseFormat = ResponseFormat("json_object")
)

@Serializable
data class ChatChoice(val message: ChatMessage)

@Serializable
data class ChatResponse(val choices: List<ChatChoice>)

/**
 * The clustering JSON the model is asked to return (EDD §5.2):
 * { "clusters": [ { "topic", "headline_ids":[..], "representative":n, "entities":[..] } ] }
 * headline_ids are 1-based indices into the numbered headline list sent in the prompt.
 */
@Serializable
data class LlmClusters(val clusters: List<LlmCluster> = emptyList())

@Serializable
data class LlmCluster(
    val topic: String = "",
    @SerialName("headline_ids") val headlineIds: List<Int> = emptyList(),
    val representative: Int = -1,
    val entities: List<String> = emptyList()
)
