package com.dhairya.newsmemory.pipeline

import com.dhairya.newsmemory.util.Normalizer
import java.util.Locale

/**
 * A topic cluster ready for assembly. In heuristic mode entities are empty
 * (backfilled later, EDD §7.3); the LLM path (Phase 5) fills them.
 */
data class TopicCluster(
    val topicLabel: String,
    val stories: List<Deduper.MergedStory>,
    val entities: List<String> = emptyList()
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
            TopicCluster(topicLabel = topTfLabel(story), stories = listOf(story))
        }
        return ClusterResult(clusters, ClusterResult.MODE_HEURISTIC)
    }

    /** Highest-frequency token within the story group; ties broken by length (longer = more specific). */
    private fun topTfLabel(story: Deduper.MergedStory): String {
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
