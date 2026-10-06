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
import com.fitcoach.app.engine.Safety
import com.fitcoach.app.llm.GeminiProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The real agent path with real Gemini: a lazy user over three days. Natural chat must become memory, the agent must
 * decide on its own (act or stay silent, choose timing), ignored messages must be evaluated, reflection must produce
 * insights, and later answers must use what was remembered. Runs only when GEMINI_API_KEY is set:
 *   GEMINI_API_KEY=... ./gradlew testDebugUnitTest --tests '*LiveAgentTest*'
 * About 25 Flash-Lite calls and 1 Flash call. The transcript (build/live-agent-transcript.txt) is for human review.
 */
@RunWith(RobolectricTestRunner::class)
class LiveAgentTest {
    private val tz = ZoneId.of("Asia/Kolkata")

    @Test fun lazyUserThreeDaysWithRealGemini() = runBlocking<Unit> {
        val key = System.getenv("GEMINI_API_KEY")
        assumeTrue("GEMINI_API_KEY not set", !key.isNullOrBlank())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = Store(Db(ctx, null).writableDatabase, tz)
        val foods = File("src/main/assets/foods_seed.csv").inputStream().use(FoodTable::load)
        val usage = File(System.getProperty("java.io.tmpdir"), "fitcoach_live_usage.json")
        val service = CoachService(store, GeminiProvider(key!!, usageFile = usage), foods, Prompts.fromDir(File("src/main/assets/prompts")))
        service.telegramLinked = { true }
        val d0 = LocalDate.now(tz).minusDays(2)
        fun at(day: Int, hhmm: String): Instant = d0.plusDays(day.toLong()).atTime(LocalTime.parse(hhmm)).atZone(tz).toInstant()
        val log = StringBuilder()
        val proactive = mutableListOf<String>()

        suspend fun say(day: Int, hhmm: String, text: String, channel: String = "app") {
            log.append("\n[day $day $hhmm] USER ($channel): $text\n")
            service.handleMessage(Inbound(text, at(day, hhmm), channel = channel)).forEach {
                log.append("  COACH: ${it.text}\n"); assertTrue("unsafe: ${it.text}", Safety.ok(it.text))
            }
        }
        suspend fun tick(day: Int, hhmm: String) {
            val before = store.recentDecisions(1).firstOrNull()?.str("id")
            val out = service.tick(at(day, hhmm))
            val dec = store.recentDecisions(1).firstOrNull()
            if (dec?.str("id") != before && dec?.str("kind")?.startsWith("agent") == true) {
                val j = JSONObject(dec.str("data_json") ?: "{}")
                log.append("[day $day $hhmm] AGENT ${dec.str("kind")}: ${dec.str("summary")}\n    woke for: ${j.optString("woke_for")} | next check in ${j.optInt("next_check_minutes")} min: ${j.optString("next_check_reason")}\n")
            } else log.append("[day $day $hhmm] agent not due\n")
            out.forEach {
                log.append("  COACH -> ${it.channel}: ${it.text}   quick replies: ${it.quickReplies}\n")
                assertTrue("unsafe: ${it.text}", Safety.ok(it.text)); proactive += it.text
            }
        }
        // Normal step counts for the past week (what the phone would have synced), and a lazy day today.
        for (i in 1..7) store.upsertHealth(at(0, "21:00"), "steps", "steps|${d0.minusDays(i.toLong())}", null, null,
            d0.minusDays(i.toLong()).atTime(12, 0).atZone(tz).toInstant(), 7000.0 + i * 150, JSONObject().put("s", i))

        say(0, "09:30", "Hi! Mujhe Diwali tak 6 kg kam karna hai. Main office jata hu 10 se 7, aur ghar aake bahut snacking hoti hai")
        say(0, "13:30", "lunch me 2 roti aur rajma chawal")
        store.upsertHealth(at(0, "18:00"), "steps", "steps|$d0", null, null, at(0, "12:00"), 2100.0, JSONObject().put("as_of", "18:00"))
        tick(0, "17:00"); tick(0, "19:15"); tick(0, "20:30")
        say(0, "21:30", "is hafte ghutne me thoda dard hai, isliye gym band hai")
        // Day 1: the user ignores everything.
        for (t in listOf("08:30", "11:00", "13:30", "16:00", "18:30", "21:00")) tick(1, t)
        // Day 2: reflection runs on the first tick; later the user asks things that need memory, from Telegram.
        tick(2, "09:00"); tick(2, "13:00")
        say(2, "20:30", "yaar mera goal kya tha aur gym kab se band hai? aaj kya karu", channel = "telegram")

        val insights = service.memory(at(2, "21:00")).getJSONArray("coach_insights")
        log.append("\nMEMORY: ${service.memory(at(2, "21:00")).toString(1)}\n")
        log.append("PROFILE goal: ${store.profile().opt("goal_text")} / ${store.profile().opt("goal_weight_kg")}\n")
        log.append("INTERVENTIONS:\n" + store.query("SELECT * FROM interventions WHERE kind != 'contextual'").joinToString("\n") {
            "  ${it.str("sent_at")} ${it.str("channel")} ${it.str("intent")} -> ${it.str("outcome")} (expected: ${it.str("expected_outcome")})"
        } + "\n")
        File("build/live-agent-transcript.txt").writeText(log.toString())
        println(log)

        val decisions = store.recentDecisions(100).filter { it.str("kind")!!.startsWith("agent_") }
        assertTrue("agent never evaluated", decisions.size >= 5)
        assertTrue("goal not understood", store.profile().opt("goal_text").toString().isNotBlank() || !store.profile().isNull("goal_weight_kg"))
        assertTrue("temporary memory missing", service.memory(at(2, "20:00")).getJSONArray("temporary").length() >= 1)
        assertTrue("long-term memory missing", service.memory(at(2, "20:00")).getJSONArray("long_term").length() >= 1)
        assertTrue("ignored messages not evaluated", store.query("SELECT 1 FROM interventions WHERE outcome = 'ignored'").isNotEmpty() || proactive.size <= 1)
        assertTrue("reflection did not run", store.recentDecisions(100).any { it.str("kind") == "agent_reflect" } || proactive.size < 3)
        val nextChecks = decisions.mapNotNull { JSONObject(it.str("data_json") ?: "{}").optInt("next_check_minutes").takeIf { m -> m > 0 } }.toSet()
        assertTrue("agent always picks the same check interval: $nextChecks", nextChecks.size >= 2)
        log.append("insights: ${insights.length()}\n")
    }
}
