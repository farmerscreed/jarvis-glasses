package com.echo.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.echo.app.ui.theme.JarvisTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val TWO_PI = (2 * Math.PI).toFloat()
private const val DEG = (Math.PI / 180.0).toFloat()

/**
 * The Presence Orb's cognitive states (design sheet "Presence Orb Core States"):
 * Idle — passive awareness, breathing core · Listening — acoustic capture, ripple feedback ·
 * Thinking — synaptic processing, orbital particles · Speaking — vocal projection, waveform pulse ·
 * Deliberating — high-load compute, flux energy · OffGrid — local storage only, amber ember.
 */
enum class OrbState { Idle, Listening, Thinking, Speaking, Deliberating, OffGrid }

/**
 * The visual heartbeat of the app — a Canvas orb animated per [state]. Stitch supplied stills;
 * the motion (periods, amplitudes) follows each state's caption and the ember CSS keyframes
 * (scale 0.98–1.05 flicker). Drive it from real assistant state; never animate it manually.
 */
@Composable
fun PresenceOrb(
    state: OrbState,
    modifier: Modifier = Modifier.size(176.dp),
) {
    val scheme = MaterialTheme.colorScheme
    val extras = JarvisTheme.colors

    Crossfade(targetState = state, label = "orbState") { s ->
        val t = rememberInfiniteTransition(label = "orb")
        // One slow breath (idle/ember), one event loop (ripples/waves), one rotation (orbits/arcs).
        val breath by t.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Reverse),
            label = "breath",
        )
        val loop by t.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(if (s == OrbState.Speaking) 700 else 1800, easing = LinearEasing)),
            label = "loop",
        )
        val angle by t.animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(2800, easing = LinearEasing)),
            label = "angle",
        )

        Canvas(modifier) {
            val r = size.minDimension * 0.30f
            when (s) {
                OrbState.Idle -> {
                    // Dark sphere, faint cyan rim, 3.6s breath (±2% scale, halo swell).
                    val sc = 1f + 0.02f * breath
                    halo(extras.glowCyan, r * (1.5f + 0.25f * breath), alpha = 0.10f + 0.08f * breath)
                    sphere(
                        r * sc,
                        listOf(scheme.surfaceContainerHigh, scheme.surfaceContainerLowest),
                    )
                    drawCircle(
                        color = extras.glowCyan.copy(alpha = 0.25f + 0.20f * breath),
                        radius = r * sc,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }

                OrbState.Listening -> {
                    // Bright cyan core + three ripples expanding past the rim (acoustic feedback).
                    halo(extras.glowCyan, r * 1.8f, alpha = 0.30f)
                    sphere(r, listOf(scheme.primary, scheme.primaryContainer, scheme.onPrimaryContainer))
                    repeat(3) { k ->
                        val p = (loop + k / 3f) % 1f
                        drawCircle(
                            color = extras.glowCyan.copy(alpha = (1f - p) * 0.45f),
                            radius = r * (1f + 0.75f * p),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }

                OrbState.Thinking -> {
                    // Dimmer core; three particles on tilted orbits (synaptic processing).
                    halo(extras.glowCyan, r * 1.5f, alpha = 0.20f)
                    sphere(
                        r * 0.8f,
                        listOf(
                            scheme.primary.copy(alpha = 0.55f),
                            scheme.primaryContainer.copy(alpha = 0.75f),
                            scheme.onPrimaryContainer,
                        ),
                    )
                    repeat(3) { i ->
                        val tilt = i * 60f
                        val a = (angle + i * 120f) * DEG
                        rotate(tilt) {
                            val pos = center + Offset(cos(a) * r * 1.45f, sin(a) * r * 0.55f)
                            drawCircle(extras.glowCyan.copy(alpha = 0.35f), 5.dp.toPx(), pos) // trail
                            drawCircle(scheme.primary, 2.5.dp.toPx(), pos)
                        }
                    }
                }

                OrbState.Speaking -> {
                    // White-hot core + symmetric waveform bars pulsing fast (vocal projection).
                    halo(extras.glowCyan, r * 1.7f, alpha = 0.35f)
                    sphere(
                        r * 0.75f,
                        listOf(Color.White, scheme.primary, scheme.primaryContainer, scheme.onPrimaryContainer),
                    )
                    val bars = 4
                    repeat(bars) { i ->
                        val h = r * (0.18f + 0.5f * abs(sin(loop * TWO_PI + i * 0.9f)))
                        val gap = r * 0.32f
                        val x = r * 1.05f + i * gap
                        val alpha = 0.85f - i * 0.18f
                        listOf(-1f, 1f).forEach { side ->
                            drawLine(
                                color = extras.glowCyan.copy(alpha = alpha),
                                start = center + Offset(side * x, -h),
                                end = center + Offset(side * x, h),
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }

                OrbState.Deliberating -> {
                    // Core + counter-rotating flux arcs (high-load compute).
                    halo(extras.glowCyan, r * 1.8f, alpha = 0.28f)
                    sphere(r * 0.85f, listOf(scheme.primary, scheme.primaryContainer, scheme.onPrimaryContainer))
                    val ringR = r * 1.3f
                    arcRing(extras.glowCyan.copy(alpha = 0.9f), ringR, startAngle = angle, sweep = 110f)
                    arcRing(scheme.primary.copy(alpha = 0.5f), ringR * 1.12f, startAngle = -angle * 1.4f, sweep = 70f)
                }

                OrbState.OffGrid -> {
                    // Amber ember: warm gradient, irregular flicker (the sheet's 0.98–1.05 keyframes).
                    val flicker = 1f + 0.025f * sin(loop * TWO_PI) +
                        0.02f * sin(breath * 2.55f * TWO_PI)
                    halo(extras.presenceAmber, r * (1.6f + 0.2f * breath), alpha = 0.22f + 0.10f * breath)
                    sphere(
                        r * flicker,
                        listOf(extras.emberHighlight, extras.presenceAmber, extras.emberDeep),
                    )
                }
            }
        }
    }
}

/** Soft radial halo behind the orb (the screens' outer box-shadow glow). */
private fun DrawScope.halo(color: Color, radius: Float, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
    )
}

/** The orb body: radial gradient with the highlight pulled to 40%,40% like the design CSS. */
private fun DrawScope.sphere(radius: Float, colors: List<Color>) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = colors,
            center = center + Offset(-radius * 0.2f, -radius * 0.2f),
            radius = radius * 1.4f,
        ),
        radius = radius,
    )
}

/** One rotating flux arc (deliberating). */
private fun DrawScope.arcRing(color: Color, radius: Float, startAngle: Float, sweep: Float) {
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = center - Offset(radius, radius),
        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
    )
}
