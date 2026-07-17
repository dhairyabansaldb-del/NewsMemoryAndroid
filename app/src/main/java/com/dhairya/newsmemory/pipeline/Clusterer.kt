package com.dhairya.newsmemory.pipeline

import com.dhairya.newsmemory.util.Normalizer
import java.util.Locale

/**
 * A topic cluster ready for assembly. In heuristic mode entities are empty
 * (backfilled later, EDD §7.3); the LLM path (Phase 5) fills them.
 *
 * [headline] is a model-synthesized headline covering the whole cluster (clustering-v2,
 * 2026-07-17) — null in heuristic mode, or when the LLM didn't supply one, in which case
 * assembly (DigestPipeline) falls back to the representative story's own title/body.
 * Before this field existed the digest always showed one input's raw headline verbatim,
 * which silently hid the other side of a 2-source merge (e.g. only the SC-quote half of
 * a Samay Raina story, never the follow-up) — the synthesized headline covers the event.
 */
data class TopicCluster(
    val topicLabel: String,
    val stories: List<Deduper.MergedStory>,
    val entities: List<String> = emptyList(),
    val headline: String? = null
)

/** Result of stage 3: clusters + which pipeline produced them. */
data class ClusterResult(val clusters: List<TopicCluster>, val mode: String) {
    companion object {
        const val MODE_LLM = "LLM"
        const val MODE_HEURISTIC = "HEURISTIC"
    }
}

/**
 * Stage 3b — heuristic fallback clustering (EDD §5.2).
 * Each merged story becomes its own cluster; the topic label is the highest-TF
 * meaningful token across the window, capitalized. Crude but honest — and the
 * digest row records HEURISTIC so degradation is visible, never silent.
 */
object HeuristicClusterer {

    fun cluster(stories: List<Deduper.MergedStory>): ClusterResult {
        val clusters = stories.map { story ->
            TopicCluster(topicLabel = labelFor(story), stories = listOf(story))
        }
        return ClusterResult(clusters, ClusterResult.MODE_HEURISTIC)
    }

    /** Highest-frequency token within the story group; ties broken by length (longer = more specific). */
    fun labelFor(story: Deduper.MergedStory): String {
        val counts = mutableMapOf<String, Int>()
        for (member in story.members) {
            for (token in Normalizer.titleTokens(member.title ?: member.body)) {
                counts[token] = (counts[token] ?: 0) + 1
            }
        }
        val top = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { it.key.length })
            .firstOrNull()?.key
        return top?.replaceFirstChar { it.titlecase(Locale.ROOT) } ?: "General"
    }
}
