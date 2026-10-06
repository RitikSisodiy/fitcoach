package com.fitcoach.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fitcoach.app.data.Db
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.str
import com.fitcoach.app.engine.CoachService
import com.fitcoach.app.engine.FoodTable
import com.fitcoach.app.engine.Inbound
import com.fitcoach.app.engine.Prompts
import com.fitcoach.app.telegram.TelegramApi
import com.fitcoach.app.telegram.TelegramBridge
import com.fitcoach.app.telegram.TelegramSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class TelegramTest {
    private lateinit var store: Store
    private lateinit var service: CoachService
    private val llm = ScriptedLlm()
    private val sent = mutableListOf<JSONObject>()
    private val settings = object : TelegramSettings {
        override var token: String? = "T"
        override var chatId: Long? = null
        override var botUsername: String? = "coachbot"
        override var pairingCode: String? = "123456"
        override var offset: Long = 0
    }
    private val api = TelegramApi("T") { url, body, _ ->
        val method = url.substringAfterLast('/')
        if (method == "sendMessage") sent += JSONObject(body!!)
        200 to """{"ok":true,"result":{"message_id":1}}""".toByteArray()
    }
    private lateinit var bridge: TelegramBridge

    private fun msg(id: Long, chat: Long, text: String) = JSONObject("""{"update_id":$id,"message":{"message_id":$id,"date":${Instant.now().epochSecond - 5},
        "chat":{"id":$chat},"text":${JSONObject.quote(text)}}}""")

    @Before fun setUp() {
        store = Store(Db(ApplicationProvider.getApplicationContext<Context>(), null).writableDatabase, ZoneId.of("Asia/Kolkata"))
        service = CoachService(store, llm, File("src/main/assets/foods_seed.csv").inputStream().use(FoodTable::load), Prompts.fromDir(File("src/main/assets/prompts")))
        bridge = TelegramBridge(api, settings) { service }
    }

    @Test fun pairingNeedsTheCodeAndThenOnlyThatChatIsAccepted() = runTest {
        assertEquals("ignored: wrong pairing code", bridge.handle(msg(1, 42, "/start 000000")))
        assertEquals("paired", bridge.handle(msg(2, 42, "/start 123456")))
        assertEquals(42L, settings.chatId)
        assertEquals("ignored: other chat", bridge.handle(msg(3, 99, "hello")))
        assertEquals(4L, settings.offset)
    }

    @Test fun telegramMessageUsesTheSharedBackendAndRepliesInTelegram() = runTest {
        settings.chatId = 42; settings.pairingCode = null
        llm.reply = "Got it"
        bridge.handle(msg(10, 42, "aaj 2 roti khayi"))
        assertEquals("Got it", sent.last().getString("text"))
        val inbound = store.one("SELECT * FROM messages WHERE direction = 'in'")!!
        assertEquals("telegram", inbound.str("channel"))
        assertEquals("tg:10", inbound.str("external_id"))
        bridge.handle(msg(10, 42, "aaj 2 roti khayi")) // redelivery is ignored
        assertEquals(1, store.query("SELECT * FROM messages WHERE direction = 'in'").size)
    }

    @Test fun quickReplyTapIsHandledAsTheUsersMessage() = runTest {
        settings.chatId = 42; settings.pairingCode = null
        llm.decide = """{"act":true,"reason":"r","intent":"meal_check","message":"Dinner?","quick_replies":["Usual","Bahar"],"channel":"telegram",
            "next_check_minutes":60,"next_check_reason":"x"}"""
        service.telegramLinked = { true }
        val evening = java.time.LocalDate.now().atTime(18, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant() // outside quiet hours
        val out = service.tick(evening, force = true).single()
        bridge.deliver(out)
        val keyboard = sent.last().getJSONObject("reply_markup").getJSONArray("inline_keyboard").getJSONArray(0)
        assertEquals("Bahar", keyboard.getJSONObject(1).getString("text"))
        val data = keyboard.getJSONObject(1).getString("callback_data")
        bridge.handle(JSONObject("""{"update_id":20,"callback_query":{"id":"cb1","data":"$data","message":{"message_id":5,"chat":{"id":42}}}}"""))
        assertEquals("Bahar", store.query("SELECT text FROM messages WHERE direction = 'in'").last().str("text"))
        assertEquals("answered", store.one("SELECT outcome FROM interventions")!!.str("outcome"))
    }

    /** Real Bot API: getMe, a message with quick replies to the paired chat, and the full bridge reply path. */
    @Test fun liveTelegramSend() = runBlocking<Unit> {
        val token = System.getenv("TELEGRAM_BOT_TOKEN"); val chat = System.getenv("TELEGRAM_CHAT_ID")?.toLongOrNull()
        assumeTrue("TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID not set", !token.isNullOrBlank() && chat != null)
        val real = TelegramApi(token!!)
        assertTrue(real.getMe().optString("username").isNotBlank())
        val live = object : TelegramSettings {
            override var token: String? = token
            override var chatId: Long? = chat
            override var botUsername: String? = null
            override var pairingCode: String? = null
            override var offset: Long = 0
        }
        llm.reply = "FitCoach test: the app's Telegram bridge can reach you. (automated check, you can ignore this)"
        val replies = service.handleMessage(Inbound("test", Instant.now(), channel = "telegram"))
        assertTrue(TelegramBridge(real, live) { service }.deliver(replies.single()))
    }
}
