package com.echo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme

/** ALL-CAPS section header with leading icon ("🛒 PRICE ANALYSIS", "TOOL_RESULT.01"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Structured tool output (the deliberate lane): a 24dp card of rows. The highlighted row gets a
 * cyan accent edge + tinted value, like "Amazon — $298.00 BEST OPTION".
 */
@Composable
fun ToolResultCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column { content() }
    }
}

@Composable
fun ToolResultRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingInitial: String? = null,
    highlighted: Boolean = false,
    valueTag: String? = null,
) {
    val cyan = MaterialTheme.colorScheme.primaryContainer
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (highlighted) Modifier.background(cyan.copy(alpha = 0.06f)) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Accent edge on the winning row
        Box(
            Modifier
                .width(3.dp)
                .height(56.dp)
                .background(if (highlighted) cyan else androidx.compose.ui.graphics.Color.Transparent),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = JarvisSpacing.md, vertical = JarvisSpacing.sm + JarvisSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
        ) {
            if (leadingInitial != null) {
                Box(
                    Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(leadingInitial, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    value,
                    style = JarvisTheme.dataMono,
                    color = if (highlighted) cyan else MaterialTheme.colorScheme.onSurface,
                )
                if (valueTag != null) {
                    Text(
                        valueTag.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = cyan,
                        modifier = Modifier
                            .padding(top = JarvisSpacing.xs)
                            .background(cyan.copy(alpha = 0.15f), MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = JarvisSpacing.xs, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** Key/value stat tile ("DISTANCE / 5.2 km"): mono value over an ALL-CAPS label. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .padding(JarvisSpacing.md),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
