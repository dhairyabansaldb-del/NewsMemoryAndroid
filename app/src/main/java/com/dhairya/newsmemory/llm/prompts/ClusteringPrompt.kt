package com.dhairya.newsmemory.llm.prompts

import com.dhairya.newsmemory.pipeline.Deduper

/**
 * Versioned clustering prompt (EDD §5.2). Bump [VERSION] on any wording change so a
 * prompt regression is attributable.
 *
 * v2 (2026-07-17) replaced v1 after a two-day live trial showed real quality failures
 * — see the 30-day replay in tools/eval_clustering.py:
 *   1. v1's "same story, or the same closely-related topic" clause let the model build
 *      TOPIC buckets ("all business news") instead of same-EVENT clusters — a 10-item
 *      "Inc42" mega-cluster swallowed six unrelated stories into one card. v2 restricts
 *      clustering to same-event only, with explicit negative examples.
 *   2. v1 could only PICK one input headline as the representative, so a correct 2-source
 *      merge still hid one side's information (e.g. a follow-up quote never shown). v2
 *      adds a "headline" field: a synthesized headline covering the whole cluster,
 *      constrained to facts present in the inputs.
 *   3. Title-only input starved the model on teaser titles ("Five-year low", "Apply or
 *      skip?") and account-name titles (X posts titled just "Inc42"). v2's user message
 *      includes a body snippet per story (buildUser); "headline" instructs the model to
 *      rewrite a vague teaser into something informative using that snippet.
 */
object ClusteringPrompt {

    const val VERSION = "clustering-v2"

    private val TAXONOMY_DEFS = """
        Markets (indices, stocks, FII/DII flows, market moves)
        AI & Agents (artificial intelligence products, models, research, agents)
        IPO (IPOs, listings, fundraising rounds, venture funding)
        Policy (government policy, regulation, courts, RBI decisions)
        Tech (technology companies and products that are not AI)
    """.trimIndent()

    val SYSTEM: String = """
        You are a news-desk editor. You receive a numbered list of news items (headline, and
        sometimes a snippet after an em dash). Group them into clusters of the SAME NEWS EVENT,
        then label and summarise each cluster. Reply with ONLY a JSON object — no prose.

        CLUSTERING RULE — put two items in one cluster ONLY if they report the same underlying
        news event: the same announcement, match, incident, deal, or viral moment. A reader who
        saw one item would learn nothing substantial from the other.
        - Different outlets covering the same event MUST be merged even when worded very
          differently: "RBI holds repo rate steady at 6.5%" and "RBI keeps repo rate unchanged
          in policy review" are the same decision -> one cluster.
        - NEVER group items merely because they share a topic, industry, company type, or
          category: "Zomato tests voice ordering" and "Swiggy raises funding" are both
          food-delivery business news but different events -> separate clusters. "Both are
          markets news" is NOT a reason to merge.
        - Singleton clusters are normal; some windows contain no same-event pairs at all.

        For each cluster:
        - "topic": use one of these canonical topics when the cluster genuinely fits:
        $TAXONOMY_DEFS
          Otherwise write a short 1-3 word topic of your own (e.g. Sports, Entertainment,
          Weather, Crime, World, Politics). Never force an unrelated story into a canonical
          topic, and never use vague labels like "General" or "News".
        - "headline": ONE concise, informative headline (max 16 words) stating the news of
          this cluster. Use ONLY facts present in the provided items — never add numbers,
          names, or claims that are not in the text. When an item's headline is a vague
          teaser ("Five-year low", "Apply or skip?"), rewrite it into an informative
          headline using its snippet. For multi-item clusters, cover the event, not one item.
        - "headline_ids": the input ids in this cluster. Every input id must appear in
          exactly ONE cluster across the whole response — never drop, invent, or repeat ids.
        - "representative": the id whose text best stands for the cluster.
        - "entities": up to 4 recurring trackable subjects (organisations, people,
          instruments, ongoing events) as short canonical names, e.g. "Sensex", "RBI",
          "FII selling", "Jaishankar". Empty array if none.

        Output schema (exactly this shape):
        {"clusters":[{"topic":"Markets","headline":"Sensex falls 800 points as FII selling continues","headline_ids":[1,3],"representative":3,"entities":["Sensex","FII selling"]}]}
    """.trimIndent()

    /**
     * Numbered headline list; ids are 1-based and match [Deduper.MergedStory] order. Each
     * line is "N. title — body snippet" (body omitted when blank or identical to the
     * title) so the model has real content instead of a title alone.
     */
    fun buildUser(stories: List<Deduper.MergedStory>, bodyChars: Int = DEFAULT_BODY_CHARS): String =
        buildString {
            appendLine("Headlines:")
            stories.forEachIndexed { i, story ->
                val rep = story.representative
                val title = (rep.title ?: rep.body ?: "").trim()
                append("${i + 1}. $title")
                val body = rep.body?.trim().orEmpty()
                if (bodyChars > 0 && body.isNotEmpty() && body != title) {
                    append(" — ${body.take(bodyChars)}")
                }
                appendLine()
            }
        }.trimEnd()

    const val DEFAULT_BODY_CHARS = 160
}
