@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.dhairya.newsmemory.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dhairya.newsmemory.R

private fun variable(weight: Int) =
    FontVariation.Settings(FontVariation.weight(weight))

/** Display / headings — Newsreader serif (weight 500), letter-spacing −0.01em. */
val Newsreader = FontFamily(
    Font(R.font.newsreader_variable, FontWeight.Normal, variationSettings = variable(400)),
    Font(R.font.newsreader_variable, FontWeight.Medium, variationSettings = variable(500)),
    Font(R.font.newsreader_variable, FontWeight.SemiBold, variationSettings = variable(600)),
    Font(R.font.newsreader_variable, FontWeight.Bold, variationSettings = variable(700))
)

/** UI / body — Instrument Sans. */
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_variable, FontWeight.Normal, variationSettings = variable(400)),
    Font(R.font.instrument_sans_variable, FontWeight.Medium, variationSettings = variable(500)),
    Font(R.font.instrument_sans_variable, FontWeight.SemiBold, variationSettings = variable(600)),
    Font(R.font.instrument_sans_variable, FontWeight.Bold, variationSettings = variable(700))
)

/** Source monogram chips — Space Grotesk 600. */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_variable, FontWeight.Medium, variationSettings = variable(500)),
    Font(R.font.space_grotesk_variable, FontWeight.SemiBold, variationSettings = variable(600)),
    Font(R.font.space_grotesk_variable, FontWeight.Bold, variationSettings = variable(700))
)

// --- Named styles from the handoff (sizes are exact) ---

fun display(size: Int) = TextStyle(
    fontFamily = Newsreader, fontWeight = FontWeight.Medium,
    fontSize = size.sp, letterSpacing = (-0.01).em
)

val Eyebrow = TextStyle(
    fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold,
    fontSize = 10.5.sp, letterSpacing = 0.14.em
)

fun body(size: Double, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = InstrumentSans, fontWeight = weight, fontSize = size.sp
)

val PillLabel = TextStyle(
    fontFamily = InstrumentSans, fontWeight = FontWeight.Bold,
    fontSize = 10.sp, letterSpacing = 0.1.em
)

fun monogram(size: Double) = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = size.sp
)

@Suppress("unused")
val Underlined = TextDecoration.Underline
