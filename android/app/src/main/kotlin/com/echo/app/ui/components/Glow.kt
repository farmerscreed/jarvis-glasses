package com.echo.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * The design system's "glow instead of shadow": a soft radial halo behind the content
 * (the screens' `box-shadow: 0 0 Npx <color>`). Draws past the bounds; keep the parent unclipped.
 */
fun Modifier.radialGlow(color: Color, radius: Dp, alpha: Float = 0.55f): Modifier = drawBehind {
    val r = radius.toPx() + size.minDimension / 2f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = r,
        ),
        radius = r,
        center = Offset(size.width / 2f, size.height / 2f),
    )
}
