package com.fitcoach.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.notify.Notifier
import com.fitcoach.app.update.Updater
import kotlinx.coroutines.launch
import com.fitcoach.app.work.Scheduler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FitCoachApp
        setContent { FitCoachTheme { Root(app) } }
    }

    override fun onResume() {
        super.onResume()
        Notifier.cancel(this)
        Scheduler.runNow(this) // opening the app is a good moment to sync sensors
    }
}

private val Green = Color(0xFF2E7D32)

@Composable
fun FitCoachTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF81C784), secondary = Color(0xFFA5D6A7))
    else lightColorScheme(primary = Green, secondary = Color(0xFF558B2F))
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun Root(app: FitCoachApp) {
    var tab by rememberSaveable { mutableIntStateOf(if (app.apiKey == null) 2 else 0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("Chat", "Today", "Setup").forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("💬", "📊", "⚙️")[i]) }, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            UpdateBanner()
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ChatScreen(app)
                    1 -> TodayScreen(app)
                    else -> SetupScreen(app)
                }
            }
        }
    }
}

/** Shown when GitHub has a newer release; downloads and installs it in place. */
@Composable
private fun UpdateBanner() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf(Updater.available(ctx)) }
    var status by remember { mutableStateOf("") }
    // Opening the app checks almost every time; the background worker checks every few hours.
    LaunchedEffect(Unit) { release = Updater.check(ctx, Updater.APP_OPEN_INTERVAL_MS) }
    val rel = release ?: return
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status.ifEmpty { "FitCoach ${rel.version} is available" }, Modifier.weight(1f))
            Button(enabled = status.isEmpty() || status.startsWith("Failed"), onClick = {
                if (!Updater.ensureInstallAllowed(ctx)) { status = ""; return@Button }
                scope.launch {
                    status = "Downloading…"
                    runCatching { Updater.downloadAndInstall(ctx, rel) { p -> status = "Downloading… $p%" } }
                        .onSuccess { status = "Installing…" }
                        .onFailure { status = "Failed: ${it.message}" }
                }
            }) { Text("Update") }
        }
    }
}
