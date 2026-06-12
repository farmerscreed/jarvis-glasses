package com.echo.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Aetheric Intelligence palette — extracted verbatim from the Stitch screens' shared theme
 * (docs/design/…/aetheric_intelligence/DESIGN.md frontmatter; every screen embeds the same set).
 * Screens are the source of truth; do not invent values here.
 */

// Canvas + tonal surface ladder
val Surface = Color(0xFF101419) // background & surface (and surfaceDim)
val SurfaceBright = Color(0xFF36393F)
val SurfaceContainerLowest = Color(0xFF0A0E13)
val SurfaceContainerLow = Color(0xFF181C21)
val SurfaceContainer = Color(0xFF1C2025)
val SurfaceContainerHigh = Color(0xFF262A30)
val SurfaceContainerHighest = Color(0xFF31353B) // == surface-variant
val OnSurface = Color(0xFFE0E2EA)
val OnSurfaceVariant = Color(0xFFBBC9CE)
val InverseSurface = Color(0xFFE0E2EA)
val InverseOnSurface = Color(0xFF2D3136)
val Outline = Color(0xFF869397)
val OutlineVariant = Color(0xFF3C494D)

// Cyan family — the system's pulse
val Primary = Color(0xFFBBEFFF) // pale cyan: text/icons in primary role
val OnPrimary = Color(0xFF003641)
val PrimaryContainer = Color(0xFF3DDCFF) // "Electric Cyan": active fills, primary buttons
val OnPrimaryContainer = Color(0xFF005E6F)
val InversePrimary = Color(0xFF00687B)
val SurfaceTint = Color(0xFF36D8FB) // also primary-fixed-dim: tint, glow, orb pulse

// Muted blue-grey
val Secondary = Color(0xFFBFC7D7)
val OnSecondary = Color(0xFF29313D)
val SecondaryContainer = Color(0xFF444C59)
val OnSecondaryContainer = Color(0xFFB4BCCC)

// Warm amber family (M3-mapped tertiary)
val Tertiary = Color(0xFFFFE2C4)
val OnTertiary = Color(0xFF472A00)
val TertiaryContainer = Color(0xFFFFBE6F)
val OnTertiaryContainer = Color(0xFF794B00)

// Error
val ErrorColor = Color(0xFFFFB4AB)
val OnError = Color(0xFF690005)
val ErrorContainer = Color(0xFF93000A)
val OnErrorContainer = Color(0xFFFFDAD6)

// Custom (NOT an M3 slot — exposed via LocalJarvisColors)
val PresenceAmber = Color(0xFFFFB454) // offline / on-phone / sync-pending / off-grid ember
val EmberHighlight = Color(0xFFFFE2C4) // off-grid orb radial: highlight → amber → deep
val EmberDeep = Color(0xFF794B00)
