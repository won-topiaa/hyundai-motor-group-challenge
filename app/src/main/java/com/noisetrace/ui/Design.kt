package com.noisetrace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noisetrace.core.Spot

/** Deep navy with a blue glow top left, a magenta glow top right and a few light magenta strands across it. */
@Composable
fun NightBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Palette.Navy)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(Palette.Blue.copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(size.width * 0.05f, size.height * 0.02f),
                        radius = size.width * 0.55f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Palette.Magenta.copy(alpha = 0.5f), Color.Transparent),
                        center = Offset(size.width * 0.98f, size.height * 0.05f),
                        radius = size.width * 0.45f,
                    ),
                )
                drawStrands(this)
            },
    ) {
        // Outside a Surface, text would default to black; everything here sits on dark glass.
        CompositionLocalProvider(LocalContentColor provides Palette.Text) { content() }
    }
}

private fun drawStrands(scope: DrawScope) = with(scope) {
    for (i in 0 until 14) {
        val k = i / 13f
        val path = Path().apply {
            moveTo(size.width * (0.70f + 0.02f * k), -10f)
            cubicTo(
                size.width * (0.84f - 0.05f * k), size.height * (0.12f + 0.03f * k),
                size.width * (0.92f + 0.03f * k), size.height * (0.22f + 0.04f * k),
                size.width * 1.02f, size.height * (0.30f + 0.06f * k),
            )
        }
        drawPath(path, Palette.MagentaBright.copy(alpha = 0.10f + 0.06f * (1 - k)), style = Stroke(width = 1.6f))
    }
}

/** A dark glass panel with a hairline border, the way every section of the screen is grouped. */
@Composable
fun GlassCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    glow: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (glow != null) Brush.linearGradient(listOf(glow.copy(alpha = 0.32f), Palette.GlassStrong, Palette.GlassStrong))
                else Brush.linearGradient(listOf(Palette.GlassStrong, Palette.Glass)),
            )
            .border(1.dp, Palette.Stroke, shape)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides Palette.Text) {
            if (title != null) SectionTitle(title)
            content()
        }
    }
}

/** "TITLE ————": the section label followed by a thin line that fades out. */
@Composable
fun SectionTitle(text: String, color: Color = Palette.Text) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, fontSize = 21.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent))),
        )
    }
}

/** A big light number with a small caption under it ("18 km / JOURNEY COVERED"). */
@Composable
fun BigStat(value: String, label: String, modifier: Modifier = Modifier, color: Color = Palette.Text, size: Int = 38) {
    Column(modifier) {
        Text(value, color = color, fontSize = size.sp, fontWeight = FontWeight.Light)
        Text(label, color = Palette.TextMuted, fontSize = 16.sp, letterSpacing = 0.5.sp)
    }
}

/** A small value over a caption, for grids of car data. */
@Composable
fun StatCell(value: String, label: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Column(modifier) {
        Text(value, color = if (highlight) Palette.MagentaBright else Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Normal)
        Text(label, color = Palette.TextMuted, fontSize = 15.sp)
    }
}

/** A rounded tag; filled magenta when [selected]. */
@Composable
fun Pill(text: String, selected: Boolean = false, color: Color = Palette.MagentaBright, onClick: (() -> Unit)? = null, enabled: Boolean = true) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) Brush.horizontalGradient(listOf(Palette.Magenta, Palette.Blue.copy(alpha = 0.85f))) else Brush.horizontalGradient(listOf(Color(0x14FFFFFF), Color(0x0AFFFFFF))))
            .border(1.dp, if (selected) Color.Transparent else color.copy(alpha = if (enabled) 0.35f else 0.15f), shape)
            .let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(text, color = if (enabled) Palette.Text else Palette.TextMuted, fontSize = 19.sp)
    }
}

/** The main action: a glowing magenta-to-blue capsule. */
@Composable
fun GlowButton(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    progress: Float? = null,
    height: Dp = 150.dp,
    titleSize: Int = (height.value / 4.2f).coerceAtMost(46f).toInt(),
    subtitleSize: Int = 19,
) {
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier
            .height(height)
            .drawBehind {
                // Soft halo under the button.
                drawRoundRect(
                    Brush.radialGradient(listOf(Palette.Magenta.copy(alpha = if (enabled) 0.45f else 0.15f), Color.Transparent), radius = size.width * 0.6f),
                    topLeft = Offset(-24f, -24f), size = Size(size.width + 48f, size.height + 48f), cornerRadius = CornerRadius(size.height),
                )
            }
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Palette.Magenta, Color(0xFF6A16E0), Palette.Blue)))
            .border(1.dp, Color(0x55FFFFFF), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).background(Color(0x33FFFFFF)))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = titleSize.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, color = Color(0xE6FFFFFF), fontSize = subtitleSize.sp, textAlign = TextAlign.Center) }
        }
    }
}

