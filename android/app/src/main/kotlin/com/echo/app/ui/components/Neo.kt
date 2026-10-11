package com.echo.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.app.ui.theme.InterFont
import com.echo.app.ui.theme.JetBrainsMono
import com.echo.app.ui.theme.SpaceGrotesk

/**
 * The 2026-10 redesign's building blocks (docs/UI_REDESIGN.md). Near-black ground, cyan glow only for
 * what's live or primary, amber for "needs attention", big round targets, one-word states.
 */
object Neo {
    val Ground = Color(0xFF0A0E13)
    val Bar = Color(0xFF0C1015)
    val Card = Color(0xFF12161B)
    val CardLow = Color(0xFF11151A)
    val Border = Color(0xFF1F262B)
    val BorderStrong = Color(0xFF262A30)
    val AccentBorder = Color(0xFF2B3A40)
    val HeroBorder = Color(0xFF22313A)
    val Divider = Color(0xFF1A2026)
    val Text = Color(0xFFE0E2EA)
    val TextSoft = Color(0xFFBBC9CE)
    val Muted = Color(0xFF8E9CA1)
    val Faint = Color(0xFF6F7D82)
    val Fainter = Color(0xFF3F4B50)
    val Cyan = Color(0xFF3DDCFF)
    val CyanPale = Color(0xFFBBEFFF)
    val OnCyan = Color(0xFF00222A)
    val Amber = Color(0xFFFFB454)
    val Danger = Color(0xFFFFB4AB)
    val DangerFill = Color(0x2E93000A)

    val display = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, color = Text)
    val body = TextStyle(fontFamily = InterFont, color = Text)
    val mono = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp, color = Muted)
}

/** Small uppercase monospace label ("CONNECTIONS", "FRENCH → ENGLISH"). */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = Neo.Muted, size: Int = 11) {
    Text(text.uppercase(), style = Neo.mono.copy(fontSize = size.sp, color = color), modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Big glowing cyan pill — the one primary action on a screen. */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(height)
            .then(if (enabled) Modifier.shadow(18.dp, shape, ambientColor = Neo.Cyan, spotColor = Neo.Cyan) else Modifier)
            .clip(shape)
            .background(if (enabled) Neo.Cyan else Neo.BorderStrong)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (enabled) Neo.OnCyan else Neo.Muted, modifier = Modifier.size(20.dp))
        Text(text, style = Neo.display.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (enabled) Neo.OnCyan else Neo.Muted), maxLines = 1)
    }
}

/** Quiet outlined pill for secondary actions. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    color: Color = Neo.Text,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(height)
            .clip(shape)
            .background(Neo.Card)
            .border(1.dp, Neo.AccentBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = Neo.display.copy(fontSize = 16.sp, color = color), maxLines = 1)
    }
}

enum class RoundStyle { Plain, Primary, Danger, Accent }

/** Round icon-only button (44/52/56). Always give it a [label] for TalkBack. */
@Composable
fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    style: RoundStyle = RoundStyle.Plain,
    enabled: Boolean = true,
) {
    val (bg, fg, border) = when (style) {
        RoundStyle.Plain -> Triple(Neo.Card, Neo.TextSoft, Neo.BorderStrong)
        RoundStyle.Primary -> Triple(Neo.Cyan, Neo.OnCyan, Neo.Cyan)
        RoundStyle.Danger -> Triple(Neo.DangerFill, Neo.Danger, Neo.Danger.copy(alpha = 0.45f))
        RoundStyle.Accent -> Triple(Neo.Cyan.copy(alpha = 0.12f), Neo.Cyan, Neo.Cyan.copy(alpha = 0.12f))
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .clickable(enabled = enabled, onClick = onClick, onClickLabel = label)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(size * 0.42f))
    }
}

/** Top bar for full screens: round back button, centred title, optional trailing action. */
@Composable
fun NeoTopBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
        if (onBack != null) RoundButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, Modifier.align(Alignment.CenterStart))
        Text(title, style = Neo.display.copy(fontSize = 20.sp), modifier = Modifier.align(Alignment.Center))
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/** A card on the dark ground. [hero] adds the cyan corner glow used for answers and results. */
@Composable
fun NeoCard(
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    padding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(if (hero) 26.dp else 20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Neo.Card)
            .then(
                if (hero) Modifier.background(Brush.radialGradient(listOf(Neo.Cyan.copy(alpha = 0.14f), Color.Transparent), radius = 700f))
                else Modifier,
            )
            .border(1.dp, if (hero) Neo.HeroBorder else Neo.Border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A feature tile in a grid: icon in a tinted chip + label (+ short sub-label). */
@Composable
fun FeatureTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    accent: Color = Neo.Cyan,
    tall: Boolean = false,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .height(if (tall) 128.dp else 96.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF181C21), Neo.Card)))
            .border(1.dp, Neo.BorderStrong, shape)
            .clickable(onClick = onClick, onClickLabel = label)
            .padding(if (tall) 14.dp else 10.dp),
        horizontalAlignment = if (tall) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = if (tall) Arrangement.SpaceBetween else Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        if (tall) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = Neo.display.copy(fontSize = 16.sp))
                if (sub != null) Text(sub, style = Neo.body.copy(fontSize = 12.sp, color = Neo.Muted), maxLines = 1)
            }
        } else {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
            Text(label, style = Neo.body.copy(fontSize = 13.sp), maxLines = 1)
        }
    }
}

