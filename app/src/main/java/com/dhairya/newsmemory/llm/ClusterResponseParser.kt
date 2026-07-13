package com.dhairya.newsmemory.llm

import com.dhairya.newsmemory.pipeline.ClusterResult
import com.dhairya.newsmemory.pipeline.Deduper
import com.dhairya.newsmemory.pipeline.HeuristicClusterer
import com.dhairya.newsmemory.pipeline.TopicCluster
import kotlinx.serialization.json.Json

/**
 * Parses the Groq clustering response (EDD §5.2 contract) and maps 1-based headline ids
 * back onto the merged stories. Pure and side-effect free so it can be unit-tested
 * without a network.
 *
 * Validation is REPAIR-based, not reject-based. Found on-device (2026-06-19): at real
 * digest sizes (~30 headlines) the 8B model reliably fumbles id bookkeeping — duplicate
 * ids, an id dropped — while the grouping itself is good. The original all-or-nothing
 * partition check therefore rejected essentially every real response and the pipeline
 * lived in permanent heuristic fallback. Instead we salvage:
 *
 *  - duplicate id → first cluster wins, later occurrences dropped
 *  - out-of-range id → dropped
 *  - representative not in its cluster → first surviving id becomes representative
 *  - blank topic → heuristic top-TF label for that cluster
 *  - id missing from every cluster → its own singleton cluster (a story is never lost)
 *
 * A throw still means "fall back to heuristic", reserved for real failure: malformed
 * JSON, an empty cluster list, or the model placing fewer than half the headlines —
 * that's "didn't do the job", not "fumbled the bookkeeping".
 */
object ClusterResponseParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** @throws Exception (serialization or [IllegalArgumentException]) when the response is beyond repair. */
    fun parse(content: String, stories: List<Deduper.MergedStory>): ClusterResult {
        require(stories.isNotEmpty()) { "no stories to cluster" }
        val parsed = json.decodeFromString<LlmClusters>(content)
        require(parsed.clusters.isNotEmpty()) { "no clusters returned" }

        val n = stories.size
        val seen = HashSet<Int>(n)

        val clusters = parsed.clusters.mapNotNull { c ->
            // Repair pass: drop out-of-range ids and ids already claimed by an earlier cluster.
            val ids = c.headlineIds.filter { it in 1..n && seen.add(it) }
            if (ids.isEmpty()) return@mapNotNull null
            val repId = if (c.representative in ids) c.representative else ids.first()
            // Representative story first — DigestPipeline reads stories.first() as the headline.
            val members = ids.sortedByDescending { it == repId }.map { stories[it - 1] }
            TopicCluster(
                topicLabel = c.topic.trim().ifEmpty { HeuristicClusterer.labelFor(members.first()) },
                stories = members,
                entities = c.entities.map { it.trim() }.filter { it.isNotEmpty() }.take(4)
            )
        }

        // Coverage floor: fumbled bookkeeping is repairable, but a model that placed fewer
        // than half the headlines fundamentally failed — heuristic does better than that.
        require(seen.size * 2 >= n) { "model placed only ${seen.size} of $n headlines" }

        // Unplaced ids become singleton clusters so no captured story vanishes from the digest.
        val orphans = (1..n).filter { it !in seen }.map { id ->
            val story = stories[id - 1]
            TopicCluster(topicLabel = HeuristicClusterer.labelFor(story), stories = listOf(story))
        }

        return ClusterResult(clusters + orphans, ClusterResult.MODE_LLM)
    }
}
