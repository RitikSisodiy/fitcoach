package com.fitcoach.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.update.Updater
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Check for updates: current and latest version, last check, problems, and a one-tap update. */
@Composable
fun UpdatesSection(app: FitCoachApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by app.dataVersion.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(-1) }
    key(version, refresh) {
        val st = Updater.status(ctx)
        FcCard(title = "App updates") {
            run {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricTile("Installed", st.current, Modifier.weight(1f), color = Fc.TextMuted)
                    MetricTile("Latest", st.latest ?: "–", Modifier.weight(1f), color = if (st.updateAvailable) Fc.Accent else Fc.Good,
                        caption = if (st.checkedAt > 0) "checked " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(st.checkedAt)) else "not checked yet")
                }
                when {
                    st.updateAvailable -> Pill("Update available", Fc.Accent)
                    st.latest != null -> Pill("You're up to date", Fc.Good)
                    else -> Text("Tap Check now to look for updates.", style = MaterialTheme.typography.bodySmall)
                }
                st.error?.let { Text("Last check problem: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                st.installError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (busy.isNotEmpty()) {
                    Text(busy, style = MaterialTheme.typography.bodySmall)
                    if (progress >= 0) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = busy.isEmpty(), onClick = {
                        scope.launch { busy = "Checking…"; Updater.check(ctx, 0L); busy = ""; refresh++ }
                    }) { Text("Check now") }
                    if (st.updateAvailable) Button(enabled = busy.isEmpty() || busy.startsWith("Failed"), onClick = {
                        val rel = Updater.available(ctx) ?: return@Button
                        if (!Updater.ensureInstallAllowed(ctx)) { busy = "Allow \"Install unknown apps\" for FitCoach, then tap Update again."; return@Button }
                        scope.launch {
                            busy = "Downloading ${rel.version}…"; progress = 0
                            runCatching { Updater.downloadAndInstall(ctx, rel) { p -> progress = p } }
                                .onSuccess { busy = "Installing - confirm in the system dialog."; progress = -1 }
                                .onFailure { busy = "Failed: ${it.message}"; progress = -1 }
                        }
                    }) { Text("Update to ${st.latest}") }
                }
                TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_PAGE))) }) { Text("Release notes") }
            }
        }
    }
}
