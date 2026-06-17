package com.dhairya.newsmemory.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhairya.newsmemory.ui.theme.LocalAlmanac
import com.dhairya.newsmemory.ui.theme.PillLabel
import com.dhairya.newsmemory.ui.theme.monogram
import com.dhairya.newsmemory.ui.theme.topicColor
import kotlin.math.abs

/** Deterministic source-chip palette (handoff §Source monogram). */
private val MonogramPalette = listOf(
    0xFF3FA37A, 0xFF5B8DEF, 0xFFD85B4A, 0xFFE0A23B, 0xFFC2476B,
    0xFFE8702A, 0xFF8C6BD8, 0xFF3AA6B9, 0xFF6B7280, 0xFFC0392B
)

fun monogramColor(key: String): Color =
    Color(MonogramPalette[abs(key.hashCode()) % MonogramPalette.size])

private fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/** Rounded-square source chip (stand-in for app logos; handoff §Source monogram chip). */
@Composable
fun SourceMonogram(label: String, size: Int = 30, modifier: Modifier = Modifier) {
    val color = monogramColor(label)
    val radius = (size * 0.32f).dp
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(radius))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials(label),
            style = monogram(size * 0.36),
            color = color,
            textAlign = TextAlign.Center
        )
    }
}

/** Topic pill: light bg + ink text (light theme) / dark+15% bg + dark text (dark). */
@Composable
fun TopicPill(topic: String, modifier: Modifier = Modifier) {
    val dark = LocalAlmanac.current.isDark
    val tc = topicColor(topic, dark)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(tc.pillBg)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(topic.uppercase(), style = PillLabel.copy(fontSize = 10.sp), color = tc.pillText)
    }
}

/** Content card: fill + hairline border, no shadow (handoff §Shape & spacing). */
@Composable
fun AlmanacCard(
    modifier: Modifier = Modifier,
    fill: Color? = null,
    border: Boolean = true,
    radius: Int = 18,
    content: @Composable () -> Unit
) {
    val a = LocalAlmanac.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(radius.dp),
        color = fill ?: a.card,
        border = if (border) BorderStroke(1.dp, a.line) else null,
        content = content
    )
}
