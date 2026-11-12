package com.fitcoach.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fitcoach.app.data.Db
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.dbl
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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Real Gemini: pantry, household memory, workout sets and the next-meal / grocery judgement (D-045).
 *   GEMINI_API_KEY=... ./gradlew testDebugUnitTest --tests '*LiveHouseholdTest*'
 * About 12 Flash-Lite calls. Read build/live-household-transcript.txt, not just the result.
 */
@RunWith(RobolectricTestRunner::class)
class LiveHouseholdTest {
    private val tz = ZoneId.of("Asia/Kolkata")

    @Test fun pantryHouseholdAndTrainingWithRealGemini() = runBlocking<Unit> {
        val key = System.getenv("GEMINI_API_KEY")
        assumeTrue("GEMINI_API_KEY not set", !key.isNullOrBlank())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = Store(Db(ctx, null).writableDatabase, tz)
        val foods = File("src/main/assets/foods_seed.csv").inputStream().use(FoodTable::load)
        val usage = File(System.getProperty("java.io.tmpdir"), "fitcoach_live_usage.json")
        val service = CoachService(store, GeminiProvider(key!!, usageFile = usage), foods, Prompts.fromDir(File("src/main/assets/prompts")))
        val d0 = LocalDate.now(tz).minusDays(1)
        fun at(day: Int, hhmm: String): Instant = d0.plusDays(day.toLong()).atTime(LocalTime.parse(hhmm)).atZone(tz).toInstant()
        val log = StringBuilder()

        suspend fun say(day: Int, hhmm: String, text: String) {
            log.append("\n[day $day $hhmm] USER: $text\n")
            service.handleMessage(Inbound(text, at(day, hhmm))).forEach {
                log.append("  COACH: ${it.text}\n"); assertTrue("unsafe: ${it.text}", Safety.ok(it.text))
            }
        }
        suspend fun tick(day: Int, hhmm: String) {
            val out = service.tick(at(day, hhmm), force = true)
            log.append("[day $day $hhmm] AGENT ${store.recentDecisions(1).firstOrNull()?.let { "${it.str("kind")}: ${it.str("summary")}" }}\n")
            out.forEach { log.append("  COACH -> ${it.channel}: ${it.text}   quick replies: ${it.quickReplies}\n"); assertTrue(Safety.ok(it.text)) }
        }

        store.addMetric(Instant.now(), at(0, "09:00"), "weight_kg", 65.0, "user_reported")
        say(0, "10:00", "Main skinny fat hu, fat kam karna hai. Ghar pe mummy khana banati hai aur tel bahut dalti hai. Paas ki dukaan pe sirf chai, maggi, cold drink milta hai")
        say(0, "12:30", "aaj 30 ande, 250 g paneer aur 500 g soya chunks laya")
        say(0, "13:00", "workout kiya: 15 pushups, 3x15 squats, plank 30 sec 3 baar")
        say(1, "10:45", "nashte me 3 ande ki bhurji aur 1 roti khayi")
        tick(1, "13:00")
        say(1, "13:10", "lunch me kya khau? protein kitna bacha hai aaj ka")
        say(1, "20:30", "kal workout kitna karu?")

        log.append("\nPANTRY: ${store.pantry().map { "${it.str("name")} ${it.dbl("quantity")} ${it.str("unit")}" }}\n")
        log.append("SETS: ${store.exerciseSetsSince("2000-01-01").map { "${it.str("exercise")} ${it.str("sets")}x${it.str("reps") ?: it.str("seconds") + "s"}" }}\n")
        log.append("MEMORY: ${service.memory(at(1, "21:00")).getJSONArray("long_term")}\n")
        File("build/live-household-transcript.txt").writeText(log.toString())
        println(log)

        val eggs = store.pantry().firstOrNull { "egg" in (it.str("name") ?: "") }
        assertTrue("eggs purchase not in pantry", eggs != null)
        assertTrue("eggs eaten not taken from the pantry: ${eggs?.dbl("quantity")}", (eggs?.dbl("quantity") ?: 0.0) < 30.0)
        assertTrue("exercise sets not logged", store.exerciseSetsSince("2000-01-01").map { it.str("exercise") }.containsAll(listOf("squats", "plank")))
        assertTrue("household memory missing", store.activeFacts().any { it.str("category") == "household" })
    }
}
