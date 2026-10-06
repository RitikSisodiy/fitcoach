package com.fitcoach.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.engine.Dashboard
import com.fitcoach.app.engine.DashboardData
import com.fitcoach.app.engine.Progress
import com.fitcoach.app.work.Scheduler
import com.fitcoach.app.notify.Notifier
import com.fitcoach.app.sensors.ActivityTracker
import com.fitcoach.app.sensors.CalendarReader
import com.fitcoach.app.sensors.FoodNotificationListener
import com.fitcoach.app.sensors.HealthSync
import com.fitcoach.app.sensors.Places
import com.fitcoach.app.telegram.Telegram
import com.fitcoach.app.voice.CallManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** Current state, progress, memory, learning and the agent's decisions - all read from the database. */
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
            "Food-order notifications" to FoodNotificationListener.enabled(ctx), "Microphone (calls)" to CallManager.hasMic(ctx),
            "Full-screen calls" to CallManager.canFullScreen(ctx), "Telegram linked" to Telegram.isLinked(ctx),
        )
        withContext(Dispatchers.IO) {
            runCatching { Dashboard.build(app.service(), Instant.now()) }.onSuccess { data = it; error = null }.onFailure { error = it.message }
        }
    }
    val d = data
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (d == null) { item { Text(error ?: "Loading…") }; return@LazyColumn }
        item {
            Section("Now") {
                Text(d.goal?.let { "Goal: $it" + (d.goalWeightKg?.let { w -> " (target $w kg)" } ?: "") } ?: "Goal: not known yet - just tell the coach")
                Text("Coaching: ${d.mode}" + (d.pausedUntil?.let { " · paused until $it" } ?: ""))
                Text("Agent: next look ${d.agentNextCheck ?: "soon"}" + (d.agentPlan?.let { " - $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Section("Progress") { d.kpis.forEach { (k, v) -> Row { Text("$k: ", style = MaterialTheme.typography.labelLarge); Text(v) } } }
        }
        item { Section("Today") { d.today.forEach { (k, v) -> Row { Text("$k: ", style = MaterialTheme.typography.labelLarge); Text(v) } } } }
        item {
            Section("Weight") {
                Text(d.weightTrend, style = MaterialTheme.typography.bodySmall)
                if (d.weight.size >= 2) WeightChart(d.weight.map { it.second }, d.goalWeightKg)
            }
        }
        item {
            Section("Steps, last 14 days") {
                Bars(d.steps14.map { it.second?.toFloat() }, MaterialTheme.colorScheme.primary)
                Text("Days without data: ${d.steps14.count { it.second == null }}", style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            Section("Food recorded, last 14 days (kcal ranges)") {
                RangeBars(d.kcal14.map { it.second?.toFloat() to it.third?.toFloat() })
                Text("Days with food logged: ${d.kcal14.count { it.second != null }}/14 · ranges are estimates", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (d.commitments.isNotEmpty()) item {
            Section("Commitments, last 14 days") {
                d.commitments.forEach { c ->
                    Text(c.title, style = MaterialTheme.typography.labelLarge)
                    if (c.schedule.isNotBlank()) Text(c.schedule, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { c.days.forEach { Dot(it) } }
                }
                Text("green done · light green smaller · red skipped/missed · grey no report", style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            Section("What the coach remembers") {
                Bullets("Long-term", d.longTerm, "nothing yet - mention your routine, work, preferences in chat")
                Bullets("Right now (temporary)", d.temporary, "nothing temporary")
            }
        }
        item {
            Section("What the coach has learned about coaching you") {
                Bullets(null, d.insights, "Not enough evidence yet - insights appear after a few days of messages")
                Text("Engagement: ${d.engagement}", style = MaterialTheme.typography.bodySmall)
                if (d.responseByHour.isNotEmpty()) Text("Replies by hour: " + d.responseByHour.joinToString { "${it.first} ${it.second}" }, style = MaterialTheme.typography.bodySmall)
                Bullets("Patterns", d.patterns, "none yet")
            }
        }
        item { Section("Messages the coach sent, and what happened") { Bullets(null, d.interventions, "none yet") } }
        item { Section("Voice calls") { Bullets(null, d.calls, "no calls yet - the coach calls only when it decides talking would help; 📞 in Chat to call it") } }
        item { Section("What the coach observed") { Bullets(null, d.observations, "nothing yet") } }
        item {
            Section("Agent decisions") {
                Bullets(null, d.decisions, "none yet")
                Text("AI quota: ${d.quota}", style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            Section("Data sources") {
                d.sources.forEach { (k, v) -> Row { Text("$k: ", style = MaterialTheme.typography.labelLarge); Text(v) } }
                val poll = Telegram.lastPollAt
                if (Telegram.isLinked(ctx)) Text("Telegram polling: " + (Telegram.lastPollError?.let { "failing ($it)" }
                    ?: if (poll == 0L) "not running in this app session" else "ok, ${(System.currentTimeMillis() - poll) / 60_000} min ago"))
                Text("Access: " + access.joinToString { "${it.first} ${if (it.second) "✓" else "✗"}" }, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { Scheduler.runNow(ctx) }) { Text("Sync sensors now") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Bullets(title: String?, items: List<String>, empty: String) {
    if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
    if (items.isEmpty()) Text(empty, style = MaterialTheme.typography.bodySmall)
    items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Dot(state: String) {
    val c = when (state) {
        "done" -> Color(0xFF2E7D32); "smaller" -> Color(0xFF81C784)
        "skipped", "missed" -> Color(0xFFC62828); "postponed" -> Color(0xFFF9A825)
        "off" -> Color.Transparent; else -> Color(0xFFBDBDBD)
    }
    Box(Modifier.size(14.dp).clip(CircleShape).background(c))
}

@Composable
private fun WeightChart(values: List<Double>, goal: Double?) {
    val line = MaterialTheme.colorScheme.primary
    val dots = MaterialTheme.colorScheme.outline
    val trend = Progress.ewma(values)
    val all = values + listOfNotNull(goal)
    val lo = all.min() - 0.5; val hi = all.max() + 0.5
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        fun x(i: Int) = if (values.size == 1) 0f else i * size.width / (values.size - 1)
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
        goal?.let { drawLine(Color(0xFF9E9E9E), Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 2f) }
        values.forEachIndexed { i, v -> drawCircle(dots, 4f, Offset(x(i), y(v))) }
        val p = Path().apply { trend.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        drawPath(p, line, style = Stroke(width = 4f))
    }
    Text("dots: weigh-ins · line: smoothed trend" + (goal?.let { " · grey: goal" } ?: ""), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun Bars(values: List<Float?>, color: Color) {
    val max = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1f
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val w = size.width / values.size
        values.forEachIndexed { i, v ->
            if (v != null) {
                val h = size.height * (v / max)
                drawRect(color, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h))
            }
        }
    }
}

@Composable
private fun RangeBars(ranges: List<Pair<Float?, Float?>>) {
    val color = MaterialTheme.colorScheme.secondary
    val max = ranges.mapNotNull { it.second }.maxOrNull()?.takeIf { it > 0 } ?: 1f
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val w = size.width / ranges.size
        ranges.forEachIndexed { i, (lo, hi) ->
            if (lo != null && hi != null) {
                drawRect(color.copy(alpha = 0.35f), Offset(i * w + w * 0.15f, size.height * (1 - hi / max)), Size(w * 0.7f, size.height * (hi - lo) / max))
                drawRect(color, Offset(i * w + w * 0.15f, size.height * (1 - lo / max)), Size(w * 0.7f, size.height * lo / max))
            }
        }
    }
}