/** Settings-style group: mono label above a rounded block of rows separated by hairlines. */
@Composable
fun SettingsGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) MonoLabel(title, Modifier.padding(start = 4.dp, bottom = 8.dp, top = 6.dp))
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Neo.Card).border(1.dp, Neo.Border, shape),
            content = content,
        )
    }
}

/**
 * One row: tinted icon chip, title (+ optional sub), a one-word [value] and a chevron — or a switch
 * when [checked] is non-null. [divider] draws the hairline under it (off for the last row).
 */
@Composable
fun SettingsRow(
    icon: ImageVector?,
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    value: String? = null,
    valueColor: Color = Neo.Muted,
    accent: Color = Neo.Cyan,
    titleColor: Color = Neo.Text,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(
                when {
                    checked != null && onCheckedChange != null -> Modifier.clickable { onCheckedChange(!checked) }
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Neo.body.copy(fontSize = 15.sp, color = titleColor))
            if (sub != null) Text(sub, style = Neo.body.copy(fontSize = 12.sp, color = Neo.Muted))
        }
        if (value != null) Text(value, style = Neo.body.copy(fontSize = 13.sp, color = valueColor), maxLines = 1)
        when {
            checked != null -> Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Neo.Cyan, checkedThumbColor = Neo.OnCyan, uncheckedTrackColor = Neo.BorderStrong),
            )
            onClick != null -> Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = Neo.Faint, modifier = Modifier.size(20.dp))
        }
    }
    if (divider) HorizontalDivider(color = Neo.Divider, thickness = 1.dp)
}

/** Selectable pills in a horizontally scrolling row. */
@Composable
fun FilterChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) -> NeoChip(label, key == selected, onClick = { onSelect(key) }) }
    }
}

@Composable
fun NeoChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dashed: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) Neo.Cyan else Color.Transparent)
            .border(1.dp, if (selected) Neo.Cyan else if (dashed) Neo.BorderStrong else Neo.AccentBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Neo.body.copy(fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Neo.OnCyan else Neo.TextSoft))
    }
}

/** Live "someone is speaking" bars. [active] animates them; idle they rest low and dim. */
@Composable
fun LiveBars(active: Boolean, modifier: Modifier = Modifier, bars: Int = 11, maxHeight: Dp = 42.dp, barWidth: Dp = 4.dp) {
    val t = rememberInfiniteTransition(label = "bars")
    val phase by t.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "phase")
    Row(modifier.height(maxHeight), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(bars) { i ->
            val center = 1f - kotlin.math.abs(i - (bars - 1) / 2f) / (bars / 2f)
            val wave = if (active) (0.35f + 0.65f * ((kotlin.math.sin(phase + i * 0.9f) + 1f) / 2f)) else 0.18f
            val h = (maxHeight.value * (0.25f + 0.75f * center) * wave).coerceAtLeast(4f)
            Box(
                Modifier.width(barWidth).height(h.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (active) (if (i % 4 == 1) Neo.CyanPale else Neo.Cyan) else Neo.AccentBorder),
            )
        }
    }
}

/** One status dot + label ("Glasses 24%"). */
@Composable
fun StatusDot(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp))
    }
}

/** A numbered source link (research). */
@Composable
fun SourceCard(index: Int, title: String, url: String, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val domain = remember(url) { url.substringAfter("://").substringBefore('/').removePrefix("www.") }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Neo.CardLow).border(1.dp, Neo.Border, shape)
            .clickable { runCatching { uri.openUri(url) } }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Neo.Cyan.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text("$index", style = Neo.mono.copy(fontSize = 12.sp, color = Neo.Cyan, letterSpacing = 0.sp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title.ifBlank { domain }, style = Neo.body.copy(fontSize = 14.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(domain, style = Neo.body.copy(fontSize = 12.sp, color = Neo.Muted), maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open", tint = Neo.Faint, modifier = Modifier.size(16.dp))
    }
}

/** A thin pill showing something live ("● FRENCH → ENGLISH"). */
@Composable
fun LivePill(text: String, modifier: Modifier = Modifier, color: Color = Neo.Cyan, onClick: (() -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.height(38.dp).clip(shape).background(color.copy(alpha = 0.08f)).border(1.dp, Neo.AccentBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).shadow(6.dp, CircleShape, ambientColor = color, spotColor = color).clip(CircleShape).background(color))
        Text(text.uppercase(), style = Neo.mono.copy(fontSize = 12.sp, color = Neo.CyanPale, letterSpacing = 0.5.sp), maxLines = 1)
        trailing?.invoke(this)
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

/** Thin bordered stroke used by outlined cards. */
val NeoStroke = BorderStroke(1.dp, Neo.Border)

@Composable
private fun <T> remember(key: Any?, calc: () -> T): T = androidx.compose.runtime.remember(key) { calc() }
