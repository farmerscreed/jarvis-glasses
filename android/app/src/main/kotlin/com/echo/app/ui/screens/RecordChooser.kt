package com.echo.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.app.ui.components.Neo

/**
 * Home → **Record**: what to record and with what. Meeting (audio, phone mic) · Video · Site walk — the last two with
 * the glasses or the phone camera. The glasses buttons explain themselves when the glasses can't film right now.
 */
@Composable
fun RecordChooser(
    glassesReady: Boolean,
    glassesWhyNot: String,
    onMeeting: (glasses: Boolean) -> Unit,
    onGlasses: (walk: Boolean) -> Unit,
    onPhone: (walk: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neo.Card,
        title = { Text("What do you want to record?", style = Neo.display.copy(fontSize = 20.sp)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Option(
                    kindStyle("meeting"), "Meeting (audio)",
                    "You get notes, decisions and action items. The phone's mic hears the whole room best; the glasses' mic is best for what you and the person in front of you say.",
                ) {
                    Choice(Icons.Outlined.PhoneAndroid, "Phone", enabled = true) { onMeeting(false) }
                    Choice(Icons.Outlined.Visibility, "Glasses", enabled = glassesReady) { onMeeting(true) }
                }
                Option(
                    kindStyle("video"), "Video", "Film something. JARVIS watches it and writes what was seen and said.",
                ) {
                    Choice(Icons.Outlined.Visibility, "Glasses", enabled = glassesReady) { onGlasses(false) }
                    Choice(Icons.Outlined.PhoneAndroid, "Phone", enabled = true) { onPhone(false) }
                }
                Option(
                    kindStyle("walk"), "Site walk", "Walk a site and talk. You get a report: progress, problems with photos, counts, safety — as a PDF to send.",
                ) {
                    Choice(Icons.Outlined.Visibility, "Glasses", enabled = glassesReady) { onGlasses(true) }
                    Choice(Icons.Outlined.PhoneAndroid, "Phone", enabled = true) { onPhone(true) }
                }
                if (!glassesReady) Text(glassesWhyNot, style = Neo.body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = Neo.Muted))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Neo.Text) } },
    )
}

@Composable
private fun Option(kind: KindStyle, title: String, sub: String, buttons: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Neo.Ground).border(1.dp, Neo.Border, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(kind.color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(kind.icon, contentDescription = null, tint = kind.color, modifier = Modifier.size(18.dp))
            }
            Text(title, style = Neo.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        }
        Text(sub, style = Neo.body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = Neo.TextSoft))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }
}

@Composable
private fun Choice(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = if (enabled) Neo.Cyan else Neo.Faint
    Row(
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(50))
            .background(if (enabled) Neo.Cyan.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c, modifier = Modifier.size(16.dp))
        Text(label, style = Neo.body.copy(fontSize = 14.sp, color = c, fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false)
    }
}
