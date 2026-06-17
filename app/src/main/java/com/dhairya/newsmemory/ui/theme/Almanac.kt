package com.dhairya.newsmemory.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The "Almanac" design tokens (handoff §Design Tokens). These extend Material's roles
 * with the warm, paper-like palette the design needs; components read them via
 * [LocalAlmanac]. Two themes: light ("Almanac") and after-dark.
 */
@Immutable
data class AlmanacColors(
    val isDark: Boolean,
    val bg: Color,
    val card: Color,
    val card2: Color,
    val tintA: Color,
    val tintB: Color,
    val ink: Color,
    val inkMed: Color,
    val inkLow: Color,
    val faint: Color,
    val line: Color,
    val line2: Color,
    val hero: Color,
    val heroInk: Color,
    val heroTag: Color,
    val blockB: Color,
    val accent: Color,
    val accentInk: Color,
    val highlightBg: Color,
    val good: Color,
    val warn: Color,
    val chipActiveBg: Color,
    val chipActiveInk: Color
)

val AlmanacLight = AlmanacColors(
    isDark = false,
    bg = Color(0xFFF6F0E4),
    card = Color(0xFFFCF9F0),
    card2 = Color(0xFFFCF9F0),
    tintA = Color(0xFFEEE3CD),
    tintB = Color(0xFFE4E8D7),
    ink = Color(0xFF1A150F),
    inkMed = Color(0x991A150F),
    inkLow = Color(0x6B1A150F),
    faint = Color(0x421A150F),
    line = Color(0x1F1A150F),
    line2 = Color(0x331A150F),
    hero = Color(0xFFF2C75B),
    heroInk = Color(0xFF1A150F),
    heroTag = Color(0xFFB8431F),
    blockB = Color(0xFFA9C8DD),
    accent = Color(0xFFB8431F),
    accentInk = Color(0xFFFCF9F0),
    highlightBg = Color(0xFFF2C75B),
    good = Color(0xFF5E7C3F),
    warn = Color(0xFFB8431F),
    chipActiveBg = Color(0xFF1A150F),
    chipActiveInk = Color(0xFFFCF9F0)
)

val AlmanacDark = AlmanacColors(
    isDark = true,
    bg = Color(0xFF1A1611),
    card = Color(0xFF241E17),
    card2 = Color(0xFF2E261C),
    tintA = Color(0xFF2A2316),
    tintB = Color(0xFF242820),
    ink = Color(0xFFF4ECDC),
    inkMed = Color(0x9EF4ECDC),
    inkLow = Color(0x6BF4ECDC),
    faint = Color(0x3DF4ECDC),
    line = Color(0x1AF4ECDC),
    line2 = Color(0x29F4ECDC),
    hero = Color(0xFFE0B24E),
    heroInk = Color(0xFF1A1611),
    heroTag = Color(0xFF1A1611),
    blockB = Color(0xFF7FA0B8),
    accent = Color(0xFFE8A24E),
    accentInk = Color(0xFF1A1611),
    highlightBg = Color(0x4DF0C75B),
    good = Color(0xFF9CBE7A),
    warn = Color(0xFFE8A24E),
    chipActiveBg = Color(0xFFE8A24E),
    chipActiveInk = Color(0xFF1A1611)
)

val LocalAlmanac = staticCompositionLocalOf { AlmanacLight }

/** Topic color-coding (handoff §Topic color-coding): pill background + text color. */
data class TopicColor(val pillBg: Color, val pillText: Color)

fun topicColor(topic: String, dark: Boolean): TopicColor {
    val t = topic.lowercase()
    // light, ink (text on light), dark
    val (light, ink, darkC) = when {
        listOf("market", "sensex", "nifty", "stock", "fii").any { t.contains(it) } ->
            Triple(0xFFA9C8DD, 0xFF3C6B8C, 0xFF86AAC4)
        listOf("ai", "agent", "tech", "robot", "chatgpt").any { t.contains(it) } ->
            Triple(0xFFF2C75B, 0xFF946E12, 0xFFE0B24E)
        listOf("ipo", "funding", "raise").any { t.contains(it) } ->
            Triple(0xFFCBD8A4, 0xFF5E7C3F, 0xFFA6C283)
        listOf("policy", "rbi", "govt", "court", "ban").any { t.contains(it) } ->
            Triple(0xFFEAB79B, 0xFFB5562F, 0xFFDA9A78)
        else -> Triple(0xFFD8CDBA, 0xFF7A6A4F, 0xFFBCAE93)
    }
    return if (dark) TopicColor(Color(darkC).copy(alpha = 0.15f), Color(darkC))
    else TopicColor(Color(light), Color(ink))
}
