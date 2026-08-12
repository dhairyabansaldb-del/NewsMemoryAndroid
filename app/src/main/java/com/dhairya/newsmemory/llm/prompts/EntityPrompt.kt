package com.dhairya.newsmemory.llm.prompts

import com.dhairya.newsmemory.SharedConfig
import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.util.Normalizer

/**
 * Versioned entity-extraction prompt (EDD §7.3). The prompt text and its version both come
 * from `shared/` — `shared/entity-prompt-v1.txt` and `shared/entity-config.json` — so the
 * offline harness (tools/eval_entities.py) evaluates the exact prompt the app ships.
 * **Edit the shared files, never a copy here.**
 *
 * The user message is a deliberate mirror of that harness's `build_user`/`pick_snippet`:
 * same numbered format, same em-dash join, same snippet choice. A divergence here would
 * make the 30-day offline gate measure a prompt the backfill doesn't send — the exact
 * failure `shared/` exists to prevent (§7).
 *
 * Note what is NOT sent: `digest_items.topic_label`. Heuristic-mode digests label items
 * with a single top-TF token ("Dropping", "Kishans"), and that is precisely the junk the
 * prompt is built to reject — feeding it in would seed the model with it.
 */
object EntityPrompt {

    const val VERSION = SharedConfig.ENTITY_PROMPT_VERSION

    val SYSTEM: String = SharedConfig.ENTITY_SYSTEM_PROMPT

    const val DEFAULT_BODY_CHARS = SharedConfig.ENTITY_BODY_CHARS

    /** One item to extract from: a digest item's id, its headline, and added body text. */
    data class Item(val id: Long, val headline: String, val snippet: String)

    /**
     * Numbered item list; ids are 1-based positions in [items] and are what the response
     * keys on. Each line is "N. headline — snippet" (snippet omitted when blank).
     */
    fun buildUser(items: List<Item>, bodyChars: Int = DEFAULT_BODY_CHARS): String =
        buildString {
            appendLine("Items:")
            items.forEachIndexed { i, item ->
                append("${i + 1}. ${item.headline}")
                if (bodyChars > 0 && item.snippet.isNotEmpty()) {
                    append(" — ${item.snippet.take(bodyChars)}")
                }
                appendLine()
            }
        }.trimEnd()

    /**
     * The body text that best ADDS to a headline, chosen across every raw notification
     * behind the item.
     *
     * §5.1: 36% of captured titles are ≤3 words, so headline-only extraction is nearly
     * blind — the same starvation that made clustering-v1 misgroup. Prefer the longest
     * source body that isn't the headline again; fall back to a source title when every
     * body is empty (common for Dailyhunt-shaped rows, whose headline lives in EXTRA_TEXT).
     * Longest wins regardless of which row or field it came from: the point is maximum
     * added information, not provenance.
     */
    fun pickSnippet(headline: String, sources: List<RawNotification>): String {
        val headlineNorm = Normalizer.normalize(headline)
        var best = ""
        for (row in sources) {
            for (text in listOf(clean(row.body), clean(row.title))) {
                if (text.isEmpty()) continue
                val norm = Normalizer.normalize(text)
                if (norm.isEmpty() || norm == headlineNorm || headlineNorm.contains(norm)) continue
                if (text.length > best.length) best = text
            }
        }
        return best
    }

    /** Newlines would break the one-line-per-item numbering the response keys on. */
    fun clean(text: String?): String = (text ?: "").replace('\n', ' ').replace('\r', ' ').trim()
}
