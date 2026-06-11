package com.dhairya.newsmemory.util

import java.security.MessageDigest

/**
 * Text normalization + content hashing (EDD §4.2):
 * content_hash = sha256(normalize(title) + "|" + normalize(body))
 * normalize = lowercase, collapse whitespace, strip emoji/punctuation.
 */
object Normalizer {

    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val sb = StringBuilder(text.length)
        for (cp in text.lowercase().codePoints()) {
            when {
                Character.isLetterOrDigit(cp) -> sb.appendCodePoint(cp)
                Character.isWhitespace(cp) -> sb.append(' ')
                // punctuation, symbols, emoji → dropped
            }
        }
        return sb.toString().trim().replace(Regex(" +"), " ")
    }

    fun contentHash(title: String?, body: String?): String {
        val input = normalize(title) + "|" + normalize(body)
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Title tokens with stopwords removed — input to the Jaccard near-dup merge (EDD §5.2). */
    fun titleTokens(title: String?): Set<String> =
        normalize(title).split(' ').filter { it.length > 1 && it !in STOPWORDS }.toSet()

    private val STOPWORDS = setOf(
        "a", "an", "the", "of", "in", "on", "at", "to", "for", "by", "with", "from",
        "and", "or", "but", "as", "is", "are", "was", "were", "be", "been", "has",
        "have", "had", "will", "would", "could", "should", "may", "might", "its",
        "it", "this", "that", "these", "those", "after", "before", "over", "under",
        "up", "down", "out", "off", "amid", "vs", "via", "into", "about", "than",
        "not", "no", "new", "say", "says", "said"
    )
}
