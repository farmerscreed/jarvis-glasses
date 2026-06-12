package com.echo.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.echo.app.R

/**
 * Tri-font strategy from the design system: Space Grotesk (display/headlines), Inter (body),
 * JetBrains Mono (transcripts/timestamps/data). Variable TTFs in res/font; each used weight is
 * pinned with FontVariation so the variable axis actually renders the right weight.
 */

@OptIn(ExperimentalTextApi::class) // FontVariation: stable in behavior, experimental in signature
private fun variableFont(res: Int, weight: FontWeight) =
    Font(res, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

val SpaceGrotesk = FontFamily(
    variableFont(R.font.space_grotesk, FontWeight.Medium),
    variableFont(R.font.space_grotesk, FontWeight.SemiBold),
    variableFont(R.font.space_grotesk, FontWeight.Bold),
)

val InterFont = FontFamily(
    variableFont(R.font.inter, FontWeight.Normal),
    variableFont(R.font.inter, FontWeight.SemiBold),
)

val JetBrainsMono = FontFamily(
    variableFont(R.font.jetbrains_mono, FontWeight.Medium),
)

/** Transcripts, timestamps, data strings — custom style, not an M3 slot. */
val DataMono = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.05.em,
)

/**
 * M3 Typography. The eight styles specified by the design sheet are exact; the remaining M3
 * slots keep Material defaults re-familied (Space Grotesk for display/headline/title, Inter for
 * body/label) so no component ever falls back to Roboto.
 */
private val Default = Typography()

val JarvisTypography = Typography(
    // — exact, from the design sheet —
    displayLarge = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold,
        fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = (-0.02).em,
    ),
    displayMedium = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp, lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 28.sp, lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle( // the sheet's mobile headline
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 24.sp, lineHeight = 32.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFont, fontWeight = FontWeight.Normal,
        fontSize = 18.sp, lineHeight = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFont, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    labelSmall = TextStyle( // ALL-CAPS systemic labels
        fontFamily = InterFont, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.em,
    ),
    // — defaults re-familied —
    displaySmall = Default.displaySmall.copy(fontFamily = SpaceGrotesk),
    headlineSmall = Default.headlineSmall.copy(fontFamily = SpaceGrotesk),
    titleLarge = Default.titleLarge.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium),
    titleMedium = Default.titleMedium.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium),
    titleSmall = Default.titleSmall.copy(fontFamily = InterFont),
    bodySmall = Default.bodySmall.copy(fontFamily = InterFont),
    labelLarge = Default.labelLarge.copy(fontFamily = InterFont, fontWeight = FontWeight.SemiBold),
    labelMedium = Default.labelMedium.copy(fontFamily = InterFont, fontWeight = FontWeight.SemiBold),
)
