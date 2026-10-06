package com.fitcoach.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.core.strOrNull
import com.fitcoach.app.data.COACHING_MODES
import com.fitcoach.app.data.normalizeTag
import com.fitcoach.app.data.str
import com.fitcoach.app.notify.Notifier
import com.fitcoach.app.sensors.ActivityTracker
import com.fitcoach.app.sensors.CalendarReader
import com.fitcoach.app.sensors.FoodNotificationListener
import com.fitcoach.app.sensors.HealthSync
import com.fitcoach.app.sensors.Places
import com.fitcoach.app.sensors.ScreenTime
import com.fitcoach.app.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@SuppressLint("BatteryLife")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(app: FitCoachApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by app.dataVersion.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    fun bump() { refresh++; app.notifyDataChanged() }

    var key by remember { mutableStateOf(app.apiKey ?: "") }
    var hcGranted by remember { mutableStateOf(0) }
    var placeTag by remember { mutableStateOf("") }
    var placeMsg by remember { mutableStateOf("") }
    var places by remember { mutableStateOf(listOf<String>()) }
    var mode by remember { mutableStateOf("normal") }
    var paused by remember { mutableStateOf(false) }
    var quiet by remember { mutableStateOf("") }

    LaunchedEffect(version, refresh) {
        hcGranted = runCatching { HealthSync.granted(ctx).size }.getOrDefault(0)
        withContext(Dispatchers.IO) {
            places = app.store.places().map { "${it.str("tag")} (${it.str("label")})" }
            val p = app.store.profile()
            mode = p.optString("coaching_mode", "normal")
            if (quiet.isEmpty()) quiet = "${p.optString("quiet_start", "22:30")}-${p.optString("quiet_end", "07:30")}"
            paused = p.strOrNull("paused_until")?.let { Instant.parse(it).isAfter(Instant.now()) } ?: false
        }
    }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { bump(); Scheduler.runNow(ctx) }
    val bgLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bump(); Places.registerAll(ctx, app.store) }
    val hcPerms = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { bump(); Scheduler.runNow(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("FitCoach ${com.fitcoach.app.update.Updater.currentVersion(ctx)}", style = MaterialTheme.typography.labelMedium)
        Section("1. Gemini API key") {
            Text("Free key from aistudio.google.com. Stored only on this phone.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Button(onClick = { app.apiKey = key; bump() }) { Text(if (app.apiKey == null) "Save" else "Update") }
        }

        TelegramSection(app)

        Section("2. Let the coach see what the phone sees") {
            PermRow("Notifications (coach messages)", Notifier.canPost(ctx)) {
                if (Build.VERSION.SDK_INT >= 33) perms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            PermRow("Health Connect: steps, sleep, weight, workouts (${minOf(hcGranted, 4)}/4)", hcGranted >= 4) {
                if (HealthSync.available(ctx)) hcPerms.launch(HealthSync.PERMISSIONS + if (HealthSync.backgroundSupported(ctx)) setOf(HealthSync.BACKGROUND_PERMISSION) else emptySet())
                else ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
            }
            PermRow("Walk / run detection", ActivityTracker.hasPermission(ctx)) {
                perms.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
            }
            PermRow("Calendar (busy / free time only)", CalendarReader.hasPermission(ctx)) {
                perms.launch(arrayOf(Manifest.permission.READ_CALENDAR))
            }
            PermRow("Location (for saved places)", Places.hasPermission(ctx)) {
                perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            if (Places.hasPermission(ctx)) PermRow("Location in background: choose \"Allow all the time\"", Places.hasBackground(ctx)) {
                bgLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            PermRow("Food-order & UPI notifications", FoodNotificationListener.enabled(ctx)) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            Text("If Android says \"Restricted setting\": App info > ⋮ (top right) > Allow restricted settings, then try again. " +
                "Only Swiggy, Zomato, EatSure, UPI apps and bank SMS are read; amounts are kept, message text is not.",
                style = MaterialTheme.typography.bodySmall)
            PermRow("Screen time (late-night phone use)", ScreenTime.hasPermission(ctx)) {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            PermRow("Microphone (voice notes)", has(ctx, Manifest.permission.RECORD_AUDIO)) {
                perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            TextButton(onClick = { bump() }) { Text("Refresh status") }
        }

        Section("3. Keep the coach alive (OnePlus / Oppo / Realme / Vivo)") {
            val pm = ctx.getSystemService(PowerManager::class.java)
            PermRow("Battery: don't optimise FitCoach", pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }
            Text(
                "ColorOS / OxygenOS / Funtouch kill background apps aggressively. Also do this once:\n" +
                    "• Settings > Battery > (More settings / App battery management) > FitCoach > Allow background activity, Allow auto launch\n" +
                    "• Open Recent apps, long-press FitCoach > Lock (padlock)\n" +
                    "• Vivo: Settings > Battery > Background power consumption > FitCoach > Allow",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Open app settings") }
        }

        Section("4. Places") {
            Text("Stand at a place (gym, office, chess club, a chaat stall you visit) and save it. " +
                "Arrivals are noticed automatically and tie into your rules and patterns.", style = MaterialTheme.typography.bodySmall)
            places.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p, Modifier.weight(1f))
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { app.store.deletePlace(p.substringBefore(" (")); Places.registerAll(ctx, app.store); bump() } }) { Text("Remove") }
                }
            }
            OutlinedTextField(placeTag, { placeTag = it }, Modifier.fillMaxWidth(), label = { Text("Name, e.g. gym or chess club") }, singleLine = true)
            Button(enabled = placeTag.isNotBlank() && Places.hasPermission(ctx), onClick = {
                scope.launch {
                    placeMsg = "Getting location…"
                    val loc = Places.currentLocation(ctx)
                    placeMsg = if (loc == null) "Location unavailable - turn on GPS and try again." else {
                        withContext(Dispatchers.IO) {
                            app.store.addPlace(Instant.now(), placeTag, placeTag.trim(), loc.first, loc.second, Places.DEFAULT_RADIUS_M)
                            Places.registerAll(ctx, app.store)
                        }
                        "Saved '${normalizeTag(placeTag)}'." + if (!Places.hasBackground(ctx)) " Allow background location so arrivals are noticed." else ""
                    }
                    placeTag = ""; bump()
                }
            }) { Text("Save current location") }
            if (placeMsg.isNotEmpty()) Text(placeMsg, style = MaterialTheme.typography.bodySmall)
        }

        Section("5. Coaching style") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                COACHING_MODES.filter { it != "strong" }.forEach { m ->
                    FilterChip(selected = mode == m, onClick = { scope.launch(Dispatchers.IO) { app.service().setMode(Instant.now(), m); bump() } }, label = { Text(m) })
                }
            }
            Text("For stricter coaching, just ask the coach in chat.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(quiet, { quiet = it }, Modifier.weight(1f), label = { Text("Never message me between (HH:MM-HH:MM)") }, singleLine = true)
                TextButton(onClick = {
                    val parts = quiet.split("-").map { it.trim() }
                    val ok = parts.size == 2 && parts.all { runCatching { com.fitcoach.app.core.TimeUtil.hhmm(it) }.isSuccess }
                    if (ok) scope.launch(Dispatchers.IO) {
                        app.store.setProfile(Instant.now(), "quiet_start", parts[0]); app.store.setProfile(Instant.now(), "quiet_end", parts[1]); bump()
                    }
                }) { Text("Save") }
            }
            if (paused) Button(onClick = { scope.launch(Dispatchers.IO) { app.service().resume(Instant.now()); bump() } }) { Text("Resume coach") }
            else OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { app.service().pause(Instant.now(), 3.0); bump() } }) { Text("Pause for 3 days") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text((if (ok) "✅ " else "⬜ ") + label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok) TextButton(onClick = onFix) { Text("Allow") }
    }
}

/** Shown by Health Connect when the user taps the privacy policy link in its permission screen. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FitCoachTheme {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("How FitCoach uses your health data", style = MaterialTheme.typography.titleLarge)
                    Text("FitCoach reads steps, sleep, weight and exercise sessions to coach you. The data stays in the app's " +
                        "private storage on this phone. Short summaries (for example \"4,200 steps today\") are sent to Google " +
                        "Gemini with your own API key when the coach writes a message. Nothing is sold or shared otherwise.")
                    Button(onClick = { finish() }) { Text("OK") }
                }
            }
        }
    }
}
