package ir.sabou.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import ir.sabou.app.ui.theme.Sabou
import ir.sabou.app.ui.theme.SabouType

/** Colours of chart series, from the theme roles (light and dark alike). The last one is for «سایر». */
@Composable
fun chartColors(): List<Color> = Sabou.colors.let { listOf(it.primary, it.accent, it.moneyIn, it.moneyOut, it.bank, it.subtle) }

/**
 * A ring of [values] (non-negative) in [colors], read clockwise from the top, with [center] content inside.
 * [description] is what a screen reader announces instead of the drawing.
 */
@Composable
fun Donut(values: List<Long>, colors: List<Color>, description: String, modifier: Modifier = Modifier, size: Dp = 148.dp, thickness: Dp = 20.dp, center: @Composable () -> Unit = {}) {
    val total = values.sumOf { it.coerceAtLeast(0) }
    val track = Sabou.colors.track
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = thickness.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            if (total > 0) {
                val gap = if (values.count { it > 0 } > 1) 2f else 0f
                var start = -90f
                values.forEachIndexed { i, v ->
                    if (v <= 0) return@forEachIndexed
                    val sweep = (v.toDouble() / total * 360.0).toFloat()
                    drawArc(colors[i % colors.size], start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), false, topLeft, arcSize, style = Stroke(stroke))
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) { center() }
    }
}

/**
 * Vertical bars of [values] with a label under each; the [highlight] bar (e.g. today) is drawn in the primary
 * colour, the others lighter. Heights are relative to the largest value.
 */
@Composable
fun Bars(values: List<Long>, labels: List<String>, description: String, modifier: Modifier = Modifier, highlight: Int = values.lastIndex, height: Dp = 96.dp) {
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val c = Sabou.colors
    Row(modifier.fillMaxWidth().semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.height(height).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    val h = if (v <= 0) 3.dp else (height * (v.toFloat() / max)).coerceAtLeast(4.dp)
                    Spacer(
                        Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                            .background(if (v <= 0) c.track else if (i == highlight) c.primary else c.primary.copy(alpha = 0.35f)),
                    )
                }
                Text(labels.getOrElse(i) { "" }, style = SabouType.caption, color = if (i == highlight) c.ink else c.muted, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A legend row: colour dot, label, share and amount. */
@Composable
fun LegendRow(color: Color, label: String, share: String, amount: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Text(label, style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
        Text(share, style = SabouType.caption, color = Sabou.colors.muted)
        Text(amount, style = SabouType.bodyStrong, color = Sabou.colors.ink)
    }
}

/**
 * Two series over the same days (ADR-0064): [current] as a filled line, [previous] dashed. Drawn right to left in a
 * right-to-left layout, so the first day sits on the right as the labels under it read.
 */
@Composable
fun LineChart(current: List<Long>, previous: List<Long>, labels: List<String>, description: String, modifier: Modifier = Modifier, height: Dp = 140.dp) {
    val c = Sabou.colors
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val max = (current + previous).maxOrNull()?.takeIf { it > 0 } ?: 1L
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = current.size.coerceAtLeast(2)
            fun x(i: Int): Float { val p = i * size.width / (n - 1); return if (rtl) size.width - p else p }
            fun y(v: Long): Float = size.height - 6.dp.toPx() - (v.toFloat() / max) * (size.height - 16.dp.toPx())
            for (g in 0..3) { val gy = size.height * g / 3f; drawLine(c.divider, Offset(0f, gy), Offset(size.width, gy), 1f) }
            if (previous.size == current.size) {
                val p = androidx.compose.ui.graphics.Path().apply { previous.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
                drawPath(p, c.subtle, style = Stroke(2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            }
            val line = androidx.compose.ui.graphics.Path().apply { current.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            val fill = androidx.compose.ui.graphics.Path().apply {
                addPath(line); lineTo(x(current.lastIndex), size.height); lineTo(x(0), size.height); close()
            }
            drawPath(fill, c.primary.copy(alpha = 0.10f))
            drawPath(line, c.primary, style = Stroke(3.dp.toPx()))
            current.forEachIndexed { i, v -> drawCircle(c.surface, 5.dp.toPx(), Offset(x(i), y(v))); drawCircle(c.primary, 3.5.dp.toPx(), Offset(x(i), y(v))) }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it, style = SabouType.caption, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
    }
}

/** Bars of different [colors] with their value printed on top ([valueText]) and a label under each. */
@Composable
fun ColorBars(values: List<Long>, labels: List<String>, colors: List<Color>, valueText: (Long) -> String, description: String, modifier: Modifier = Modifier, height: Dp = 120.dp) {
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    Row(modifier.fillMaxWidth().semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(valueText(v), style = SabouType.caption, color = Sabou.colors.ink, maxLines = 1)
                Box(Modifier.fillMaxWidth().height((height * (v.toFloat() / max)).coerceAtLeast(3.dp)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .background(colors[i % colors.size]))
                Text(labels[i], style = SabouType.caption, color = Sabou.colors.muted, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Groups of side-by-side bars, one group per label, one colour per series (personnel, materials, profit). */
@Composable
fun GroupedBars(groups: List<List<Long>>, labels: List<String>, colors: List<Color>, description: String, modifier: Modifier = Modifier, height: Dp = 140.dp) {
    val max = groups.flatten().maxOrNull()?.takeIf { it > 0 } ?: 1L
    Row(modifier.fillMaxWidth().semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        groups.forEachIndexed { g, values ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                    values.forEachIndexed { s, v ->
                        Box(Modifier.weight(1f).height((height * (v.coerceAtLeast(0).toFloat() / max)).coerceAtLeast(2.dp))
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(colors[s % colors.size]))
                    }
                }
                Text(labels[g], style = SabouType.caption, color = Sabou.colors.muted, maxLines = 1)
            }
        }
    }
}

/** A labelled horizontal bar 0–100 ([score] null: unknown, an empty track). */
@Composable
fun ScoreTrack(title: String, score: Int?, color: Color) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = SabouType.body, color = Sabou.colors.ink, modifier = Modifier.weight(1f))
            Text(score?.let { "٪" + ir.sabou.core.Fa.number(it.toLong()) } ?: "نامعلوم", style = SabouType.label, color = if (score == null) Sabou.colors.muted else Sabou.colors.ink)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Sabou.colors.track)) {
            if (score != null && score > 0) Box(Modifier.fillMaxWidth(score / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}
