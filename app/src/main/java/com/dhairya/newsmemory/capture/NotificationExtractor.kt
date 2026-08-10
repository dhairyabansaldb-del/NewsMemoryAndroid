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
 *
 * A publisher/account name is NEVER promoted to a headline: a title that is only a source
 * name with no body behind it is UNPARSEABLE, whatever the package (see [looksLikeSourceName]).
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

    /**
     * A bare source name is at most this many tokens ("Inc42", "The Verge", "Business Standard").
     * The cap only *admits* a title to the source-name check — a verb-shaped token or a
     * lowercase content word still rescues it, so terse-but-real headlines ("Trump signs
     * order") survive.
     */
    private const val SOURCE_NAME_MAX_TOKENS = 3

    /** Punctuation a written sentence carries and a bare publisher/account name does not. */
    private const val SENTENCE_PUNCT = ".,:;!?\"'—–"

    /**
     * Verbs, auxiliaries and clause markers common in terse headlines that carry no
     * -s/-ed/-ing marker. Only ever used to *spare* a title from the source-name check, so
     * over-inclusion is safe and under-inclusion is the risk.
     */
    private val VERB_HINTS = setOf(
        "to", "will", "may", "can", "cut", "hit", "set", "win", "buy", "say", "sue", "ban",
        "beat", "quit", "urge", "warn", "hike", "sell", "fall", "rise", "jump", "drop",
        "plan", "eye", "seek", "open", "close", "end", "back", "up", "down",
        "amid", "after", "over", "against", "as", "vs", "on", "in", "at", "for"
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
        // real headline lives in the text — using the title would show "inshorts". Same when
        // the title merely repeats the publisher the notification itself declares in
        // EXTRA_SUB_TEXT: that is a source name, never a headline.
        val titleNorm = Normalizer.normalize(input.title)
        val titleIsSourceName = input.title.isNullOrBlank() || (titleNorm.isNotEmpty() &&
            (titleNorm == Normalizer.normalize(input.appLabel) ||
                titleNorm == Normalizer.normalize(input.subText)))

        // Shape: aggregator (title = publisher) OR title is just the app/publisher name →
        // headline is in the body
        if (aggregator || titleIsSourceName) {
            val headline = firstNonBlank(input.text, input.bigText)
            if (headline != null && !isJunk(headline)) {
                val publisher = if (aggregator) firstNonBlank(input.title, input.subText) ?: input.appLabel
                else firstNonBlank(input.subText) ?: input.appLabel
                return listOf(item(input, headline, snippet = null, publisher = publisher, ParseQuality.FULL))
            }
            // No usable body. The title is only the publisher/app name, never a headline,
            // so we don't fabricate one from it — mark unparseable.
            val publisher = if (aggregator) firstNonBlank(input.title, input.subText) ?: input.appLabel
            else firstNonBlank(input.subText) ?: input.appLabel
            return listOf(item(input, input.title ?: input.appLabel, null, publisher, ParseQuality.UNPARSEABLE))
        }

        // Shape: standard — title is a real headline
        val headline = firstNonBlank(input.title, input.text, input.bigText)
        if (headline == null || isJunk(headline)) {
            return listOf(
                item(input, headline ?: input.appLabel, null, firstNonBlank(input.subText) ?: input.appLabel, ParseQuality.UNPARSEABLE)
            )
        }
        val body = firstNonBlank(input.bigText, input.text)
            ?.takeIf { !isJunk(it) && !it.equalsNorm(headline) }
        // Generalisation of the aggregator rule above: a title that is only a source name
        // (a publisher, an app, an @account — e.g. an X post whose title is just "Inc42")
        // with nothing behind it is not a story. Both conditions are required — a real body
        // means the row carries content, and a name-shaped title alone is not enough —
        // because silently dropping a real story is worse than admitting a junk row.
        if (body == null && looksLikeSourceName(headline)) {
            return listOf(
                item(input, headline, null, firstNonBlank(input.subText, input.title) ?: input.appLabel, ParseQuality.UNPARSEABLE)
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

    /**
     * True when a title reads as a bare publisher/account/app name rather than a headline.
     * Deliberately conservative — every branch below demands more than one signal except the
     * single-token case (no news push is one word), and any verb-shaped or lowercase content
     * token vetoes the whole check.
     */
    private fun looksLikeSourceName(title: String): Boolean {
        if (title.any { it in SENTENCE_PUNCT }) return false
        val tokens = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > SOURCE_NAME_MAX_TOKENS) return false
        // One word is never a headline: an @handle, an account name or an app/publisher name.
        if (tokens.size == 1) return true
        if (tokens.any { looksVerbal(it) }) return false
        // Names are written in title case throughout; headlines keep verbs/objects lowercase.
        return tokens.all { !it.first().isLetter() || it.first().isUpperCase() }
    }

    /** Cheap morphology: does this token behave like a headline verb? Errs towards yes. */
    private fun looksVerbal(token: String): Boolean {
        val t = Normalizer.normalize(token)
        if (t.isEmpty()) return false
        if (t in VERB_HINTS) return true
        if (t.length >= 5 && (t.endsWith("ed") || t.endsWith("ing"))) return true
        return t.length >= 4 && t.endsWith("s") && !t.endsWith("ss")
    }

    private fun firstNonBlank(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun isJunk(s: String): Boolean = Normalizer.normalize(s).let { it.isEmpty() || it in JUNK }

    private fun String.equalsNorm(other: String): Boolean =
        Normalizer.normalize(this) == Normalizer.normalize(other)
}
