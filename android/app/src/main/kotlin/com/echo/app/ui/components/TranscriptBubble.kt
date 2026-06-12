package com.echo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.echo.app.ui.theme.JarvisSpacing

/**
 * Conversation bubbles from the consoles/timeline: the user speaks in a neutral surface bubble;
 * Jarvis answers in a cyan-tinted bubble with a cyan hairline. The corner nearest the speaker
 * is squared (16dp card radius, 4dp on the anchor corner).
 */
@Composable
fun TranscriptBubble(
    text: String,
    fromUser: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = if (fromUser) {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    } else {
        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    }
    val cyan = MaterialTheme.colorScheme.primaryContainer
    val base = modifier.clip(shape)
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (fromUser) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        modifier = (
            if (fromUser) {
                base.background(MaterialTheme.colorScheme.surfaceContainerHigh)
            } else {
                base
                    .background(cyan.copy(alpha = 0.12f))
                    .border(1.dp, cyan.copy(alpha = 0.35f), shape)
            }
            ).padding(horizontal = JarvisSpacing.md, vertical = JarvisSpacing.sm + JarvisSpacing.xs),
    )
}
