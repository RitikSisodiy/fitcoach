package com.fitcoach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared building blocks of the FitCoach design (see Theme.kt). */

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, inset: Dp = 20.dp, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = inset, end = inset - 4.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            subtitle?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fc.TextMuted) }
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Fc.Text)
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.labelSmall, color = Fc.TextMuted)
}

/** Rounded surface card with an optional title row. */
@Composable
fun FcCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: @Composable (() -> Unit)? = null,
    background: Brush? = null,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .then(if (background != null) Modifier.background(background) else Modifier.background(Fc.Surface))
            .border(1.dp, Fc.Outline.copy(alpha = 0.6f), shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null || action != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title ?: "", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Fc.Text)
            action?.invoke()
        }
        content()
    }
}

/** A labelled number, e.g. "Adherence 7 d  80%". */
@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, caption: String? = null, color: Color = Fc.Accent) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Fc.SurfaceHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Fc.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MetricStyle, maxLines = 1)
            unit?.let { Text(" $it", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = Fc.TextMuted) }
        }
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** Circular progress; [progress] null draws only the track (no target known). */
@Composable
fun Ring(progress: Float?, color: Color, size: Dp = 92.dp, stroke: Dp = 10.dp, content: @Composable () -> Unit) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            val arc = Size(this.size.width - s, this.size.height - s)
            val tl = Offset(s / 2, s / 2)
            drawArc(color.copy(alpha = 0.16f), -90f, 360f, false, tl, arc, style = Stroke(s, cap = StrokeCap.Round))
            if (progress != null && progress > 0f) {
                drawArc(color, -90f, 360f * progress.coerceAtMost(1f), false, tl, arc, style = Stroke(s, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(color.copy(alpha = 0.16f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
    }
}

@Composable
fun Pill(text: String, color: Color = Fc.Accent, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Text(
        text,
        modifier.clip(CircleShape).background(color.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
    )
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

/** A settings / list row: title, optional subtitle, optional trailing content. */
@Composable
fun ListRow(title: String, subtitle: String? = null, leading: Color? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { StatusDot(it, 10.dp); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Fc.Text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        trailing?.invoke()
    }
}

@Composable
fun FcButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick, modifier.height(48.dp), enabled = enabled, shape = CircleShape,
        colors = if (primary) ButtonDefaults.buttonColors(containerColor = Fc.Accent, contentColor = Fc.OnAccent)
        else ButtonDefaults.buttonColors(containerColor = Fc.SurfaceHigher, contentColor = Fc.Text),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Round icon button (call controls, send, header actions). */
@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                    container: Color = Fc.SurfaceHigher, tint: Color = Fc.Text, size: Dp = 48.dp, enabled: Boolean = true) {
    Box(
        modifier.size(size).clip(CircleShape).background(if (enabled) container else container.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, Modifier.size(size * 0.45f), tint = if (enabled) tint else tint.copy(alpha = 0.5f)) }
}

@Composable
fun FcTextField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true,
                visual: VisualTransformation = VisualTransformation.None) {
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine, visualTransformation = visual,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Fc.Accent, unfocusedBorderColor = Fc.Outline, focusedLabelColor = Fc.Accent,
            focusedContainerColor = Fc.SurfaceHigh, unfocusedContainerColor = Fc.SurfaceHigh, cursorColor = Fc.Accent,
        ),
    )
}

@Composable
fun EmptyHint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Fc.TextFaint)

/** Simple bar chart; null values are gaps. */
@Composable
fun Bars(values: List<Float?>, color: Color, modifier: Modifier = Modifier, highlightLast: Boolean = true) {
    val max = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1f
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width / values.size
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.25f, w * 0.25f)
        values.forEachIndexed { i, v ->
            val c = if (highlightLast && i == values.lastIndex) color else color.copy(alpha = 0.45f)
            if (v == null) {
                drawRoundRect(Fc.SurfaceHigher, Offset(i * w + w * 0.18f, size.height - 4.dp.toPx()), Size(w * 0.64f, 4.dp.toPx()), r)
            } else {
                val h = (size.height * (v / max)).coerceAtLeast(4.dp.toPx())
                drawRoundRect(c, Offset(i * w + w * 0.18f, size.height - h), Size(w * 0.64f, h), r)
            }
        }
    }
}
