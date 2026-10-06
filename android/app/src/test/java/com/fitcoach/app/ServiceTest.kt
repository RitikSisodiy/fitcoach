package com.fitcoach.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fitcoach.app.data.Db
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import com.fitcoach.app.engine.Coach
import com.fitcoach.app.engine.CoachService
import com.fitcoach.app.engine.Dashboard
import com.fitcoach.app.engine.FoodTable
import com.fitcoach.app.engine.Inbound
import com.fitcoach.app.engine.NotificationParser
import com.fitcoach.app.engine.Prompts
import com.fitcoach.app.llm.LlmException
import com.fitcoach.app.llm.LlmProvider
import com.fitcoach.app.llm.LlmRequest
import com.fitcoach.app.llm.LlmResponse
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Orchestration with a scripted LLM: storage, memory tiers, the agent loop's wake-up/limit/learning mechanics.
 * Whether the LLM's decisions are good is checked by LiveAgentTest against real Gemini.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceTest {
    private val tz = ZoneId.of("Asia/Kolkata")
    private lateinit var store: Store
    private lateinit var service: CoachService
    private val llm = ScriptedLlm()

    private fun at(day: String, hhmm: String): Instant = LocalDate.parse(day).atTime(LocalTime.parse(hhmm)).atZone(tz).toInstant()
    private fun act(message: String, quick: String = "[]", extra: String = "") =
        """{"act":true,"reason":"test","intent":"check_in","message":"$message","quick_replies":$quick,"channel":"telegram",
           "expected_outcome":"replies","next_check_minutes":120,"next_check_reason":"see if they replied"$extra}"""

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        store = Store(Db(ctx, null).writableDatabase, tz)
        val foods = File("src/main/assets/foods_seed.csv").inputStream().use(FoodTable::load)
        service = CoachService(store, llm, foods, Prompts.fromDir(File("src/main/assets/prompts")))
    }

    @Test fun messageLogsFoodAndReplies() = runTest {
        llm.extraction = """{"food_items":[{"name":"roti","quote":"2 roti","quantity":2,"unit":"piece","meal_slot":"lunch","eaten":"eaten","confidence":0.9},
            {"name":"dal","quote":"dal","quantity":1,"unit":"katori","meal_slot":"lunch","eaten":"eaten","confidence":0.9}]}"""
        val out = service.handleMessage(Inbound("lunch me 2 roti dal", at("2026-10-06", "14:00"), channel = "telegram"))
        assertEquals("Nice, logged.", out.single().text)
        assertEquals("telegram", out.single().channel)
        assertEquals(listOf("roti", "dal"), store.foodOn("2026-10-06").map { it.str("item_name") })
        assertEquals("telegram", store.one("SELECT channel FROM messages WHERE direction = 'in'")!!.str("channel"))
    }

    @Test fun memoryTiersLongTermAndTemporaryWithExpiry() = runTest {
        llm.extraction = """{"memories":[
            {"type":"long_term","category":"routine","key":"work_setup","value":"works from home, office on Tuesdays","quote":"WFH karta hu","confidence":0.9},
            {"type":"temporary","category":"health","key":"knee_pain","value":"knee pain, no gym","valid_days":3,"quote":"ghutne me dard","confidence":0.9},
            {"type":"long_term","category":"other","key":"made_up","value":"invented","quote":"not in the message","confidence":0.9}]}"""
        val now = at("2026-10-06", "10:00")
        service.handleMessage(Inbound("main WFH karta hu, aur abhi ghutne me dard hai", now))
        val mem = service.memory(now)
        assertEquals(1, mem.getJSONArray("long_term").length())
        assertEquals(1, mem.getJSONArray("temporary").length())
        assertEquals(0, service.memory(now.plusSeconds(4 * 86400)).getJSONArray("temporary").length())
        assertEquals(1, service.memory(now.plusSeconds(4 * 86400)).getJSONArray("long_term").length())
    }

    @Test fun asUsualLogsUsualMealItems() = runTest {
        store.setProfile(Instant.now(), "usual_meals", JSONObject("""{"lunch":[{"name":"roti","quantity":3,"unit":"piece"},{"name":"dal","quantity":1,"unit":"katori"}]}"""))
        llm.extraction = """{"food_items":[
            {"name":"roti","quote":"lunch usual tha","quantity":3,"unit":"piece","meal_slot":"lunch","eaten":"eaten","confidence":0.8,"from_usual_meal":true},
            {"name":"paneer","quote":"lunch usual tha","meal_slot":"lunch","eaten":"eaten","confidence":0.8,"from_usual_meal":true}]}"""
        service.handleMessage(Inbound("lunch usual tha", at("2026-10-06", "15:00")))
        assertEquals(listOf("roti"), store.foodOn("2026-10-06").map { it.str("item_name") }) // paneer is not a usual item
    }

    @Test fun agentDecidesAndRecordsIntervention() = runTest {
        llm.decide = act("Aaj walk hui?", """["Haan","Abhi jaunga"]""")
        val out = service.tick(at("2026-10-06", "18:00"))
        val o = out.single()
        assertEquals("notification", o.channel) // telegram not linked -> falls back
        assertEquals(listOf("Haan", "Abhi jaunga"), o.quickReplies)
        val iv = store.one("SELECT * FROM interventions")!!
        assertEquals("check_in", iv.str("intent"))
        assertEquals("replies", iv.str("expected_outcome"))
        assertNotNull(store.kv("agent_next_check_at"))
        assertTrue(llm.lastDecidePrompt.contains("\"limits\""))
    }

    @Test fun telegramChannelUsedWhenLinked() = runTest {
        service.telegramLinked = { true }
        llm.decide = act("Hi")
        assertEquals("telegram", service.tick(at("2026-10-06", "18:00")).single().channel)
    }

    @Test fun agentSleepsUntilItsOwnCheckTimeUnlessSomethingHappens() = runTest {
        llm.decide = """{"act":false,"reason":"nothing new","next_check_minutes":180,"next_check_reason":"after dinner"}"""
        service.tick(at("2026-10-06", "12:00"))
        assertEquals(1, llm.calls["decide"])
        service.tick(at("2026-10-06", "12:30")) // not due, nothing observed
        assertEquals(1, llm.calls["decide"])
        service.onPlaceEvent("gym", true, at("2026-10-06", "12:40"), at("2026-10-06", "12:40"))
        service.tick(at("2026-10-06", "12:41")) // significant observation wakes the agent
        assertEquals(2, llm.calls["decide"])
        assertTrue(llm.lastDecidePrompt.contains("arrived at gym"))
        service.tick(at("2026-10-06", "15:45")) // its own scheduled check (12:41 + 180 min)
        assertEquals(3, llm.calls["decide"])
    }

    @Test fun quietHoursAndPauseSkipEvaluation() = runTest {
        llm.decide = act("x")
        assertTrue(service.tick(at("2026-10-06", "23:30")).isEmpty())
        service.pause(at("2026-10-06", "10:00"), 2.0)
        assertTrue(service.tick(at("2026-10-07", "18:00")).isEmpty())
        assertEquals(null, llm.calls["decide"])
    }

    @Test fun limitsBlockSendingButTheDecisionIsLogged() = runTest {
        service.setMode(Instant.now(), "gentle") // daily cap 1
        llm.decide = act("first")
        assertEquals(1, service.tick(at("2026-10-06", "10:00")).size)
        llm.decide = act("second")
        assertTrue(service.tick(at("2026-10-06", "14:00"), force = true).isEmpty())
        assertTrue(store.recentDecisions(5).any { it.str("kind") == "agent_blocked" })
        assertTrue(llm.lastDecidePrompt.contains("daily cap reached"))
    }

    @Test fun replyAndIgnoreAreEvaluatedAsOutcomes() = runTest {
        llm.decide = act("Dinner me kya tha?", """["Usual","Bahar khaya"]""")
        service.tick(at("2026-10-06", "20:00"))
        service.handleMessage(Inbound("Usual", at("2026-10-06", "20:12"), channel = "notification"))
        val iv = store.one("SELECT * FROM interventions")!!
        assertEquals("answered", iv.str("outcome"))
        assertEquals(12.0, iv.str("response_minutes")!!.toDouble(), 0.1)

        llm.decide = act("Kal walk?")
        service.tick(at("2026-10-07", "09:00"), force = true)
        llm.decide = """{"act":false,"reason":"wait","next_check_minutes":240,"next_check_reason":"x"}"""
        service.tick(at("2026-10-07", "12:30"))
        assertEquals("ignored", store.query("SELECT outcome FROM interventions ORDER BY id DESC LIMIT 1").single().str("outcome"))
    }

    @Test fun observedWalkMarksCommitmentAndCreditsTheMessage() = runTest {
        val cid = store.addCommitment(Instant.now(), mapOf("kind" to "habit", "title" to "Walk after lunch", "action" to "15 min walk",
            "schedule_days" to "daily", "window_start" to "14:00", "window_end" to "16:00", "activity_kind" to "walk"),
            listOf("15 min walk", "5 min walk"))
        llm.decide = act("Lunch ke baad 15 min walk?", extra = ""","commitment_id":$cid""")
        service.tick(at("2026-10-06", "14:05"))
        service.onActivitySession("WALKING", at("2026-10-06", "14:20"), at("2026-10-06", "14:38"), at("2026-10-06", "14:40"))
        llm.decide = """{"act":false,"reason":"done","next_check_minutes":240,"next_check_reason":"evening"}"""
        service.tick(at("2026-10-06", "14:45"))
        assertEquals("done", store.commitmentOutcome(cid, "2026-10-06"))
        assertEquals("achieved", store.one("SELECT outcome FROM interventions")!!.str("outcome"))
        assertTrue(store.slotStats(cid).isNotEmpty())
    }

    @Test fun reflectionTurnsOutcomesIntoCoachInsights() = runTest {
        llm.decide = act("ping")
        for (d in listOf("2026-10-03", "2026-10-04", "2026-10-05")) service.tick(at(d, "09:00"), force = true)
        llm.reflect = """{"insights":[{"key":"morning_ignored","insight":"Ignores morning notifications","evidence":"0/3 answered at 09:00","confidence":0.6}],
            "summary":"mornings do not work"}"""
        service.tick(at("2026-10-06", "12:00"))
        assertEquals(1, llm.calls["reflect"])
        val insights = service.memory(at("2026-10-06", "12:00")).getJSONArray("coach_insights")
        assertTrue(insights.getString(0).contains("Ignores morning notifications"))
        assertTrue(llm.lastDecidePrompt.contains("Ignores morning notifications")) // the agent sees what it learned
    }

    @Test fun aiDownMeansNoTemplatesOnlyAStatusLine() = runTest {
        llm.fail = true
        assertTrue(service.tick(at("2026-10-06", "18:00")).isEmpty())
        assertTrue(store.recentDecisions(3).any { it.str("kind") == "agent_error" })
        val reply = service.handleMessage(Inbound("hi", at("2026-10-06", "18:05"))).single()
        assertEquals(Coach.AI_UNAVAILABLE, reply.text)
        assertEquals(1, store.query("SELECT * FROM messages WHERE direction = 'in'").size) // the message is still remembered
    }

    @Test fun unsafeMessagesAreNeverSent() = runTest {
        llm.decide = act("You are so lazy, skip all meals tomorrow")
        assertTrue(service.tick(at("2026-10-06", "18:00")).isEmpty())
        assertEquals(2, llm.calls["decide"]) // one retry with feedback, then silence
    }

    @Test fun upiNotificationIsObservedAndOfferedToTheAgent() = runTest {
        val t = at("2026-10-06", "18:00")
        assertTrue(NotificationParser.ingest(store, "com.phonepe.app", "Paid ₹40", "Paid to Sharma Chaat Corner", t, t).startsWith("stored"))
        store.addObservation(t, "payment_or_order", "small payment")
        llm.decide = """{"act":false,"reason":"wait","next_check_minutes":60,"next_check_reason":"x"}"""
        service.tick(at("2026-10-06", "18:05"))
        assertTrue(llm.lastDecidePrompt.contains("Sharma Chaat Corner"))
    }

    @Test fun dashboardReflectsDatabase() = runTest {
        val empty = Dashboard.build(service, at("2026-10-06", "10:00"))
        assertNull(empty.goal)
        store.setProfile(Instant.now(), "goal_text", "lose 5 kg")
        store.addMetric(Instant.now(), at("2026-10-05", "08:00"), "weight_kg", 82.0, "user_reported")
        store.addMetric(Instant.now(), at("2026-10-06", "08:00"), "weight_kg", 81.6, "user_reported")
        llm.decide = act("hello")
        service.tick(at("2026-10-06", "10:00"))
        val d = Dashboard.build(service, at("2026-10-06", "10:30"))
        assertEquals("lose 5 kg", d.goal)
        assertEquals(2, d.weight.size)
        assertTrue(d.interventions.single().contains("hello"))
        assertTrue(d.decisions.any { it.contains("agent_act") })
        assertFalse(d.agentNextCheck.isNullOrBlank())
    }

    @Test fun migrationFromVersion1KeepsDataAndAddsColumns() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = File(ctx.cacheDir, "v1.db").also { it.delete() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            Db.SCHEMA.forEach { db.execSQL(it) }
            db.execSQL("INSERT INTO messages(direction, kind, text, created_at) VALUES ('in','text','old','2026-10-01T10:00:00Z')")
            db.version = 1
        }
        val db = Db(ctx, f.absolutePath).writableDatabase
        val s = Store(db, tz)
        assertEquals("app", s.one("SELECT channel FROM messages")!!.str("channel"))
        s.setKv(Instant.now(), "k", "v")
        assertEquals("v", s.kv("k"))
        assertTrue(s.addObservation(Instant.now(), "x", "y") > 0)
        assertTrue(s.insert("calls", mapOf("status" to "ringing", "rang_at" to "2026-10-01T10:00:00Z")) > 0) // v3
        assertEquals(0L, s.one("SELECT COUNT(*) AS n FROM food_events WHERE off_plan = 1")!!.long("n"))
        db.close()
    }

    // ----------------------------------------------------------- voice calls
    private fun callDecision(message: String) = act(message).replace("\"channel\":\"telegram\"", "\"channel\":\"call\"")

    @Test fun agentCallsAndTheConversationBecomesMemory() = runTest {
        service.callsSupported = { true }
        llm.decide = callDecision("Aaj ka plan short call pe fix karte hain?")
        val ring = service.tick(at("2026-10-06", "18:00")).single()
        assertEquals("call", ring.channel)
        val id = ring.callId!!
        assertEquals("ringing", service.call(id)!!.str("status"))
        assertTrue(service.callInstruction(at("2026-10-06", "18:00"), id).contains("Aaj ka plan short call pe fix karte hain?"))

        service.setCallStatus(at("2026-10-06", "18:01"), id, "answered")
        llm.extraction = """{"memories":[{"type":"long_term","category":"routine","key":"evening_class","value":"has a guitar class on Monday evenings",
            "quote":"guitar class hai","confidence":0.9}]}"""
        service.ingestCall(at("2026-10-06", "18:06"), id, listOf("coach" to "Hi! Aaj shaam ka kya plan hai?", "user" to "Aaj guitar class hai, walk kal karunga"),
            "User has a guitar class tonight; walk moved to tomorrow.", "gemini-3.8-live")
        assertTrue(llm.lastExtractRequest!!.userText.contains("coach: Hi! Aaj shaam"))
        assertEquals("ended", service.call(id)!!.str("status"))
        assertEquals(2, store.query("SELECT * FROM messages WHERE channel = 'call' AND kind = 'call'").size)
        assertEquals("answered", store.one("SELECT outcome FROM interventions")!!.str("outcome"))
        assertEquals(1, service.memory(at("2026-10-06", "18:06")).getJSONArray("long_term").length())

        llm.decide = """{"act":false,"reason":"just talked","next_check_minutes":240,"next_check_reason":"x"}"""
        service.tick(at("2026-10-06", "18:10")) // the call is a significant observation: the agent re-evaluates
        assertTrue(llm.lastDecidePrompt.contains("walk moved to tomorrow"))
    }

    @Test fun callsNeedPermissionAndAreRare() = runTest {
        llm.decide = callDecision("talk?")
        assertEquals("notification", service.tick(at("2026-10-06", "10:00")).single().channel) // calls not supported -> text
        service.callsSupported = { true }
        assertTrue(llm.lastDecidePrompt.contains("\"can_call_now\": false"))
        assertEquals("call", service.tick(at("2026-10-06", "12:00"), force = true).single().channel)
        service.tick(at("2026-10-06", "15:00"), force = true)
        assertTrue(llm.lastDecidePrompt.contains("\"calls_today\": 1"))
        assertEquals(1, store.query("SELECT * FROM calls").size) // second call the same day falls back to text
    }

    @Test fun missedCallIsAnObservationForTheAgent() = runTest {
        service.callsSupported = { true }
        llm.decide = callDecision("quick call?")
        val id = service.tick(at("2026-10-06", "18:00")).single().callId!!
        llm.decide = """{"act":false,"reason":"wait","next_check_minutes":240,"next_check_reason":"x"}"""
        service.tick(at("2026-10-06", "18:05"))
        assertEquals("missed", service.call(id)!!.str("status"))
        assertTrue(llm.lastDecidePrompt.contains("missed the coach's voice call"))
    }

    // ------------------------------------------------- media and generic inference
    @Test fun photoIsDescribedRememberedAndReplied() = runTest {
        llm.extraction = """{"media_summary":"A plate with two rotis and a bowl of dal","food_items":[
            {"name":"roti","quote":"two rotis","quantity":2,"unit":"piece","meal_slot":"lunch","eaten":"eaten","confidence":0.7}]}"""
        service.handleMessage(Inbound("", at("2026-10-06", "13:30"), "photo", listOf(com.fitcoach.app.llm.MediaPart(byteArrayOf(1, 2, 3), "image/jpeg"))))
        assertEquals(1, llm.lastExtractRequest!!.media.size)
        assertTrue(store.one("SELECT text FROM messages WHERE direction = 'in'")!!.str("text")!!.contains("[photo] A plate with two rotis"))
        assertTrue(llm.lastReplyPrompt.contains("sent a photo: A plate with two rotis"))
        assertEquals(listOf("roti"), store.foodOn("2026-10-06").map { it.str("item_name") })
    }

    @Test fun mealTimeAndOffPlanComeFromTheLlmNotFromTables() = runTest {
        llm.extraction = """{"food_items":[{"name":"samosa","quote":"samosa","quantity":2,"unit":"piece","meal_slot":"snack","eaten":"eaten",
            "confidence":0.9,"off_plan":true,"eaten_at":"16:30"}]}"""
        service.handleMessage(Inbound("4:30 baje 2 samosa kha liye", at("2026-10-06", "19:00")))
        val f = store.foodOn("2026-10-06").single()
        assertEquals(1L, f.long("off_plan"))
        assertTrue(f.str("occurred_at")!!.startsWith("2026-10-06T11:00")) // 16:30 IST
    }

    @Test fun agentSettlesACommitmentFromObservations() = runTest {
        val cid = store.addCommitment(Instant.now(), mapOf("kind" to "habit", "title" to "Gym", "action" to "go to the gym",
            "schedule_days" to "daily"), listOf("go to the gym"))
        llm.decide = """{"act":false,"reason":"left the gym after 70 min","next_check_minutes":240,"next_check_reason":"x",
            "commitment_outcomes":[{"commitment_id":$cid,"outcome":"done","evidence":"stayed at gym 70 min"},
            {"commitment_id":999,"outcome":"done","evidence":"unknown id"}]}"""
        service.tick(at("2026-10-06", "20:00"))
        assertEquals("done", store.commitmentOutcome(cid, "2026-10-06"))
        assertTrue(store.recentDecisions(5).any { it.str("kind") == "agent_inferred_outcome" })
    }

    @Test fun dashboardKpisAndSourcesComeFromData() = runTest {
        store.setProfile(Instant.now(), "goal_weight_kg", 76.0)
        store.addMetric(Instant.now(), at("2026-10-01", "08:00"), "weight_kg", 80.0, "user_reported")
        store.addMetric(Instant.now(), at("2026-10-06", "08:00"), "weight_kg", 79.0, "user_reported")
        service.tick(at("2026-10-06", "10:00"))
        val d = Dashboard.build(service, at("2026-10-06", "10:30"))
        val kpi = d.kpis.toMap()
        assertTrue(kpi["Goal progress"]!!.contains("target 76.0"))
        assertEquals("0/7 days", kpi["Food logging, 7 days"])
        assertEquals("no data", kpi["Steps, 7-day average"])
        assertEquals("30 min ago", d.sources.toMap()["Background loop"])
        assertEquals("never", d.sources.toMap()["Health Connect"])
    }
}

/** Canned JSON per purpose; counts calls and records the last agent prompt. */
class ScriptedLlm : LlmProvider {
    override val name = "scripted"
    var extraction = "{}"
    var decide = """{"act":false,"reason":"nothing useful","next_check_minutes":120,"next_check_reason":"later"}"""
    var reflect = """{"insights":[],"summary":"none"}"""
    var reply = "Nice, logged."
    var fail = false
    var lastDecidePrompt = ""
    var lastReplyPrompt = ""
    var lastExtractRequest: LlmRequest? = null
    val calls = mutableMapOf<String, Int>()

    override suspend fun generate(request: LlmRequest): LlmResponse {
        calls[request.purpose] = (calls[request.purpose] ?: 0) + 1
        if (fail) throw LlmException("down")
        val text = when (request.purpose) {
            "extract" -> { lastExtractRequest = request; extraction }
            "decide" -> { lastDecidePrompt = request.userText; decide }
            "reflect" -> reflect
            else -> { lastReplyPrompt = request.userText; reply }
        }
        return LlmResponse(text, name, "scripted")
    }
}
