package com.dhairya.newsmemory.llm

import com.dhairya.newsmemory.SharedConfig
import com.dhairya.newsmemory.util.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses the id-keyed entity-extraction response and maps 1-based ids back onto the batch.
 * Pure and side-effect free, so every repair path is unit-testable without a network.
 *
 * REPAIR-based, not reject-based — the hardest-won lesson of Phase 5 (§5.1). Strict
 * validation of the clustering response rejected essentially every real reply and left the
 * pipeline in permanent silent fallback; the same all-or-nothing check here would stall the
 * backfill on a single fumbled id and burn the daily quota re-sending the same batch. So:
 *
 *  - envelope drift ({"items":…} / {"results":…} / a bare list / an id→entities map) → accepted
 *  - out-of-range id → dropped
 *  - duplicate id → first occurrence wins, later ones dropped
 *  - id missing from the response → absent from the map; the caller treats that as zero
 *    entities, which is a legitimate answer for a great many items anyway
 *  - entities as a comma string, or with non-string members → salvaged / members skipped
 *  - blank, punctuation-only or duplicate names → trimmed and deduped on the same
 *    normalized key the `entities` table merges on
 *  - more than [MAX_ENTITIES] → truncated, most-central-first as the prompt orders them
 *
 * A throw is reserved for genuine failure: malformed JSON, or a reply with no usable id in
 * it at all. The caller re-asks once and then gives up WITHOUT advancing the watermark, so
 * the batch is retried later rather than silently skipped.
 */
object EntityResponseParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Envelope keys seen in practice; the documented one is "items". */
    private val ENVELOPE_KEYS = listOf("items", "results", "extractions", "entities")

    private val TRIM_CHARS = charArrayOf(' ', ',', ';', ':', '-', '–', '—', '"', '\'')

    const val MAX_ENTITIES = SharedConfig.ENTITY_MAX_PER_ITEM

    /**
     * @param n batch size; ids outside 1..[n] are dropped.
     * @return 1-based id → entity names, only for ids the model actually answered for.
     * @throws Exception (serialization or [IllegalArgumentException]) when beyond repair.
     */
    fun parse(content: String, n: Int): Map<Int, List<String>> {
        require(n > 0) { "no items to extract from" }
        val rows: List<Pair<JsonElement?, JsonElement?>> = when (val root = json.parseToJsonElement(content)) {
            is JsonArray -> root.map { it.idAndEntities() }
            is JsonObject -> {
                val listed = ENVELOPE_KEYS.firstNotNullOfOrNull { root[it] as? JsonArray }
                when {
                    listed != null -> listed.map { it.idAndEntities() }
                    // An id-keyed object ({"1":["Sensex"],"2":[]}) is the other shape the
                    // model reaches for when it ignores the schema.
                    root.isNotEmpty() && root.keys.all { it.trim().toIntOrNull() != null } ->
                        root.map { (key, value) -> JsonPrimitive(key.trim()) as JsonElement? to value }
                    else -> throw IllegalArgumentException("no id-keyed item list in response")
                }
            }
            else -> throw IllegalArgumentException("no id-keyed item list in response")
        }

        val out = LinkedHashMap<Int, List<String>>(n)
        for ((idElement, entitiesElement) in rows) {
            val id = idElement.asIntOrNull() ?: continue      // unusable row → dropped
            if (id !in 1..n || id in out) continue            // out-of-range / duplicate → dropped
            out[id] = cleanEntities(entitiesElement)
        }

        require(out.isNotEmpty()) { "no usable ids in response" }
        return out
    }

    private fun JsonElement.idAndEntities(): Pair<JsonElement?, JsonElement?> =
        (this as? JsonObject)?.let { it["id"] to it["entities"] } ?: (null to null)

    /** Accepts a number or a numeric string — the model quotes ids about as often as not. */
    private fun JsonElement?.asIntOrNull(): Int? =
        (this as? JsonPrimitive)?.content?.trim()?.toIntOrNull()

    private fun cleanEntities(element: JsonElement?): List<String> {
        val raw: List<String> = when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            // Observed shape drift: the whole list collapsed into one comma-separated string.
            is JsonPrimitive -> if (element.isString) element.content.split(",") else emptyList()
            else -> emptyList()
        }
        val keys = HashSet<String>(raw.size)
        val cleaned = ArrayList<String>(raw.size)
        for (candidate in raw) {
            val name = candidate.split(WHITESPACE).filter { it.isNotEmpty() }
                .joinToString(" ").trim(*TRIM_CHARS)
            // Dedupe on the same key `entities.normalized` merges on, so two spellings that
            // would collapse to one row don't both get linked.
            val key = Normalizer.normalize(name)
            if (key.isEmpty() || !keys.add(key)) continue
            cleaned.add(name)
        }
        return cleaned.take(MAX_ENTITIES)
    }

    private val WHITESPACE = Regex("\\s+")
}
