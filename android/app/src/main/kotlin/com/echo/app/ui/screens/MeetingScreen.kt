package com.echo.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.app.HomeViewModel
import com.echo.app.meeting.MeetingRecorder
import com.echo.app.ui.components.GhostButton
import com.echo.app.ui.components.GlowButton
import com.echo.app.ui.components.MonoLabel
import com.echo.app.ui.components.Neo
import com.echo.app.ui.components.NeoCard
import com.echo.app.ui.components.NeoTopBar
import com.echo.app.ui.components.RoundButton

/** Recording red — the one colour that means "the mic is capturing a meeting". */
private val Rec = Color(0xFFFF5C5C)
private val RecSoft = Color(0xFFFF9A9A)

/**
 * Meetings (Phase 2 · M1, docs/PHASE2_DESIGN.md §4/§8): START (title, phone vs glasses, consent reminder,
 * big record button) → RECORDING (timer in a glowing red ring, level meter, bookmarks, Mark · Stop · Pause)
 * → SAVED (duration, pieces, size). Recording keeps going with the screen off (foreground service).
 */
@Composable
fun MeetingScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val m = vm.meetingUi
    when {
        m.active -> MeetingLive(vm)
        m.lastSaved != null -> MeetingSaved(vm, onBack)
        else -> MeetingStart(vm, onBack)
    }
}

@Composable
private fun MeetingStart(vm: HomeViewModel, onBack: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Neo.Ground)) {
        NeoTopBar("Record a meeting", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoLabel("What is it? (optional)")
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    placeholder = { Text("e.g. Site meeting with Mr Ade", style = Neo.body.copy(fontSize = 16.sp, color = Neo.Faint)) },
                    textStyle = Neo.body.copy(fontSize = 17.sp),
                    shape = RoundedCornerShape(28.dp), colors = neoFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonoLabel("Listen with")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SourceTile(Icons.Outlined.PhoneAndroid, "Phone", "On the table — hears the room", selected = true, modifier = Modifier.weight(1f))
                    SourceTile(Icons.Outlined.Visibility, "Glasses", "Coming soon — hears you best", selected = false, modifier = Modifier.weight(1f))
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Neo.Amber.copy(alpha = 0.08f))
                    .border(1.dp, Neo.Amber.copy(alpha = 0.3f), RoundedCornerShape(20.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Neo.Amber, modifier = Modifier.size(22.dp))
                Text("Tell everyone you're recording before you start. It keeps going with the screen off — put the phone in the middle of the table.",
                    style = Neo.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
            }
            if (vm.meetingUi.error.isNotBlank()) Text(vm.meetingUi.error, style = Neo.body.copy(fontSize = 14.sp, color = Neo.Danger))
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(120.dp).border(3.dp, Rec.copy(alpha = 0.4f), CircleShape).clip(CircleShape)
                        .clickable(onClickLabel = "Start recording") { vm.startMeeting(title) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(92.dp).shadow(30.dp, CircleShape, ambientColor = Rec, spotColor = Rec).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(RecSoft, Rec, Color(0xFFB42323)), radius = 150f)))
                }
                Text("Start recording", style = Neo.display.copy(fontSize = 18.sp))
                Text("or say “Jarvis, record this meeting”", style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SourceTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, selected: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.height(112.dp).clip(shape).background(if (selected) Neo.Cyan.copy(alpha = 0.08f) else Neo.Card)
            .border(1.dp, if (selected) Neo.Cyan else Neo.BorderStrong, shape).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Neo.Cyan else Neo.Faint, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Neo.display.copy(fontSize = 15.sp, color = if (selected) Neo.Text else Neo.Muted))
            Text(sub, style = Neo.body.copy(fontSize = 12.sp, color = Neo.Muted))
        }
    }
}

