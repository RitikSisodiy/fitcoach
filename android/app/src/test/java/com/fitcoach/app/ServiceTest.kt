package com.fitcoach.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.fitcoach.app.data.Db
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.str
import com.fitcoach.app.engine.CoachService
import com.fitcoach.app.engine.FoodTable
import com.fitcoach.app.engine.Inbound
import com.fitcoach.app.engine.NotificationParser
import com.fitcoach.app.engine.Prompts
import com.fitcoach.app.engine.SlotLearner
import com.fitcoach.app.llm.LlmProvider
import com.fitcoach.app.llm.LlmRequest
import com.fitcoach.app.llm.LlmResponse
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Orchestration tests with a scripted LLM (the live Gemini check is LiveGeminiTest).
 * They cover the paths that do not depend on wording: storage, gates, buttons and on-device signals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ServiceTest {
    private val tz = ZoneId.of("Asia/Kolkata")
    private lateinit var store: Store
    private lateinit var service: CoachService
    private val llm = ScriptedLlm()

    private fun at(day: String, hhmm: String): Instant = LocalDate.parse(day).atTime(java.time.LocalTime.parse(hhmm)).atZone(tz).toInstant()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        store = Store(Db(ctx, null).writableDatabase, tz)
        val foods = File("src/main/assets/foods_seed.csv").inputStream().use(FoodTable::load)
        val prompts = File("src/main/assets/prompts.json").inputStream().use(Prompts::load)
        service = CoachService(store, llm, foods, prompts, SlotLearner(store, Random(1)))
    }

    @Test fun messageLogsFoodAndReplies() = runTest {
        llm.extraction = """{"food_items":[{"name":"roti","quote":"2 roti","quantity":2,"unit":"piece","meal_slot":"lunch","eaten":"eaten","confidence":0.9},
            {"name":"dal","quote":"dal","quantity":1,"unit":"katori","meal_slot":"lunch","eaten":"eaten","confidence":0.9}]}"""
        val out = service.handleMessage(Inbound("lunch me 2 roti dal", at("2026-10-06", "14:00")))
        assertEquals("Nice, logged.", out.first().text)
        val food = store.foodOn("2026-10-06")
        assertEquals(listOf("roti", "dal"), food.map { it.str("item_name") })
        assertEquals("2026-10-06", food.first().str("local_date"))
    }

    @Test fun recapAtNightOffersOneTapUsualMeals() = runTest {
        store.setProfile(Instant.now(), "usual_meals", JSONObject("""{"lunch":[{"name":"roti","quantity":2,"unit":"piece"}],"dinner":[{"name":"dal","quantity":1,"unit":"katori"}]}"""))
        llm.decide = """{"choice":0,"message":"Lunch aur dinner usual tha?","reason":"recap"}"""
        val out = service.tick(at("2026-10-06", "21:10"))
        assertEquals(1, out.size)
        assertTrue(out[0].buttons.any { it.data.endsWith(":usual") })
        val reply = service.handleButton(out[0].buttons.first { it.data.endsWith(":usual") }.data, at("2026-10-06", "21:12"), out[0].messageId)
        assertTrue(reply!!.text.startsWith("Logged as usual"))
        assertEquals(setOf("lunch", "dinner"), store.foodOn("2026-10-06").map { it.str("meal_slot") }.toSet())
    }

    @Test fun quietHoursAndPauseAreHardGates() = runTest {
        llm.decide = """{"choice":0,"message":"x","reason":"x"}"""
        assertTrue(service.tick(at("2026-10-06", "23:30")).isEmpty())
        service.pause(at("2026-10-06", "10:00"), 2.0)
        assertTrue(service.tick(at("2026-10-07", "21:10")).isEmpty())
    }

    @Test fun placeArrivalWithRuleTriggersReminder() = runTest {
        store.addCommitment(Instant.now(), mapOf("kind" to "if_then", "title" to "Chai only at chess", "trigger_tag" to "chess_club",
            "action" to "order chai, no samosa", "user_words" to "chess pe sirf chai"), listOf("order chai, no samosa"))
        llm.decide = """{"choice":0,"message":"Chess time - sirf chai, yaad hai?","reason":"arrival"}"""
        val now = at("2026-10-06", "18:05")
        service.onPlaceEvent("chess_club", true, now, now)
        val out = service.tick(now)
        assertEquals("Chess time - sirf chai, yaad hai?", out.single().text)
        assertTrue(llm.lastDecidePrompt.contains("arrived:chess_club"))
    }

    @Test fun detectedWalkCompletesWalkCommitment() = runTest {
        val cid = store.addCommitment(Instant.now(), mapOf("kind" to "habit", "title" to "Walk after lunch", "action" to "15 min walk",
            "schedule_days" to "daily", "window_start" to "14:00", "window_end" to "16:00", "activity_kind" to "walk"),
            listOf("15 min walk", "5 min walk"))
        service.onActivitySession("WALKING", at("2026-10-06", "14:10"), at("2026-10-06", "14:28"), at("2026-10-06", "14:30"))
        service.tick(at("2026-10-06", "14:35"))
        assertEquals("done", store.commitmentOutcome(cid, "2026-10-06"))
    }

    @Test fun upiNotificationBecomesRecapQuestion() = runTest {
        val t = at("2026-10-06", "18:00")
        assertTrue(NotificationParser.ingest(store, "com.phonepe.app", "Paid ₹40", "Paid to Sharma Chaat Corner", t, t).startsWith("stored"))
        assertEquals("duplicate", NotificationParser.ingest(store, "com.android.mms", "", "Rs 40.00 debited, paid to Sharma Chaat Corner via UPI", t.plusSeconds(30), t))
        llm.decide = """{"choice":0,"message":"Aaj 40 rupaye ka chaat tha?","reason":"recap"}"""
        val out = service.tick(at("2026-10-06", "21:10"))
        val yes = out.single().buttons.first { it.data.startsWith("inf:") && it.data.endsWith(":yes") }
        service.handleButton(yes.data, at("2026-10-06", "21:11"))
        assertEquals("snack", store.foodOn("2026-10-06").single().str("meal_slot"))
        // The payee is now known as food: the next payment is logged without asking.
        NotificationParser.ingest(store, "com.phonepe.app", "Paid ₹40", "Paid to Sharma Chaat Corner", at("2026-10-07", "18:00"), at("2026-10-07", "18:00"))
        service.tick(at("2026-10-07", "18:20"))
        assertEquals(1, store.foodOn("2026-10-07").size)
    }

    @Test fun ruleWithoutTriggerIsBoundToPlaceInItsWords() = runTest {
        store.addPlace(Instant.now(), "chess_club", "Chess club", 28.6, 77.2, 120.0)
        llm.extraction = """{"commitments":[{"kind":"if_then","title":"chai only at chess club","action":"order only chai",
            "quote":"chess pe sirf chai lunga","user_words":"chess pe sirf chai lunga"}]}"""
        service.handleMessage(Inbound("rule: chess pe sirf chai lunga", at("2026-10-06", "12:00")))
        assertEquals("chess_club", store.activeCommitments().single().triggerTag)
    }

    @Test fun focusedExtractionOnlyForPlausibleAnswers() {
        assertTrue(CoachService.looksLikeAnswer("usual_meals", "usually breakfast me poha hota hai"))
        assertTrue(CoachService.looksLikeAnswer("goal", "5 kg kam karna hai"))
        assertTrue(!CoachService.looksLikeAnswer("goal", "ek samosa kha liya yaar, aaj ka din kharab"))
    }

    @Test fun llmFailureFallsBackToTemplate() = runTest {
        store.setProfile(Instant.now(), "usual_meals", JSONObject("""{"lunch":[{"name":"roti","quantity":2}]}"""))
        llm.fail = true
        val out = service.tick(at("2026-10-06", "21:10"))
        assertTrue(out.single().text.startsWith("Quick recap"))
        val reply = service.handleMessage(Inbound("hi", at("2026-10-06", "21:20")))
        assertTrue(reply.first().text.startsWith("Saved."))
    }
}

/** Returns canned JSON per purpose; records the last decide prompt. */
class ScriptedLlm : LlmProvider {
    override val name = "scripted"
    var extraction = "{}"
    var decide = """{"choice":-1,"reason":"nothing useful"}"""
    var reply = "Nice, logged."
    var fail = false
    var lastDecidePrompt = ""

    override suspend fun generate(request: LlmRequest): LlmResponse {
        if (fail) throw com.fitcoach.app.llm.LlmException("down")
        val text = when (request.purpose) {
            "extract" -> extraction
            "decide" -> { lastDecidePrompt = request.userText; decide }
            else -> reply
        }
        return LlmResponse(text, name, "scripted")
    }
}
