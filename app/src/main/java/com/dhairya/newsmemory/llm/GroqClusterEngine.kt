package com.dhairya.newsmemory.llm

import com.dhairya.newsmemory.llm.prompts.ClusteringPrompt
import com.dhairya.newsmemory.pipeline.ClusterResult
import com.dhairya.newsmemory.pipeline.Deduper
import com.dhairya.newsmemory.pipeline.HeuristicClusterer

/**
 * Stage-3 clustering backed by Groq (EDD §5.2), with the always-available heuristic as a
 * fallback. Plugs into [com.dhairya.newsmemory.pipeline.DigestPipeline] as its
 * `clusterEngine`. Minor id bookkeeping mistakes in the response are repaired by
 * [ClusterResponseParser]; ANY real failure — network, HTTP, malformed JSON, or a model
 * that placed under half the headlines — degrades to [HeuristicClusterer] and the
 * resulting digest is tagged HEURISTIC, so degradation is visible and never silent.
 */
class GroqClusterEngine(
    private val client: GroqClient,
    private val model: String = CLUSTERING_MODEL,
    // Injected so unit tests stay pure (no android.util.Log); AppContainer wires the real one.
    private val log: (String) -> Unit = {}
) {

    suspend fun cluster(stories: List<Deduper.MergedStory>): ClusterResult {
        if (stories.isEmpty()) return ClusterResult(emptyList(), ClusterResult.MODE_HEURISTIC)
        return try {
            val content = client.complete(
                model = model,
                system = ClusteringPrompt.SYSTEM,
                user = ClusteringPrompt.buildUser(stories)
            )
            ClusterResponseParser.parse(content, stories).also {
                log("LLM ok: ${stories.size} stories → ${it.clusters.size} clusters via $model")
            }
        } catch (e: Exception) {
            log("LLM failed (${e.javaClass.simpleName}: ${e.message}) → heuristic fallback")
            HeuristicClusterer.cluster(stories)
        }
    }

    companion object {
        /** Small/fast model for clustering; the v1 one-shot query uses the 70B model (EDD §7.2). */
        const val CLUSTERING_MODEL = "llama-3.1-8b-instant"
    }
}
