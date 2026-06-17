package com.dhairya.newsmemory.capture

import com.dhairya.newsmemory.util.Normalizer

/** How confidently we resolved a real headline (drives the allowlist "limited support" flag). */
enum class ParseQuality { FULL, PARTIAL, UNPARSEABLE }

/**
 * One story resolved from a notification. A single notification can yield several
 * (InboxStyle bundles). [title]/[snippet] are the RESOLVED headline + secondary text the
 * rest of the pipeline consumes; the raw fields are preserved so Groq and any future
 * re-derivation have the full payload.
 */
data class ExtractedItem(
    val title: String,            // resolved headline
    val snippet: String?,         // resolved secondary line
    val publisher: String,        // source within the app (e.g. "The Verge"), else app label
    val rawTitle: String?,
    val rawText: String?,
    val bigText: String?,
    val subText: String?,
    val textLines: String?,       // joined with "\n" when InboxStyle
    val template: String?,
    val quality: ParseQuality
)

/**
 * Notification-shape classifier + extractor (Phase A). News apps use at least four shapes
 * and the old "title ?: text" logic showed publisher/app names instead of headlines:
 *   - standard BigText  → headline in EXTRA_TITLE, expansion in EXTRA_BIG_TEXT/TEXT
 *   - aggregator        → EXTRA_TITLE is the PUBLISHER, headline is in EXTRA_TEXT (Google News)
 *   - inbox multi-line  → EXTRA_TEXT_LINES holds several headlines in one notification
 *   - custom-view       → standard extras near-empty (e.g. Inshorts) → UNPARSEABLE → flagged
 */
object NotificationExtractor {

    /** Packages where EXTRA_TITLE is the publisher and the headline lives in EXTRA_TEXT. */
    private val AGGREGATOR_PACKAGES = setOf(
        "com.google.android.apps.magazines"   // Google News (calibrated against live dump)
    )

    /** Placeholder bodies some apps post instead of a real headline — never store these. */
    private val JUNK = setOf(
        "you have a notification", "new message", "new notification", "1 new message"
    )

    data class Input(
        val packageName: String,
        val appLabel: String,
        val title: String?,
        val text: String?,
        val bigText: String?,
        val subText: String?,
        val infoText: String?,
        val summaryText: String?,
        val textLines: List<String>,
        val template: String?
    )

    fun extract(input: Input): List<ExtractedItem> {
        val lines = input.textLines.map { it.trim() }.filter { it.isNotBlank() }

        // Shape: inbox / multi-line bundle → one story per line
        if (lines.size >= 2 || input.template?.contains("InboxStyle") == true && lines.isNotEmpty()) {
            val publisher = firstNonBlank(input.subText, input.title) ?: input.appLabel
            return lines.map { line ->
                item(input, title = line, snippet = null, publisher = publisher, quality = ParseQuality.FULL)
            }
        }

        val aggregator = input.packageName in AGGREGATOR_PACKAGES
        // Inshorts/Dailyhunt-style: the "title" is the app's own name (or absent), so the
        // real headline lives in the text — using the title would show "inshorts".
        val titleIsAppName = input.title.isNullOrBlank() ||
            Normalizer.normalize(input.title!!).let { it.isNotEmpty() && it == Normalizer.normalize(input.appLabel) }

        // Shape: aggregator (title = publisher) OR title is just the app name → headline is in the body
        if (aggregator || titleIsAppName) {
            val headline = firstNonBlank(input.text, input.bigText)
            if (headline != null && !isJunk(headline)) {
                val publisher = if (aggregator) firstNonBlank(input.title, input.subText) ?: input.appLabel
                else firstNonBlank(input.subText) ?: input.appLabel
                return listOf(item(input, headline, snippet = null, publisher = publisher, ParseQuality.FULL))
            }
            // No usable body. The title is only the publisher/app name, never a headline,
            // so we don't fabricate one from it — mark unparseable.
            val publisher = if (aggregator) firstNonBlank(input.title, input.subText) ?: input.appLabel
            else input.appLabel
            return listOf(item(input, input.title ?: input.appLabel, null, publisher, ParseQuality.UNPARSEABLE))
        }

        // Shape: standard — title is a real headline
        val headline = firstNonBlank(input.title, input.text, input.bigText)
        if (headline == null || isJunk(headline)) {
            return listOf(
                item(input, headline ?: input.appLabel, null, firstNonBlank(input.subText) ?: input.appLabel, ParseQuality.UNPARSEABLE)
            )
        }
        val snippet = firstNonBlank(input.bigText, input.text)?.takeIf { !it.equalsNorm(headline) }
        val publisher = firstNonBlank(input.subText) ?: input.appLabel
        return listOf(item(input, headline, snippet, publisher, ParseQuality.FULL))
    }

    private fun item(
        input: Input,
        title: String,
        snippet: String?,
        publisher: String,
        quality: ParseQuality
    ) = ExtractedItem(
        title = title.trim(),
        snippet = snippet?.trim()?.takeIf { it.isNotBlank() },
        publisher = publisher.trim(),
        rawTitle = input.title,
        rawText = input.text,
        bigText = input.bigText,
        subText = input.subText,
        textLines = input.textLines.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        template = input.template,
        quality = quality
    )

    private fun firstNonBlank(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun isJunk(s: String): Boolean = Normalizer.normalize(s).let { it.isEmpty() || it in JUNK }

    private fun String.equalsNorm(other: String): Boolean =
        Normalizer.normalize(this) == Normalizer.normalize(other)
}
