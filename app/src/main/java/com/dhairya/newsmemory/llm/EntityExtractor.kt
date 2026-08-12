package com.dhairya.newsmemory.llm

import com.dhairya.newsmemory.SharedConfig
import com.dhairya.newsmemory.data.db.DigestItem
import com.dhairya.newsmemory.data.db.RawNotification
import com.dhairya.newsmemory.llm.prompts.EntityPrompt

/**
 * Extracts canonical recurring subjects for a batch of digest items (EDD §7.3) — the
 * extraction half of [com.dhairya.newsmemory.memory.EntityBackfill]. Structurally the twin
 * of [GroqClusterEngine]: same client, same token budgeting, same one-re-ask-then-give-up
 * shape, same repair-based parsing.
 *
 * The one deliberate difference is what failure means. Clustering has the heuristic to fall
 * back to, so it degrades and returns. There is no heuristic entity extractor — a headline
 * can't be turned into countable canonical subjects by counting words, that's the whole
 * reason this calls an LLM — so failure THROWS. The backfill turns that into "leave the
 * watermark alone and retry this batch next hour", which is the point of the watermark.
 *
 * Items are enriched with a body snippet through [sourcesFor] before being sent: one small
 * indexed query per item, against one network call per batch. §5.1 — 36% of captured titles
 * are ≤3 words, so headline-only extraction is nearly blind, and a blind extraction is not
 * cheaper than a query, it is just wrong more often.
 */
class EntityExtractor(
    private val client: GroqClient,
    /** Raw notifications behind one digest item; `DigestDao::sourcesFor` in production. */
    private val sourcesFor: suspend (Long) -> List<RawNotification> = { emptyList() },
    private val model: String = ENTITY_MODEL,
    private val reasoningEffort: String? = ENTITY_EFFORT,
    // Injected so unit tests stay pure (no android.util.Log); AppContainer wires the real one.
    private val log: (String) -> Unit = {}
) {

    /**
     * @return digest_item id → entity names. Ids the model answered for with nothing map to
     *   an empty list, and ids it never answered for are absent — both mean "no recurring
     *   subject", which is a correct and common answer, never a failure.
     * @throws Exception on a network/HTTP failure, or a response still unparseable after
     *   one re-ask. Never returns partial silence as success.
     */
    suspend fun extract(items: List<DigestItem>): Map<Long, List<String>> {
        if (items.isEmpty()) return emptyMap()

        // A blank headline has nothing to extract from, so it is not worth a line in the
        // prompt (the offline harness skips those rows for the same reason). It still
        // counts as attempted upstream — the backfill advances past it either way.
        val inputs = items.mapNotNull { item ->
            val headline = EntityPrompt.clean(item.headline)
            if (headline.isEmpty()) null
            else EntityPrompt.Item(item.id, headline, EntityPrompt.pickSnippet(headline, sourcesFor(item.id)))
        }
        if (inputs.isEmpty()) return emptyMap()

        // The free tier pre-counts input + max_completion_tokens against a per-minute token
        // cap, so the completion budget is derived from the real prompt size and snippets
        // shrink when a batch would otherwise leave no room to answer in. Mirrors
        // eval_entities.plan_batch exactly — a divergence would make the offline gate
        // measure a different request than the one the app sends.
        val want = maxOf(COMPLETION_BASE + COMPLETION_PER_ITEM * inputs.size, MIN_COMPLETION_TOKENS)
        var bodyChars = EntityPrompt.DEFAULT_BODY_CHARS
        var user = EntityPrompt.buildUser(inputs, bodyChars)
        var room = ENTITY_TPM_BUDGET - estTokens(EntityPrompt.SYSTEM) - estTokens(user)
        while (room < want && bodyChars > MIN_BODY_CHARS) {
            bodyChars -= BODY_CHARS_STEP
            user = EntityPrompt.buildUser(inputs, bodyChars)
            room = ENTITY_TPM_BUDGET - estTokens(EntityPrompt.SYSTEM) - estTokens(user)
        }
        // Still short = a pathological batch (huge snippets); the reservation stays well
        // under the hard cap, so send it and let truncation show rather than drop items.
        val maxTokens = maxOf(minOf(want, room), MIN_COMPLETION_TOKENS)

        var lastError: Exception? = null
        repeat(PARSE_ATTEMPTS) { attempt ->
            val content = try {
                client.complete(model, EntityPrompt.SYSTEM, user, maxTokens, reasoningEffort)
            } catch (e: Exception) {
                // GroqClient already retried internally; re-asking would repeat the failure.
                log("entity call failed (${e.javaClass.simpleName}: ${e.message})")
                throw e
            }
            try {
                val byIndex = EntityResponseParser.parse(content, inputs.size)
                val byId = LinkedHashMap<Long, List<String>>(inputs.size)
                inputs.forEachIndexed { i, input -> byIndex[i + 1]?.let { byId[input.id] = it } }
                val suffix = if (attempt > 0) " (reask)" else ""
                val unanswered = inputs.size - byId.size
                log(
                    "entities ok: ${inputs.size} items → ${byId.values.sumOf { it.size }} names, " +
                        "$unanswered unanswered via $model$suffix"
                )
                return byId
            } catch (e: Exception) {
                lastError = e
                log("entity response invalid, attempt ${attempt + 1}/$PARSE_ATTEMPTS " +
                    "(${e.javaClass.simpleName}: ${e.message})")
            }
        }
        throw GroqException(
            "entity response still invalid after $PARSE_ATTEMPTS attempt(s): ${lastError?.message}"
        )
    }

    private fun estTokens(text: String): Int = text.length / 4 + 1

    companion object {
        // Model, effort and every token-budget number come from shared/entity-config.json so
        // the offline harness measures the configuration the app ships.
        const val ENTITY_MODEL = SharedConfig.ENTITY_MODEL
        const val ENTITY_EFFORT = SharedConfig.ENTITY_EFFORT

        private const val ENTITY_TPM_BUDGET = SharedConfig.ENTITY_TPM_BUDGET
        private const val COMPLETION_BASE = SharedConfig.ENTITY_COMPLETION_BASE
        private const val COMPLETION_PER_ITEM = SharedConfig.ENTITY_COMPLETION_PER_ITEM
        private const val MIN_COMPLETION_TOKENS = SharedConfig.ENTITY_MIN_COMPLETION_TOKENS

        // App-internal, no harness counterpart beyond the shrink loop's own shape.
        private const val PARSE_ATTEMPTS = 2
        private const val BODY_CHARS_STEP = 40
        private const val MIN_BODY_CHARS = 40
    }
}
