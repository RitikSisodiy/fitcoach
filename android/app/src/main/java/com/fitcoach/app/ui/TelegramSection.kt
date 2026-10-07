package com.fitcoach.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fitcoach.app.FitCoachApp
import com.fitcoach.app.telegram.Telegram
import com.fitcoach.app.telegram.TelegramApi
import com.fitcoach.app.telegram.TelegramBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connect the user's own Telegram bot: same coach, same memory, same conversation as the app. */
@Composable
fun TelegramSection(app: FitCoachApp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by app.dataVersion.collectAsState() // the polling service bumps this after pairing
    val s = Telegram.settings(ctx)
    var token by remember { mutableStateOf(s.token ?: "") }
    var status by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    key(version, refresh) { FcCard(title = "Telegram") {
        run {
            Text("Optional: chat with the same coach from Telegram.", style = MaterialTheme.typography.bodySmall)
            when {
                s.chatId != null && s.token != null -> {
                    Text("✅ Connected to @${s.botUsername ?: "your bot"}. Chat here or there - it is one conversation, and the coach can reach you on Telegram.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        s.token = null; s.chatId = null; s.pairingCode = null; s.offset = 0
                        Telegram.stop(ctx); token = ""; status = ""; refresh++
                    }) { Text("Disconnect") }
                }
                s.token != null && s.pairingCode != null -> {
                    Text("Last step: open your bot and send this code (or tap the button):", style = MaterialTheme.typography.bodySmall)
                    Text("/start ${s.pairingCode}", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/${s.botUsername}?start=${s.pairingCode}")))
                    }) { Text("Open @${s.botUsername} in Telegram") }
                    OutlinedButton(onClick = { refresh++ }) { Text("I've sent it - refresh") }
                }
                else -> {
                    Text("Create a bot with @BotFather in Telegram, then paste its token here. The phone talks to the bot directly; " +
                        "no server is involved.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Bot token") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    Button(enabled = token.isNotBlank(), onClick = {
                        scope.launch {
                            status = "Checking token…"
                            val me = withContext(Dispatchers.IO) { runCatching { TelegramApi(token.trim()).getMe() } }
                            me.onSuccess {
                                s.token = token.trim(); s.botUsername = it.optString("username"); s.chatId = null; s.offset = 0
                                s.pairingCode = TelegramBridge.newPairingCode()
                                Telegram.ensureRunning(ctx)
                                status = ""; refresh++
                            }.onFailure { status = "Token not accepted: ${it.message}" }
                        }
                    }) { Text("Connect") }
                }
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    } }
}
