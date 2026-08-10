package com.dhairya.newsmemory.llm.prompts

import com.dhairya.newsmemory.SharedConfig
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

    /**
     * Both come from `shared/` — the prompt text lives in
     * `shared/clustering-prompt-v2.txt` and the version in
     * `shared/pipeline-config.json`, so the offline eval harness
     * (tools/eval_clustering.py) evaluates the exact prompt the app ships.
     * **Edit the shared files, never a copy here.**
     */
    const val VERSION = SharedConfig.PROMPT_VERSION

    val SYSTEM: String = SharedConfig.CLUSTERING_SYSTEM_PROMPT

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

    const val DEFAULT_BODY_CHARS = SharedConfig.DEFAULT_BODY_CHARS
}
