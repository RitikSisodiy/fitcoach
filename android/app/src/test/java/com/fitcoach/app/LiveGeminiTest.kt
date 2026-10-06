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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Real Gemini, real prompts, the app's own engine. Runs only when GEMINI_API_KEY is set:
 *   GEMINI_API_KEY=... ./gradlew testDebugUnitTest --tests '*LiveGeminiTest*'
 * Uses about 8 Flash-Lite requests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class LiveGeminiTest {
    private val tz = ZoneId.of("Asia/Kolkata")
    private fun at(day: String, hhmm: String): Instant = LocalDate.parse(day).atTime(LocalTime.parse(hhmm)).atZone(tz).toInstant()

    @Test fun lazyUserDayWithRealGemini() = runBlocking {
        val key = System.getenv("GEMINI_API_KEY")
        assumeTrue("GEMINI_API_KEY not set", !key.isNullOrBlank())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = Store(Db(ctx, null).writableDatabase, tz)
        val foods = File("src/main/assets/foods_seed.csv").inputStream().use(FoodTable::load)
        val prompts = File("src/main/assets/prompts.json").inputStream().use(Prompts::load)
        val usage = File(System.getProperty("java.io.tmpdir"), "fitcoach_live_usage.json")
        val service = CoachService(store, GeminiProvider(key!!, usageFile = usage), foods, prompts)
        val day = LocalDate.now(tz).toString()
        val transcript = StringBuilder()
        suspend fun say(hhmm: String, text: String) {
            val out = service.handleMessage(Inbound(text, at(day, hhmm)))
            transcript.append("USER $hhmm: $text\n")
            out.forEach { transcript.append("COACH: ${it.text}\n"); assertTrue("unsafe: ${it.text}", Safety.ok(it.text)) }
        }

        say("13:40", "lunch me 3 roti, dal aur thodi bhindi sabzi khayi")
        say("13:45", "usually breakfast me poha ya 2 paratha hota hai, dinner me dal chawal")
        say("17:50", "chess club ja raha hu, wahan samosa milte hai. rule: chess pe sirf chai lunga")
        service.onPlaceEvent("chess_club", true, at(day, "18:05"), at(day, "18:05"))
        val arrival = service.tick(at(day, "18:06"))
        arrival.forEach { transcript.append("COACH (proactive 18:06): ${it.text}\n") }
        say("19:30", "ek samosa kha liya yaar, aaj ka din kharab")

        val food = store.foodOn(day).map { "${it.str("item_name")} (${it.str("meal_slot")})" }
        val commitments = store.activeCommitments().map { "${it.title} trigger=${it.triggerTag}" }
        transcript.append("\nFOOD: $food\nCOMMITMENTS: $commitments\nUSUAL: ${store.profile().optJSONObject("usual_meals")}\n")
        transcript.append("DECISIONS:\n" + store.recentDecisions(30).reversed().joinToString("\n") { "  ${it.str("kind")}: ${it.str("summary")?.take(160)}" })
        File("build/live-gemini-transcript.txt").writeText(transcript.toString())
        println(transcript)

        assertTrue("roti not logged: $food", food.any { "roti" in it.lowercase() })
        assertTrue("samosa not logged: $food", food.any { "samosa" in it.lowercase() })
        assertTrue("no rule saved", store.activeCommitments().isNotEmpty())
    }
}
