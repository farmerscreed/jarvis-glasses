package com.echo.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * Colors the design system needs but M3's ColorScheme has no slot for. Read via
 * `JarvisTheme.colors` inside themed content — never hardcode these in screens.
 */
@Immutable
data class JarvisColors(
    /** Offline / on-phone / sync-pending status, and the off-grid orb ember. */
    val presenceAmber: Color = PresenceAmber,
    val onPresenceAmber: Color = OnTertiary, // dark text that reads on amber (tertiary's pair)
    /** Off-grid orb radial gradient: highlight → ember → deep. */
    val emberHighlight: Color = EmberHighlight,
    val emberDeep: Color = EmberDeep,
    /** Cyan used for glows/pulses (the screens' surface-tint / primary-fixed-dim). */
    val glowCyan: Color = SurfaceTint,
)

val LocalJarvisColors = staticCompositionLocalOf { JarvisColors() }

private val ColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    inversePrimary = InversePrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    background = Surface,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceContainerHighest,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceTint = SurfaceTint,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    error = ErrorColor,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Color.Black,
    surfaceBright = SurfaceBright,
    surfaceDim = Surface,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainerLowest = SurfaceContainerLowest,
)

/**
 * The Companion Console theme ("Aetheric Intelligence"). Dark-only by design — the system is
 * built for OLED/waveguide and every Stitch screen is dark; there is no light variant.
 */
@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalJarvisColors provides JarvisColors()) {
        MaterialTheme(
            colorScheme = ColorScheme,
            typography = JarvisTypography,
            shapes = JarvisShapes,
            content = content,
        )
    }
}

/** Accessors for the non-M3 pieces, mirroring the MaterialTheme object idiom. */
object JarvisTheme {
    val colors: JarvisColors
        @Composable get() = LocalJarvisColors.current

    /** Transcripts, timestamps, data strings (JetBrains Mono). */
    val dataMono: TextStyle
        @Composable get() = DataMono
}
