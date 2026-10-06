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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
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
    private var fileOk = true
    private val api = TelegramApi("T") { url, body, _ ->
        val method = url.substringAfterLast('/')
        when {
            url.contains("/file/bot") -> if (fileOk) 200 to byteArrayOf(9, 9, 9) else 404 to ByteArray(0)
            method == "getFile" -> 200 to """{"ok":true,"result":{"file_path":"photos/p.jpg"}}""".toByteArray()
            else -> {
                if (method == "sendMessage") sent += JSONObject(body!!)
                200 to """{"ok":true,"result":{"message_id":1}}""".toByteArray()
            }
        }
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

    private fun photo(id: Long, caption: String?) = JSONObject("""{"update_id":$id,"message":{"message_id":$id,"date":${Instant.now().epochSecond - 5},
        "chat":{"id":42},${caption?.let { "\"caption\":${JSONObject.quote(it)}," } ?: ""}"photo":[
        {"file_id":"small","width":90,"height":90},{"file_id":"mid","width":1280,"height":960},{"file_id":"huge","width":4000,"height":3000}]}}""")

    @Test fun photoGoesThroughTheMultimodalPipeline() = runTest {
        settings.chatId = 42; settings.pairingCode = null
        llm.extraction = """{"media_summary":"Poha with peanuts in a steel plate"}"""
        llm.reply = "Poha nice choice"
        assertEquals("handled photo: 1 replies", bridge.handle(photo(30, "breakfast")))
        assertEquals("mid", TelegramBridge.mediaOf(photo(1, null).getJSONObject("message"))!!.second.first) // largest within ~1.6 MP
        assertEquals(3, llm.lastExtractRequest!!.media.single().data.size)
        assertEquals("Poha nice choice", sent.last().getString("text"))
        assertTrue(llm.lastReplyPrompt.contains("sent a photo: Poha with peanuts"))
        assertTrue(store.one("SELECT text FROM messages WHERE direction = 'in'")!!.str("text")!!.contains("[photo] Poha with peanuts"))
    }

    @Test fun voiceNoteIsTranscribedAndAnswered() = runTest {
        settings.chatId = 42; settings.pairingCode = null
        llm.extraction = """{"media_summary":"Aaj gym nahi ja paunga, office late tak hai"}"""
        bridge.handle(JSONObject("""{"update_id":31,"message":{"message_id":31,"date":${Instant.now().epochSecond - 5},"chat":{"id":42},
            "voice":{"file_id":"v1","mime_type":"audio/ogg","duration":4}}}"""))
        assertEquals("audio/ogg", llm.lastExtractRequest!!.media.single().mimeType)
        assertTrue(llm.lastReplyPrompt.contains("(voice note) Aaj gym nahi ja paunga"))
    }

    @Test fun mediaFailureIsToldToTheUserNotSwallowed() = runTest {
        settings.chatId = 42; settings.pairingCode = null
        fileOk = false
        assertTrue(bridge.handle(photo(32, null)).startsWith("error"))
        assertTrue(sent.last().getString("text").contains("couldn't process that photo"))
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

    /**
     * Real media path: a photo and a voice note are uploaded to the user's chat with the bot (sendPhoto / sendVoice), and
     * the returned Telegram messages go through the real bridge: file download -> real Gemini (multimodal) -> memory ->
     * reply in Telegram. Needs GEMINI_API_KEY, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, TEST_PHOTO and TEST_VOICE (ogg or wav).
     */
    @Test fun liveTelegramPhotoAndVoice() = runBlocking<Unit> {
        val key = System.getenv("GEMINI_API_KEY"); val token = System.getenv("TELEGRAM_BOT_TOKEN")
        val chat = System.getenv("TELEGRAM_CHAT_ID")?.toLongOrNull()
        val photo = System.getenv("TEST_PHOTO")?.let(::File); val voice = System.getenv("TEST_VOICE")?.let(::File)
        assumeTrue("live media env not set", !key.isNullOrBlank() && !token.isNullOrBlank() && chat != null && photo?.exists() == true && voice?.exists() == true)
        val real = com.fitcoach.app.engine.CoachService(store, com.fitcoach.app.llm.GeminiProvider(key!!), File("src/main/assets/foods_seed.csv").inputStream()
            .use(FoodTable::load), Prompts.fromDir(File("src/main/assets/prompts")))
        val live = object : TelegramSettings {
            override var token: String? = token
            override var chatId: Long? = chat
            override var botUsername: String? = null
            override var pairingCode: String? = null
            override var offset: Long = 0
        }
        val bridge = TelegramBridge(TelegramApi(token!!), live) { real }
        val http = okhttp3.OkHttpClient()
        fun upload(method: String, field: String, f: File, mime: String, caption: String?): JSONObject {
            val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM).addFormDataPart("chat_id", chat.toString())
                .addFormDataPart(field, f.name, f.asRequestBody(mime.toMediaType()))
            caption?.let { body.addFormDataPart("caption", it) }
            val res = http.newCall(okhttp3.Request.Builder().url("https://api.telegram.org/bot$token/$method").post(body.build()).build()).execute()
            return JSONObject(res.body!!.string()).getJSONObject("result")
        }
        val log = StringBuilder()
        // The uploaded message stands in for one the user sent: same chat, same media structure.
        val p = upload("sendPhoto", "photo", photo!!, "image/jpeg", "(FitCoach automated test) lunch")
        log.append("photo -> ${bridge.handle(JSONObject().put("update_id", 900001).put("message", p))}\n")
        val v = if (voice!!.name.endsWith(".ogg")) upload("sendVoice", "voice", voice, "audio/ogg", null) else upload("sendDocument", "document", voice, "audio/wav", null)
        log.append("voice -> ${bridge.handle(JSONObject().put("update_id", 900002).put("message", v))}\n")
        store.query("SELECT direction, text FROM messages ORDER BY id").forEach { log.append("${it.str("direction")}: ${it.str("text")}\n") }
        File("build/live-telegram-media.txt").writeText(log.toString())
        println(log)
        val inbound = store.query("SELECT text FROM messages WHERE direction = 'in' ORDER BY id").map { it.str("text")!! }
        assertTrue("photo not understood: $inbound", inbound[0].contains("[photo]"))
        assertTrue("voice not transcribed: $inbound", inbound[1].length > 5)
        assertEquals(2, store.query("SELECT * FROM messages WHERE direction = 'out' AND kind = 'text'").size)
    }
}
