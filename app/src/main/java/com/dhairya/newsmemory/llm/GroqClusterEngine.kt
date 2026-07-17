package com.dhairya.newsmemory.llm

import com.dhairya.newsmemory.llm.prompts.ClusteringPrompt
import com.dhairya.newsmemory.pipeline.ClusterResult
import com.dhairya.newsmemory.pipeline.Deduper
import com.dhairya.newsmemory.pipeline.HeuristicClusterer

/**
 * Stage-3 clustering backed by Groq (EDD §5.2), with the always-available heuristic as a
 * fallback. Plugs into [com.dhairya.newsmemory.pipeline.DigestPipeline] as its
 * `clusterEngine`. Minor id bookkeeping mistakes in the response are repaired by
 * [ClusterResponseParser]; a malformed/unparseable response gets ONE re-ask (a fresh
 * call — the model is non-deterministic enough that a retry often succeeds); any other
 * failure — network, HTTP, or a model that placed under half the headlines even after
 * a re-ask — degrades to [HeuristicClusterer] and the resulting digest is tagged
 * HEURISTIC, so degradation is visible and never silent.
 *
 * Model is `openai/gpt-oss-120b` (2026-07-17; replaced llama-3.1-8b-instant, which Groq
 * is deprecating and which a 30-day replay showed producing topical mega-clusters even
 * when the prompt forbade it — see ClusteringPrompt's v2 changelog).
 */
class GroqClusterEngine(
    private val client: GroqClient,
    private val model: String = CLUSTERING_MODEL,
    private val reasoningEffort: String? = REASONING_EFFORT,
    // Injected so unit tests stay pure (no android.util.Log); AppContainer wires the real one.
    private val log: (String) -> Unit = {}
) {

    suspend fun cluster(stories: List<Deduper.MergedStory>): ClusterResult {
        if (stories.isEmpty()) return ClusterResult(emptyList(), ClusterResult.MODE_HEURISTIC)

        // The free tier pre-counts input + max_completion_tokens against a per-minute
        // token cap, so a single large digest (many stories + body snippets) can be
        // rejected outright unless the completion budget — and if needed the body
        // snippet length — shrinks to fit. One call per digest, so this only needs to
        // fit once, not pace across calls the way the offline eval harness does.
        var bodyChars = ClusteringPrompt.DEFAULT_BODY_CHARS
        var user = ClusteringPrompt.buildUser(stories, bodyChars)
        var maxTokens = completionBudget(user)
        while (maxTokens < MIN_COMPLETION_TOKENS && bodyChars > 0) {
            bodyChars = (bodyChars - 40).coerceAtLeast(0)
            user = ClusteringPrompt.buildUser(stories, bodyChars)
            maxTokens = completionBudget(user)
        }

        var lastError: Exception? = null
        repeat(PARSE_ATTEMPTS) { attempt ->
            val content = try {
                client.complete(model, ClusteringPrompt.SYSTEM, user, maxTokens, reasoningEffort)
            } catch (e: Exception) {
                // Network/HTTP failure — GroqClient already retried internally; re-asking
                // immediately after that would just repeat the same failure.
                log("LLM call failed (${e.javaClass.simpleName}: ${e.message}) → heuristic fallback")
                return HeuristicClusterer.cluster(stories)
            }
            try {
                return ClusterResponseParser.parse(content, stories).also {
                    val suffix = if (attempt > 0) " (reask)" else ""
                    log("LLM ok: ${stories.size} stories → ${it.clusters.size} clusters via $model$suffix")
                }
            } catch (e: Exception) {
                lastError = e
                log("LLM response invalid, attempt ${attempt + 1}/$PARSE_ATTEMPTS " +
                    "(${e.javaClass.simpleName}: ${e.message})")
            }
        }
        log("LLM response still invalid after $PARSE_ATTEMPTS attempt(s) " +
            "(${lastError?.message}) → heuristic fallback")
        return HeuristicClusterer.cluster(stories)
    }

    private fun completionBudget(user: String): Int =
        (TPM_BUDGET - estTokens(ClusteringPrompt.SYSTEM) - estTokens(user)).coerceAtLeast(0)

    private fun estTokens(text: String): Int = text.length / 4 + 1

    companion object {
        const val CLUSTERING_MODEL = "openai/gpt-oss-120b"
        const val REASONING_EFFORT = "low"

        // Free-tier TPM for openai/gpt-oss-120b is 8000, pre-counting input +
        // max_completion_tokens; budgeted with margin under the hard cap since token
        // estimation (chars/4) is approximate.
        private const val TPM_BUDGET = 6500
        private const val MIN_COMPLETION_TOKENS = 1500
        private const val PARSE_ATTEMPTS = 2
    }
}
