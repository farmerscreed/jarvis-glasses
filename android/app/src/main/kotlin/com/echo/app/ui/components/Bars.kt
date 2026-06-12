package com.echo.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme
import com.echo.app.ui.theme.SpaceGrotesk

/**
 * Top bar: menu/back leading, cyan JARVIS wordmark (or screen title) centered, "?" help trailing.
 */
@Composable
fun JarvisTopBar(
    modifier: Modifier = Modifier,
    title: String = "JARVIS",
    wordmark: Boolean = true,
    onNavigate: (() -> Unit)? = null,
    navigateBack: Boolean = false,
    onHelp: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp),
    ) {
        if (onNavigate != null) {
            IconButton(onClick = onNavigate, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    if (navigateBack) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Menu,
                    contentDescription = if (navigateBack) "back" else "menu",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (wordmark) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.08.em,
                ),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (onHelp != null) {
            IconButton(onClick = onHelp, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = "help",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The app's four destinations, exactly as in the designs' bottom bar. */
enum class JarvisTab(val label: String, val icon: ImageVector) {
    Live("Live", Icons.Outlined.GraphicEq),
    Timeline("Timeline", Icons.Outlined.History),
    Gallery("Gallery", Icons.Outlined.PhotoLibrary),
    Settings("Settings", Icons.Outlined.Settings),
}

/** Bottom nav: hairline divider on top, icon+label items, active item in Electric Cyan. */
@Composable
fun JarvisBottomBar(
    current: JarvisTab,
    onSelect: (JarvisTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = modifier) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = JarvisSpacing.sm),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                JarvisTab.entries.forEach { tab ->
                    val active = tab == current
                    val tint = if (active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs),
                        modifier = Modifier
                            .selectable(selected = active, onClick = { onSelect(tab) })
                            .padding(horizontal = JarvisSpacing.md, vertical = JarvisSpacing.xs),
                    ) {
                        Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(24.dp))
                        Text(tab.label, style = JarvisTheme.dataMono, color = tint)
                    }
                }
            }
        }
    }
}
