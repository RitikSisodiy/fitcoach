package com.fitcoach.app.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.notify.Notifier
import com.fitcoach.app.update.Updater
import com.fitcoach.app.work.Scheduler
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so system bar icons are always light.
        enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        val app = application as FitCoachApp
        setContent { FitCoachTheme { Root(app) } }
    }

    override fun onResume() {
        super.onResume()
        Notifier.cancel(this)
        Scheduler.runNow(this) // opening the app is a good moment to sync sensors
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(Tab("Coach", FcIcons.Chat), Tab("Today", FcIcons.Pulse), Tab("Settings", FcIcons.Sliders))

@Composable
private fun Root(app: FitCoachApp) {
    var tab by rememberSaveable { mutableIntStateOf(if (app.apiKey == null) 2 else 0) }
    Scaffold(
        containerColor = Fc.Bg,
        bottomBar = {
            NavigationBar(containerColor = Fc.Surface, tonalElevation = 0.dp) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, t.label, Modifier.size(24.dp)) },
                        label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Fc.OnAccent, selectedTextColor = Fc.Accent, indicatorColor = Fc.Accent,
                            unselectedIconColor = Fc.TextMuted, unselectedTextColor = Fc.TextMuted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().background(Fc.Bg).padding(padding).consumeWindowInsets(padding)) {
            UpdateBanner()
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ChatScreen(app)
                    1 -> DashboardScreen(app)
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp))
            .background(Fc.HeroGradient).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(FcIcons.Download, null, Modifier.size(20.dp), tint = Fc.Accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (status.isEmpty()) "Version ${rel.version} is ready" else status, style = MaterialTheme.typography.titleSmall, color = Fc.Text)
            if (status.isEmpty()) Text("You have ${Updater.currentVersion(ctx)}", style = MaterialTheme.typography.bodySmall)
        }
        FcButton("Update", enabled = status.isEmpty() || status.startsWith("Failed"), onClick = {
            if (!Updater.ensureInstallAllowed(ctx)) { status = ""; return@FcButton }
            scope.launch {
                status = "Downloading…"
                runCatching { Updater.downloadAndInstall(ctx, rel) { p -> status = "Downloading… $p%" } }
                    .onSuccess { status = "Installing…" }
                    .onFailure { status = "Failed: ${it.message}" }
            }
        })
    }
}
