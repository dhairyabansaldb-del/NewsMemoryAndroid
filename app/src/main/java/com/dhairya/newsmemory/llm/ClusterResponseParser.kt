package com.dhairya.newsmemory.llm

import com.dhairya.newsmemory.pipeline.ClusterResult
import com.dhairya.newsmemory.pipeline.Deduper
import com.dhairya.newsmemory.pipeline.TopicCluster
import kotlinx.serialization.json.Json

/**
 * Parses + validates the Groq clustering response against the EDD §5.2 contract and maps
 * 1-based headline ids back onto the merged stories. Pure and side-effect free so it can
 * be unit-tested without a network. ANY validation failure throws — the caller
 * (GroqClusterEngine) treats a throw as "fall back to heuristic", so a bad LLM response is
 * never trusted and never crashes the pipeline.
 *
 * The core invariant is the EDD's: the returned ids must *partition* the input set — every
 * headline appears in exactly one cluster, no extras, no omissions, no duplicates.
 */
object ClusterResponseParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** @throws Exception (serialization or [IllegalArgumentException]) on any malformed/invalid response. */
    fun parse(content: String, stories: List<Deduper.MergedStory>): ClusterResult {
        require(stories.isNotEmpty()) { "no stories to cluster" }
        val parsed = json.decodeFromString<LlmClusters>(content)
        require(parsed.clusters.isNotEmpty()) { "no clusters returned" }

        val n = stories.size
        val seen = HashSet<Int>(n)

        val clusters = parsed.clusters.map { c ->
            require(c.topic.isNotBlank()) { "blank topic" }
            require(c.headlineIds.isNotEmpty()) { "empty cluster" }
            require(c.representative in c.headlineIds) {
                "representative ${c.representative} not in ${c.headlineIds}"
            }
            for (id in c.headlineIds) {
                require(id in 1..n) { "headline id $id out of range 1..$n" }
                require(seen.add(id)) { "duplicate headline id $id" }
            }
            // Representative story first — DigestPipeline reads stories.first() as the headline.
            val members = c.headlineIds
                .sortedByDescending { it == c.representative }
                .map { stories[it - 1] }
            TopicCluster(
                topicLabel = c.topic.trim(),
                stories = members,
                entities = c.entities.map { it.trim() }.filter { it.isNotEmpty() }.take(4)
            )
        }

        require(seen.size == n) { "ids do not partition input: covered ${seen.size} of $n" }
        return ClusterResult(clusters, ClusterResult.MODE_LLM)
    }
}