/** Slanted tabs, like a drive-mode selector. */
@Composable
fun SkewTabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val skew = 26f
    val shape = GenericShape { size, _ ->
        moveTo(skew, 0f); lineTo(size.width, 0f); lineTo(size.width - skew, size.height); lineTo(0f, size.height); close()
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        items.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .width(170.dp)
                    .height(60.dp)
                    .clip(shape)
                    .background(
                        if (on) Brush.verticalGradient(listOf(Palette.Magenta, Color(0xFF5A0F7A)))
                        else Brush.verticalGradient(listOf(Color(0xFF1A1A30), Color(0xFF0E0E1C))),
                    )
                    .border(1.dp, if (on) Color(0x66FFFFFF) else Palette.BlueBright.copy(alpha = 0.45f), shape)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.White else Palette.TextMuted, fontSize = 21.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Switch in the cyan of live car data. */
@Composable
fun GlowSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Palette.Cyan.copy(alpha = 0.85f),
            uncheckedThumbColor = Palette.TextMuted, uncheckedTrackColor = Color(0xFF1C1C2E), uncheckedBorderColor = Palette.Stroke,
        ),
    )
}

/** A thin magenta-to-blue bar for how much louder than usual the cabin is right now. */
@Composable
fun LevelBar(excessDb: Float?, modifier: Modifier = Modifier) {
    val f = ((excessDb ?: 0f) / 20f).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x22FFFFFF))) {
        Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.02f)).height(8.dp).background(Brush.horizontalGradient(listOf(Palette.Cyan, Palette.MagentaBright))))
    }
}

/**
 * The car from above, nose up: where the noise was heard (glowing dots) and where its weight shifts under the
 * repeated conditions (arrows), e.g. forward and to the right on a slow left turn going downhill.
 */
@Composable
fun CarLoadView(spots: Set<Spot>, load: String?, modifier: Modifier = Modifier, height: Dp = 260.dp) {
    val dirs = load?.split("·")?.toSet().orEmpty()
    Canvas(modifier.height(height).fillMaxWidth()) {
        val cw = size.height * 0.42f
        val ch = size.height * 0.86f
        val left = (size.width - cw) / 2
        val top = (size.height - ch) / 2
        val cx = size.width / 2
        val cy = size.height / 2
        // Ground glow.
        drawOval(Brush.radialGradient(listOf(Palette.Magenta.copy(alpha = 0.35f), Color.Transparent)), topLeft = Offset(cx - cw * 1.3f, cy - ch * 0.62f), size = Size(cw * 2.6f, ch * 1.24f))
        // Wheels.
        val wheel = Size(cw * 0.16f, ch * 0.18f)
        listOf(0.16f, 0.72f).forEach { fy ->
            drawRoundRect(Color(0xFF07070F), Offset(left - wheel.width * 0.55f, top + ch * fy), wheel, CornerRadius(8f))
            drawRoundRect(Color(0xFF07070F), Offset(left + cw - wheel.width * 0.45f, top + ch * fy), wheel, CornerRadius(8f))
        }
        // Body, glass, outline.
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3A2152), Color(0xFF1B1530), Color(0xFF2A1A44))), Offset(left, top), Size(cw, ch), CornerRadius(cw * 0.42f))
        drawRoundRect(Color(0xFF0C1630), Offset(left + cw * 0.14f, top + ch * 0.22f), Size(cw * 0.72f, ch * 0.16f), CornerRadius(18f))
        drawRoundRect(Color(0xFF0C1630), Offset(left + cw * 0.16f, top + ch * 0.70f), Size(cw * 0.68f, ch * 0.10f), CornerRadius(14f))
        drawRoundRect(Palette.MagentaBright.copy(alpha = 0.6f), Offset(left, top), Size(cw, ch), CornerRadius(cw * 0.42f), style = Stroke(width = 2.5f))
        // Heard here.
        val sx = when { Spot.LEFT in spots && Spot.RIGHT !in spots -> cx - cw * 0.26f; Spot.RIGHT in spots && Spot.LEFT !in spots -> cx + cw * 0.26f; else -> cx }
        val sy = when { Spot.FRONT in spots && Spot.REAR !in spots -> top + ch * 0.20f; Spot.REAR in spots && Spot.FRONT !in spots -> top + ch * 0.82f; else -> cy }
        if (spots.isNotEmpty()) {
            drawCircle(Brush.radialGradient(listOf(Palette.MagentaBright, Palette.Magenta.copy(alpha = 0f)), center = Offset(sx, sy), radius = cw * 0.38f), radius = cw * 0.38f, center = Offset(sx, sy))
            drawCircle(Color.White, radius = 7f, center = Offset(sx, sy))
        }
        // Weight shift.
        fun arrow(dx: Float, dy: Float) {
            val start = Offset(cx + dx * cw * 0.15f, cy + dy * ch * 0.08f)
            val end = Offset(cx + dx * cw * 0.95f, cy + dy * ch * 0.58f)
            drawLine(Palette.Cyan, start, end, 5f, cap = StrokeCap.Round)
            val ang = kotlin.math.atan2(end.y - start.y, end.x - start.x)
            val head = 18f
            for (s in listOf(-0.5f, 0.5f)) {
                drawLine(Palette.Cyan, end, Offset(end.x - head * kotlin.math.cos(ang + s), end.y - head * kotlin.math.sin(ang + s)), 5f, cap = StrokeCap.Round)
            }
        }
        if ("앞" in dirs) arrow(0f, -1f)
        if ("뒤" in dirs) arrow(0f, 1f)
        if ("왼쪽" in dirs) arrow(-1.4f, 0f)
        if ("오른쪽" in dirs) arrow(1.4f, 0f)
    }
}

/** A round icon-like marker used in the driving screen and headers. */
@Composable
fun Dot(color: Color, size: Dp = 12.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
