package com.fitcoach.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fitcoach.app.data.Db
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import com.fitcoach.app.engine.CoachService
import com.fitcoach.app.engine.FoodTable
import com.fitcoach.app.engine.Inbound
import com.fitcoach.app.engine.PlanDraft
import com.fitcoach.app.engine.Prompts
import com.fitcoach.app.llm.GeminiProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * The plan is dynamic, with real Gemini: the agent holds a "dinner check" intention for 20:00, the person tells it what
 * they ate at 19:30, and the agent must drop the intention instead of contacting them. Also prints what the agent plans
 * by itself. Writes build/live-plan-transcript.txt.
 *   GEMINI_API_KEY=... ./gradlew testDebugUnitTest --tests '*LivePlanTest*'
 */
@RunWith(RobolectricTestRunner::class)
class LivePlanTest {
    private val tz = ZoneId.of("Asia/Kolkata")

    @Test fun plannedDinnerCheckIsCancelledWhenThePersonAlreadyToldIt() = runBlocking<Unit> {
        val key = System.getenv("GEMINI_API_KEY")
        assumeTrue("GEMINI_API_KEY not set", !key.isNullOrBlank())
        val store = Store(Db(ApplicationProvider.getApplicationContext<Context>(), null).writableDatabase, tz)
        val service = CoachService(store, GeminiProvider(key!!, usageFile = File(System.getProperty("java.io.tmpdir"), "fitcoach_live_usage.json")),
            File("src/main/assets/foods_seed.csv").inputStream().use(FoodTable::load), Prompts.fromDir(File("src/main/assets/prompts")))
        val day = LocalDate.now(tz)
        fun at(hhmm: String): Instant = day.atTime(LocalTime.parse(hhmm)).atZone(tz).toInstant()
        val log = StringBuilder()
        fun plan() = service.plan.open().joinToString("\n") { "  #${it.long("id")} [${it.str("status")}] ${it.str("title")} ${store.fmtLocal(com.fitcoach.app.core.TimeUtil.parse(it.str("window_start")!!), "HH:mm")} | why: ${it.str("reason")} | skip if: ${it.str("skip_if")}" }
        fun lastAgent() = store.recentDecisions(10).filter { it.str("kind")!!.startsWith("agent_") }.take(2).joinToString("\n") { "  ${it.str("kind")}: ${it.str("summary")}" }

        service.handleMessage(Inbound("Mera goal 6 kg kam karna hai. Main dinner log karna bhool jata hu, usually 8-9 baje dinner karta hu.", at("16:50")))
        service.tick(at("17:00"), force = true)
        log.append("17:00 agent decided:\n${lastAgent()}\nplan:\n${plan()}\n\n")

        // Make sure a dinner check exists so the cancellation path is tested even if the agent planned something else.
        val dinner = service.plan.open().firstOrNull { (it.str("title") + it.str("reason")).contains("dinner", ignoreCase = true) }?.long("id")
            ?: service.plan.add(at("17:01"), PlanDraft("Ask what they ate for dinner", "They often forget to log dinner", "notification",
                "meal_check", "20:00", "20:45", "they tell me about dinner first", null)).first!!
        log.append("dinner intention: #$dinner\n")

        service.handleMessage(Inbound("Dinner ho gaya: 2 roti, paneer sabzi aur salad", at("19:30")))
        service.tick(at("19:31"))
        log.append("19:31 after the person reported dinner:\n${lastAgent()}\nplan now:\n${plan()}\n")
        val row = service.plan.get(dinner)!!
        log.append("dinner intention -> ${row.str("status")}: ${row.str("resolution")}\n\n")

        service.tick(at("20:05"))
        log.append("20:05:\n${lastAgent()}\nplan:\n${plan()}\n")
        val sentAfter = store.query("SELECT message_text FROM interventions WHERE sent_at >= ?", com.fitcoach.app.core.TimeUtil.iso(at("19:30")))
        log.append("messages sent after 19:30: ${sentAfter.map { it.str("message_text") }}\n")
        File("build/live-plan-transcript.txt").writeText(log.toString())
        println(log)

        assertEquals("cancelled", row.str("status"))
        assertTrue("asked about dinner anyway: $sentAfter", sentAfter.none { it.str("message_text")!!.contains("dinner", ignoreCase = true) })
        assertTrue(service.plan.open().all { com.fitcoach.app.core.TimeUtil.parse(it.str("window_start")!!).isBefore(at("19:31").plusSeconds(24 * 3600)) })
    }
}
