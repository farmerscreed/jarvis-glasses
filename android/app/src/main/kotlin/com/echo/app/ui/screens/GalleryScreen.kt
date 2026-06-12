package com.echo.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.JarvisSecondaryButton
import com.echo.app.ui.components.OrbState
import com.echo.app.ui.components.PresenceOrb
import com.echo.app.ui.theme.JarvisSpacing

/**
 * Gallery (gallery_visual_memories). The designed grid needs a media-listing query the engine
 * doesn't expose yet (UI pass adds no engine code), so this renders the designed empty state;
 * "Sync from glasses" is the one real action that produces visual memories today.
 */
@Composable
fun GalleryScreen(vm: HomeViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = JarvisSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PresenceOrb(state = if (vm.online) OrbState.Idle else OrbState.OffGrid)
        Spacer(Modifier.height(JarvisSpacing.lg))
        Text(
            "Visual memories",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(JarvisSpacing.sm))
        Text(
            "Photos captured on the glasses land here after a sync. The gallery grid arrives with the engine's media browse query.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(JarvisSpacing.lg))
        JarvisSecondaryButton("Sync from glasses", onClick = vm::syncGlasses, enabled = !vm.busy)
    }
}
