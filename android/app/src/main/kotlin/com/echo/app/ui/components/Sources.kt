package com.echo.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme

/**
 * Research answers arrive as "<spoken answer>\nSources:\n<url>\n<url>…". This splits them so the
 * answer reads cleanly and the sources render as tappable chips instead of raw URLs.
 */
fun splitSources(text: String): Pair<String, List<String>> {
    val idx = text.indexOf("Sources:", ignoreCase = true)
    if (idx < 0) return text to emptyList()
    val body = text.substring(0, idx).trim().ifBlank { text }
    val urls = text.substring(idx + "Sources:".length).split("\n", " ")
        .map { it.trim().removePrefix("-").removePrefix("•").trim() }
        .filter { it.startsWith("http") }
        .distinct()
    return body to urls
}

/** "https://www.nairametrics.com/2026/…" → "nairametrics.com" */
fun domainOf(url: String): String =
    url.substringAfter("://").substringBefore('/').removePrefix("www.").removePrefix("m.")

/** Tappable source chips (site names) — opens the page in the browser. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesRow(urls: List<String>, modifier: Modifier = Modifier, label: Boolean = true) {
    if (urls.isEmpty()) return
    val uri = LocalUriHandler.current
    val accent = MaterialTheme.colorScheme.primaryContainer
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)) {
        if (label) Text("SOURCES · ${urls.size}", style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.xs), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)) {
            urls.forEach { url ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .clickable { runCatching { uri.openUri(url) } }
                        .padding(horizontal = JarvisSpacing.sm + JarvisSpacing.xs, vertical = JarvisSpacing.xs + 2.dp),
                ) {
                    Text(domainOf(url), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}
