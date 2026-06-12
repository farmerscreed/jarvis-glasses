package com.echo.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme

/**
 * Status pill ("● Glasses · Connected", "Cloud · Off-grid"): transparent fill, 1px accent
 * border, mono type, leading status dot. Accent cyan = live/cloud-synced; amber = offline /
 * on-phone / pending (pass [JarvisTheme.colors.presenceAmber]).
 */
@Composable
fun StatusChip(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, accent.copy(alpha = 0.7f), CircleShape)
            .padding(horizontal = JarvisSpacing.md, vertical = JarvisSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        } else {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
        }
        Text(text, style = JarvisTheme.dataMono, color = accent)
    }
}

/** Filled neutral pill ("3 new captures", "Mic: glasses"): surface fill, secondary text. */
@Composable
fun NeutralChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = JarvisSpacing.md, vertical = JarvisSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
    ) {
        if (icon != null) {
            Icon(
                icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(text, style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tiny ALL-CAPS tag ("ARCHITECTURE", "RETAIL") on memory cards. */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = JarvisSpacing.sm, vertical = 2.dp),
    )
}

/** ALL-CAPS section label with leading dot, e.g. the amber "● LOCAL PROCESSING". */
@Composable
fun StatusLabel(text: String, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
    ) {
        Box(Modifier.size(6.dp).background(accent, CircleShape))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = accent)
    }
}
