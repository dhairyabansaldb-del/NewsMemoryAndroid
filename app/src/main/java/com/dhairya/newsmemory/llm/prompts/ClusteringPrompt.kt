package com.dhairya.newsmemory.llm.prompts

import com.dhairya.newsmemory.pipeline.Deduper

/**
 * Versioned clustering prompt (EDD §5.2). Bump [VERSION] on any wording change so a
 * prompt regression is attributable. The prompt assigns each cluster a topic from the
 * Almanac taxonomy when one fits (which lights up the topic-chip colours) and falls back
 * to a short free-form label otherwise; it also names recurring entities — the substrate
 * v1 recurrence counts over.
 */
object ClusteringPrompt {

    const val VERSION = "clustering-v1"

    /** Canonical topics surfaced as coloured chips (see ui/theme topicColor). */
    private val TAXONOMY = listOf("Markets", "AI & Agents", "IPO", "Policy", "Tech")

    val SYSTEM: String = """
        You are a news-desk editor. You are given a numbered list of news headlines and you
        group them into story clusters, then label each cluster. Reply with ONLY a JSON
        object — no prose, no markdown.

        Rules:
        - Put headlines that cover the same story, or the same closely-related topic, in one
          cluster. A headline with no relatives forms its own single-headline cluster.
        - Every input id must appear in exactly one cluster. Never invent ids, never drop an
          id, never repeat an id, and never merge clearly unrelated stories.
        - "representative" is the id of the single clearest, most complete headline in the
          cluster — the one whose wording best stands for the group.
        - "topic" is a concise label. Prefer one of these canonical topics when it genuinely
          fits the cluster: ${TAXONOMY.joinToString(", ")}. Otherwise write your own 2–4 word
          topic label. Do not force an unrelated story into a canonical topic.
        - "entities" are the recurring, trackable subjects of the cluster: organisations,
          people, financial instruments, or ongoing events — as short canonical names
          (e.g. "FII selling", "Sensex", "Jaishankar", "RBI"). At most 4 per cluster. Skip
          generic words. Use an empty array if there are none worth tracking.

        Output schema (exactly this shape):
        {"clusters":[{"topic":"Markets","headline_ids":[1,3],"representative":3,"entities":["Sensex","FII selling"]}]}
    """.trimIndent()

    /** Numbered headline list; ids are 1-based and match [Deduper.MergedStory] order. */
    fun buildUser(stories: List<Deduper.MergedStory>): String = buildString {
        appendLine("Headlines:")
        stories.forEachIndexed { i, story ->
            val headline = story.representative.title ?: story.representative.body ?: ""
            appendLine("${i + 1}. $headline")
        }
    }.trimEnd()
}
