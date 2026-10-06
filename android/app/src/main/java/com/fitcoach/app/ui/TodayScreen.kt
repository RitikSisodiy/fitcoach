package com.fitcoach.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.data.str
import com.fitcoach.app.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** What the coach knows today, and why it did (or did not) message - full transparency, no black box. */
@Composable
fun TodayScreen(app: FitCoachApp) {
    val ctx = LocalContext.current
    val version by app.dataVersion.collectAsState()
    var status by remember { mutableStateOf(listOf<String>()) }
    var patterns by remember { mutableStateOf(listOf<String>()) }
    var decisions by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            val now = Instant.now()
            status = runCatching { app.service().statusLines(now) }.getOrElse { listOf("Status unavailable: ${it.message}") }
            patterns = app.store.patterns(null).take(12).map { "${it.str("claim")} [${it.str("status")}]" }
            decisions = app.store.recentDecisions(40).map {
                "${app.store.fmtLocal(TimeUtil.parse(it.str("created_at")!!), "dd MMM HH:mm")}  ${it.str("kind")}: ${it.str("summary")}"
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card { Column(Modifier.padding(12.dp)) {
                Text("Today", style = MaterialTheme.typography.titleMedium)
                status.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            } }
        }
        item { Button(onClick = { Scheduler.runNow(ctx) }) { Text("Sync sensors and check now") } }
        item {
            Card { Column(Modifier.padding(12.dp)) {
                Text("Patterns the coach has noticed", style = MaterialTheme.typography.titleMedium)
                if (patterns.isEmpty()) Text("None yet - patterns need a couple of weeks of data.", style = MaterialTheme.typography.bodySmall)
                patterns.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text("Tell the coach in chat if one is wrong; it will be dropped.", style = MaterialTheme.typography.labelSmall)
            } }
        }
        item { Text("Coach decision log", style = MaterialTheme.typography.titleMedium) }
        items(decisions) { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
