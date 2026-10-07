package com.fitcoach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.Icon
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.engine.Dashboard
import com.fitcoach.app.engine.DashboardData
import com.fitcoach.app.engine.Progress
import com.fitcoach.app.notify.Notifier
import com.fitcoach.app.sensors.ActivityTracker
import com.fitcoach.app.sensors.CalendarReader
import com.fitcoach.app.sensors.FoodNotificationListener
import com.fitcoach.app.sensors.HealthSync
import com.fitcoach.app.sensors.Places
import com.fitcoach.app.telegram.Telegram
import com.fitcoach.app.voice.CallManager
import com.fitcoach.app.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Today at a glance, progress, what the coach knows and does, and data health - all read from the database. */
@Composable
fun DashboardScreen(app: FitCoachApp) {
    val ctx = LocalContext.current
    val version by app.dataVersion.collectAsState()
    var data by remember { mutableStateOf<DashboardData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var access by remember { mutableStateOf(emptyList<Pair<String, Boolean>>()) }
    LaunchedEffect(version) {
        val hc = runCatching { HealthSync.granted(ctx).size >= 4 }.getOrDefault(false)
        access = listOf(
            "Notifications" to Notifier.canPost(ctx), "Health Connect" to hc, "Walk/run detection" to ActivityTracker.hasPermission(ctx),
            "Background location" to Places.hasBackground(ctx), "Calendar" to CalendarReader.hasPermission(ctx),
            "Food-order notifications" to FoodNotificationListener.enabled(ctx), "Microphone" to CallManager.hasMic(ctx),
            "Full-screen calls" to CallManager.canFullScreen(ctx), "Telegram" to Telegram.isLinked(ctx),
        )
        withContext(Dispatchers.IO) {
            runCatching { Dashboard.build(app.service(), Instant.now()) }.onSuccess { data = it; error = null }.onFailure { error = it.message }
        }
    }
    val d = data
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenHeader("Today", subtitle = today, inset = 4.dp) {
                d?.agentNextCheck?.let { Pill("Next look $it", Fc.Violet) }
            }
        }
        if (d == null) { item { EmptyHint(error ?: "Loading…") }; return@LazyColumn }
        val h = d.headline
        item { PlanCard(app, d) }
        item { TodayRings(h, d) }
        item { GoalCard(d, h) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile("Adherence · 7d", h.adherence7Pct?.let { "$it" } ?: "–", Modifier.weight(1f), unit = h.adherence7Pct?.let { "%" },
                    caption = if (d.commitments.isEmpty()) "No habits yet" else "${d.commitments.size} habits", color = Fc.Accent)
                MetricTile("Replies to coach", h.answerRatePct?.let { "$it" } ?: "–", Modifier.weight(1f), unit = h.answerRatePct?.let { "%" },
                    caption = "last 7 days", color = Fc.Violet)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile("Food logged", "${h.foodDays7}", Modifier.weight(1f), unit = "/ 7", caption = "days this week", color = Fc.Orange)
                MetricTile("Coach acted", "${h.agentActed7}", Modifier.weight(1f), caption = "stayed quiet ${h.agentSilent7}×", color = Fc.Cyan)
            }
        }
        item {
            FcCard(title = "Steps", action = { Text("14 days", style = MaterialTheme.typography.labelMedium, color = Fc.TextMuted) }) {
                if (d.steps14.all { it.second == null }) EmptyHint("No step data yet - connect Health Connect in Settings.")
                else {
                    Bars(d.steps14.map { it.second?.toFloat() }, Fc.Cyan)
                    Text("Average ${h.stepsAvg7?.let { fmt(it) } ?: "–"} a day this week · ${d.steps14.count { it.second == null }} days without data",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            FcCard(title = "Food recorded", action = { Text("kcal ranges", style = MaterialTheme.typography.labelMedium, color = Fc.TextMuted) }) {
                RangeBars(d.kcal14.map { it.second?.toFloat() to it.third?.toFloat() })
                Text("${d.kcal14.count { it.second != null }} of 14 days logged · estimates from your messages", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (d.commitments.isNotEmpty()) item {
            FcCard(title = "Habits") {
                d.commitments.forEach { c ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(c.title, style = MaterialTheme.typography.titleSmall, color = Fc.Text)
                        if (c.schedule.isNotBlank()) Text(c.schedule, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { c.days.forEach { Day(it, Modifier.weight(1f)) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend("done", Fc.Good); Legend("smaller", Fc.Accent.copy(alpha = 0.6f)); Legend("missed", Fc.Bad); Legend("no report", Fc.SurfaceHigher)
                }
            }
        }
        item { BrainCard(d) }
        item { ActivityCard(d) }
        item { SourcesCard(d, access) { Scheduler.runNow(ctx) } }
    }
}

/** The agent's near-term intentions: what it may do, when, why, and what would cancel it. The person can mute any of them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanCard(app: FitCoachApp, d: DashboardData) {
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf(false) }
    fun io(block: () -> Unit) = scope.launch(Dispatchers.IO) { block(); app.notifyDataChanged() }
    FcCard(title = "Up next", action = { Text("next 24 h", style = MaterialTheme.typography.labelMedium, color = Fc.TextMuted) }) {
        if (d.pausedUntil != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Fc.Warn)
                Spacer(Modifier.width(8.dp))
                Text("Coaching paused until ${d.pausedUntil}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Fc.Text)
                Pill("Resume", Fc.Accent, onClick = { io { app.service().resume(Instant.now()) } })
            }
        }
        if (d.plan.isEmpty()) EmptyHint("Nothing planned. The coach re-plans as things happen, and stays quiet when nothing would help.")
        d.plan.forEach { p -> PlanRow(p) { muted -> io { app.service().mutePlanned(Instant.now(), p.id, muted) } } }
        Text("Coach looks again ${d.agentNextCheck ?: "soon"}" + (d.agentPlan?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
        if (d.pausedUntil == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Pause 2 h", Fc.TextMuted, onClick = { io { app.service().pause(Instant.now(), 2.0 / 24) } })
            Pill("Pause rest of today", Fc.TextMuted, onClick = {
                io {
                    val now = Instant.now()
                    val midnight = LocalDate.now(app.store.tz).plusDays(1).atStartOfDay(app.store.tz).toInstant()
                    app.service().pause(now, java.time.Duration.between(now, midnight).seconds / 86400.0)
                }
            })
        }
        if (d.planHistory.isNotEmpty()) {
            TextButton(onClick = { history = !history }, contentPadding = PaddingValues(0.dp)) {
                Text(if (history) "Hide recent changes" else "Recent changes (${d.planHistory.size})", color = Fc.Accent)
            }
            if (history) d.planHistory.forEach { p ->
                val color = when (p.status) { "done" -> Fc.Good; "cancelled" -> Fc.Violet; else -> Fc.TextFaint }
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("${p.status.replaceFirstChar { it.uppercase() }} ${p.eta} · ${p.title}", style = MaterialTheme.typography.labelMedium, color = Fc.Text)
                        p.resolution?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanRow(p: DashboardData.PlanItem, onMute: (Boolean) -> Unit) {
    val muted = p.status == "muted"
    val (icon, color) = when (p.channel) { "call" -> FcIcons.Phone to Fc.Violet; "telegram" -> FcIcons.Send to Fc.Cyan; else -> FcIcons.Chat to Fc.Accent }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Fc.SurfaceHigh).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = if (muted) 0.06f else 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = if (muted) Fc.TextFaint else color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = if (muted) Fc.TextMuted else Fc.Text)
                Pill(if (muted) "muted" else p.eta, when { muted -> Fc.TextFaint; p.status == "due" -> Fc.Warn; else -> color })
            }
            Text("${when (p.channel) { "call" -> "Voice call"; "telegram" -> "Telegram"; else -> "Notification" }} · ${p.window} · planned ${p.created}",
                style = MaterialTheme.typography.labelSmall, color = Fc.TextMuted)
            Text("Why: ${p.reason}", style = MaterialTheme.typography.bodySmall, color = Fc.Text)
            p.skipIf?.let { Text("Skips if: $it", style = MaterialTheme.typography.bodySmall) }
            p.note?.let { Text(it.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.bodySmall, color = Fc.TextFaint) }
            Row(Modifier.padding(top = 4.dp)) { Pill(if (muted) "Unmute" else "Mute", if (muted) Fc.Accent else Fc.TextMuted, onClick = { onMute(!muted) }) }
        }
    }
}

private fun fmt(n: Int) = String.format(Locale.ENGLISH, "%,d", n)

@Composable
private fun TodayRings(h: DashboardData.Headline, d: DashboardData) {
    FcCard(background = Fc.HeroGradient) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RingStat("Calories", h.kcalToday?.let { fmt(it) } ?: "–", h.kcalTarget?.let { "of ${fmt(it)}" } ?: "no target",
                h.kcalTarget?.let { t -> (h.kcalToday ?: 0) / t.toFloat() }, Fc.Orange)
            RingStat("Protein", h.proteinToday?.let { "${it}g" } ?: "–", h.proteinTarget?.let { "of ${it}g" } ?: "no target",
                h.proteinTarget?.let { t -> (h.proteinToday ?: 0) / t.toFloat() }, Fc.Accent)
            RingStat("Steps", h.stepsToday?.let { fmt(it) } ?: "–", h.stepsAvg7?.let { "avg ${fmt(it)}" } ?: "no data",
                h.stepsAvg7?.takeIf { it > 0 }?.let { a -> (h.stepsToday ?: 0) / a.toFloat() }, Fc.Cyan)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            h.sleepHours?.let { Pill("Sleep ${it}h", Fc.Violet) }
            d.today.firstOrNull { it.first == "Meals logged" }?.let { Pill(it.second.substringBefore(" ·").ifBlank { "no meals" }.let { m -> "Meals: $m" }, Fc.Orange) }
        }
    }
}

@Composable
private fun RingStat(label: String, value: String, caption: String, progress: Float?, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Ring(progress, color, size = 88.dp, stroke = 9.dp) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = Fc.Text, textAlign = TextAlign.Center)
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = Fc.Text)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = Fc.TextMuted)
    }
}

@Composable
private fun GoalCard(d: DashboardData, h: DashboardData.Headline) {
    FcCard(title = "Goal", action = { h.goalPct?.let { Pill("$it%", Fc.Accent) } }) {
        Text(d.goal ?: "No goal yet - just tell the coach what you want", style = MaterialTheme.typography.bodyLarge, color = Fc.Text)
        if (h.weightNow != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${h.weightNow}", style = MetricStyle)
                Text(" kg now", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = Fc.TextMuted)
                Spacer(Modifier.weight(1f))
                h.weightGoal?.let { Text("target $it kg", style = MaterialTheme.typography.labelLarge, color = Fc.TextMuted) }
            }
            h.goalPct?.let { ProgressBar(it / 100f, Fc.Accent) }
            if (d.weight.size >= 2) WeightChart(d.weight.map { it.second }, d.goalWeightKg)
            Text(d.weightTrend.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
        } else {
            EmptyHint("No weight data yet - tell the coach your weight, or sync a scale through Health Connect.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrainCard(d: DashboardData) {
    FcCard(title = "What your coach knows") {
        Sub("Remembers")
        if (d.longTerm.isEmpty() && d.temporary.isEmpty()) EmptyHint("Nothing yet - mention your routine, work or preferences in chat.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            d.longTerm.forEach { Chip(it, Fc.Violet) }
            d.temporary.forEach { Chip(it, Fc.Warn) }
        }
        Sub("Learned about coaching you")
        if (d.insights.isEmpty()) EmptyHint("Insights appear after a few days of messages.")
        d.insights.forEach { Bullet(it, Fc.Accent) }
        if (d.patterns.isNotEmpty()) { Sub("Patterns"); d.patterns.forEach { Bullet(it, Fc.Cyan) } }
        Text("Engagement: ${d.engagement}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActivityCard(d: DashboardData) {
    var all by remember { mutableStateOf(false) }
    FcCard(title = "Coach activity", action = { TextButton(onClick = { all = !all }) { Text(if (all) "Less" else "More", color = Fc.Accent) } }) {
        Sub("Messages and outcomes")
        if (d.interventions.isEmpty()) EmptyHint("No messages yet.")
        d.interventions.take(if (all) 15 else 3).forEach { TimelineItem(it, Fc.Accent) }
        Sub("Calls")
        if (d.calls.isEmpty()) EmptyHint("No calls yet. The coach calls only when it decides talking would help; tap the phone in Coach to call it.")
        d.calls.take(if (all) 8 else 2).forEach { TimelineItem(it, Fc.Violet) }
        Sub("Decisions")
        d.decisions.take(if (all) 25 else 4).forEach { TimelineItem(it, Fc.TextMuted) }
        if (all) {
            Sub("Observed")
            d.observations.forEach { TimelineItem(it, Fc.Cyan) }
        }
        Text("AI quota today: ${d.quota}", style = MaterialTheme.typography.labelSmall, color = Fc.TextFaint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesCard(d: DashboardData, access: List<Pair<String, Boolean>>, onSync: () -> Unit) {
    FcCard(title = "Data sources", action = { TextButton(onClick = onSync) { Text("Sync now", color = Fc.Accent) } }) {
        d.sources.forEach { s ->
            val color = when {
                s.label.startsWith("AI errors") -> if (s.ageMinutes == 0L) Fc.Good else Fc.Bad
                s.ageMinutes == null -> Fc.TextFaint
                s.ageMinutes < 24 * 60 -> Fc.Good
                else -> Fc.Warn
            }
            ListRow(s.label, leading = color) { Text(s.status, style = MaterialTheme.typography.labelMedium, color = Fc.TextMuted) }
        }
        Telegram.lastPollError?.let { Text("Telegram polling problem: $it", style = MaterialTheme.typography.bodySmall, color = Fc.Bad) }
        Sub("Access")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            access.forEach { (k, ok) -> Pill(if (ok) "✓ $k" else "✗ $k", if (ok) Fc.Good else Fc.TextMuted) }
        }
    }
}

@Composable
private fun Sub(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fc.TextMuted, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall, color = Fc.Text)
}

@Composable
private fun Bullet(text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Fc.Text)
    }
}

/** "dd MMM HH:mm · …\nmessage" rows from the engine, shown as a small timeline entry. */
@Composable
private fun TimelineItem(text: String, color: Color) {
    val head = text.substringBefore('\n')
    val body = text.substringAfter('\n', "")
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(head, style = MaterialTheme.typography.labelMedium, color = Fc.TextMuted)
            if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium, color = Fc.Text)
        }
    }
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Fc.TextMuted)
    }
}

@Composable
private fun Day(state: String, modifier: Modifier) {
    val c = when (state) {
        "done" -> Fc.Good; "smaller" -> Fc.Accent.copy(alpha = 0.6f)
        "skipped", "missed" -> Fc.Bad; "postponed" -> Fc.Warn
        "off" -> Color.Transparent; else -> Fc.SurfaceHigher
    }
    Box(modifier.height(18.dp).clip(RoundedCornerShape(5.dp)).background(c))
}

@Composable
private fun WeightChart(values: List<Double>, goal: Double?) {
    val trend = Progress.ewma(values)
    val all = values + listOfNotNull(goal)
    val lo = all.min() - 0.5; val hi = all.max() + 0.5
    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        fun x(i: Int) = if (values.size == 1) 0f else i * size.width / (values.size - 1)
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
        goal?.let {
            var gx = 0f
            while (gx < size.width) { drawLine(Fc.TextFaint, Offset(gx, y(it)), Offset(gx + 8f, y(it)), strokeWidth = 2f); gx += 16f }
        }
        val line = Path().apply { trend.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        val area = Path().apply { addPath(line); lineTo(x(trend.lastIndex), size.height); lineTo(x(0), size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(Fc.Accent.copy(alpha = 0.25f), Color.Transparent)))
        values.forEachIndexed { i, v -> drawCircle(Fc.TextMuted, 3f, Offset(x(i), y(v))) }
        drawPath(line, Fc.Accent, style = Stroke(width = 4f, cap = StrokeCap.Round))
    }
}

@Composable
private fun RangeBars(ranges: List<Pair<Float?, Float?>>) {
    val max = ranges.mapNotNull { it.second }.maxOrNull()?.takeIf { it > 0 } ?: 1f
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width / ranges.size
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.25f, w * 0.25f)
        ranges.forEachIndexed { i, (lo, hi) ->
            if (lo != null && hi != null) {
                drawRoundRect(Fc.Orange.copy(alpha = 0.3f), Offset(i * w + w * 0.18f, size.height * (1 - hi / max)), Size(w * 0.64f, size.height * hi / max), r)
                drawRoundRect(Fc.Orange, Offset(i * w + w * 0.18f, size.height * (1 - lo / max)), Size(w * 0.64f, size.height * lo / max), r)
            } else {
                drawRoundRect(Fc.SurfaceHigher, Offset(i * w + w * 0.18f, size.height - 4.dp.toPx()), Size(w * 0.64f, 4.dp.toPx()), r)
            }
        }
    }
}