@Composable
private fun MeetingLive(vm: HomeViewModel) {
    val m = vm.meetingUi
    val pulse = rememberInfiniteTransition(label = "rec")
    val glow by pulse.animateFloat(0.85f, 1.05f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "glow")
    Column(Modifier.fillMaxSize().background(Neo.Ground).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(Rec.copy(alpha = 0.12f)).border(1.dp, Rec.copy(alpha = 0.35f), RoundedCornerShape(50)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (m.paused) Neo.Amber else Rec))
                Text(if (m.paused) "PAUSED" else "RECORDING · PHONE", style = Neo.mono.copy(fontSize = 12.sp, color = if (m.paused) Neo.Amber else Neo.Danger, letterSpacing = 0.5.sp))
            }
            Spacer(Modifier.weight(1f))
            Text("${"%.1f".format(m.bytes / 1_000_000.0)} MB", style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp, color = Neo.Faint))
        }
        Column(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(236.dp).border(1.dp, Rec.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(204.dp).scale(if (m.paused) 1f else glow).border(2.dp, Rec.copy(alpha = if (m.paused) 0.25f else 0.55f), CircleShape),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(MeetingRecorder.clock(m.elapsedMs), style = Neo.display.copy(fontSize = 52.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
                    Text(m.title.ifBlank { "Meeting" }, style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted), maxLines = 1)
                }
            }
            Spacer(Modifier.height(18.dp))
            LevelMeter(if (m.paused) 0f else m.level)
        }
        // Bookmarks + what happens next.
        NeoCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp).weight(1f), padding = 16.dp) {
            Row { MonoLabel("Marked moments", color = Neo.Cyan, modifier = Modifier.weight(1f)); MonoLabel("${m.chunks} pieces saved", color = Neo.Faint) }
            if (m.bookmarks.isEmpty()) {
                Text("Tap the bookmark — or say “Jarvis, mark that” — when something important is said.", style = Neo.body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = Neo.Muted))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    m.bookmarks.reversed().forEach { b ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Bookmark, contentDescription = null, tint = Neo.Amber, modifier = Modifier.size(16.dp))
                            Text(MeetingRecorder.clock(b.tS * 1000L) + (if (b.label.isNotBlank()) " — ${b.label}" else ""), style = Neo.body.copy(fontSize = 14.sp, color = Neo.Amber))
                        }
                    }
                }
            }
            Text("The transcript and summary come after you stop.", style = Neo.body.copy(fontSize = 12.sp, color = Neo.Faint))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Outlined.BookmarkBorder, "Mark this moment", onClick = { vm.markMeeting() }, size = 60.dp)
            Box(
                Modifier.size(84.dp).border(3.dp, Rec.copy(alpha = 0.45f), CircleShape).clip(CircleShape).clickable(onClickLabel = "Stop recording") { vm.stopMeeting() },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(34.dp).shadow(16.dp, RoundedCornerShape(8.dp), ambientColor = Rec, spotColor = Rec).clip(RoundedCornerShape(8.dp)).background(Rec)) }
            RoundButton(if (m.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, if (m.paused) "Resume" else "Pause",
                onClick = { if (m.paused) vm.resumeMeeting() else vm.pauseMeeting() }, size = 60.dp)
        }
    }
}

/** Nine bars that follow the room's loudness. */
@Composable
private fun LevelMeter(level: Float) {
    Row(Modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = listOf(0.25f, 0.5f, 0.8f, 0.6f, 1f, 0.45f, 0.7f, 0.35f, 0.2f)
        shape.forEach { f ->
            val h = (6f + 34f * f * level.coerceIn(0f, 1f)).dp
            Box(Modifier.width(4.dp).height(h).clip(RoundedCornerShape(2.dp)).background(if (level > 0.05f) Rec else Color(0xFF3A2323)))
        }
    }
}

@Composable
private fun MeetingSaved(vm: HomeViewModel, onBack: () -> Unit) {
    val s = vm.meetingUi.lastSaved ?: return
    Column(Modifier.fillMaxSize().background(Neo.Ground)) {
        NeoTopBar("Meeting saved", onBack = { vm.clearMeetingSaved(); onBack() })
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NeoCard(hero = true, padding = 22.dp) {
                MonoLabel("Saved on your phone", color = Neo.Cyan)
                Text(s.title.ifBlank { "Meeting" }, style = Neo.display.copy(fontSize = 24.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Length", MeetingRecorder.clock(s.durationS * 1000L), Modifier.weight(1f))
                    StatTile("Pieces", "${s.chunks}", Modifier.weight(1f))
                    StatTile("Size", "${"%.1f".format(s.bytes / 1_000_000.0)} MB", Modifier.weight(1f))
                }
            }
            Text("Next update: it uploads, writes the transcript and a summary with action items, and tells you when it's ready.",
                style = Neo.body.copy(fontSize = 14.sp, lineHeight = 21.sp, color = Neo.Muted), textAlign = TextAlign.Start)
            GlowButton("Done", onClick = { vm.clearMeetingSaved(); onBack() }, modifier = Modifier.fillMaxWidth())
            GhostButton("Record another", onClick = { vm.clearMeetingSaved() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Neo.CardLow).border(1.dp, Neo.Border, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MonoLabel(label, size = 10)
        Text(value, style = Neo.display.copy(fontSize = 18.sp))
    }
}
