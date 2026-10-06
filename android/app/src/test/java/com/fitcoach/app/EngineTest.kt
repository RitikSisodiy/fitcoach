package com.fitcoach.app

import com.fitcoach.app.engine.ExtractionValidator
import com.fitcoach.app.engine.FoodTable
import com.fitcoach.app.engine.NotificationParser
import com.fitcoach.app.engine.Patterns
import com.fitcoach.app.engine.Safety
import com.fitcoach.app.llm.GeminiProvider
import com.fitcoach.app.llm.LlmException
import com.fitcoach.app.llm.LlmRequest
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Deterministic engine pieces (ports of the Python reference tests). Robolectric supplies a real org.json. */
@RunWith(RobolectricTestRunner::class)
class EngineTest {
    private val tz = ZoneId.of("Asia/Kolkata")
    private val foods = File("src/main/assets/foods_seed.csv").inputStream().use(FoodTable::load)

    @Test fun groundingDropsInventedFood() {
        val raw = JSONObject("""{"food_items":[
            {"name":"roti","quote":"2 roti","quantity":2,"unit":"piece","meal_slot":"lunch","eaten":"eaten","confidence":0.9},
            {"name":"paneer","quote":"paneer tikka","eaten":"eaten","confidence":0.9}]}""")
        val ex = ExtractionValidator.validate(raw, emptySet(), "lunch me 2 roti aur dal li", foods::aliasesFor)
        assertEquals(listOf("roti"), ex.foods.map { it.name })
        assertTrue(ex.dropped.any { "paneer" in it })
    }

    @Test fun tokenizerKeepsWordsBeforeFullStop() {
        assertTrue(ExtractionValidator.grounded("dal sabzi", "2 roti dal sabzi."))
    }

    @Test fun commitmentWindowAndTriggerFallback() {
        val raw = JSONObject("""{"context":[{"tag":"chess_club","timing":"planned"}],
            "commitments":[{"kind":"if_then","title":"No samosa at chess","action":"order chai only","quote":"chess pe sirf chai",
            "window_start":"18:00"}]}""")
        val ex = ExtractionValidator.validate(raw, emptySet(), "chess pe sirf chai lunga, samosa nahi")
        val c = ex.commitments.single()
        assertNull(c.windowStart) // half-specified window is dropped
        assertEquals("chess_club", c.triggerTag)
    }

    @Test fun usualMealsNeedQuote() {
        val raw = JSONObject("""{"usual_meals":[{"slot":"lunch","quote":"lunch me usually 3 roti dal","items":[{"name":"roti","quantity":3}]},
            {"slot":"dinner","quote":"rajma chawal","items":[{"name":"rajma"}]}]}""")
        val ex = ExtractionValidator.validate(raw, emptySet(), "lunch me usually 3 roti dal hota hai")
        val usual = ex.profileUpdates["usual_meals"] as JSONObject
        assertTrue(usual.has("lunch")); assertFalse(usual.has("dinner"))
    }

    @Test fun safetyBlocksShameAndStarvation() {
        assertFalse(Safety.ok("You were lazy again"))
        assertFalse(Safety.ok("Skip all meals tomorrow to make up for it"))
        assertFalse(Safety.ok("Eat only 900 kcal per day"))
        assertTrue(Safety.ok("That happened. Next meal normal - dal roti is perfect."))
    }

    @Test fun nutritionRangesAndConversion() {
        val roti = foods.estimate("chapati", 2.0, "piece")
        assertEquals("roti", roti.foodKey)
        assertEquals(180.0, roti.kcalLow!!, 0.1); assertEquals(240.0, roti.kcalHigh!!, 0.1)
        val unknown = foods.estimate("mystery stew", null, null)
        assertNull(unknown.kcalLow)
        val llm = foods.estimate("mystery stew", 1.0, null, 200.0 to 300.0)
        assertEquals(160.0, llm.kcalLow!!, 0.1); assertEquals(360.0, llm.kcalHigh!!, 0.1)
    }

    @Test fun upiAndDeliveryNotifications() {
        val t = Instant.parse("2026-10-06T12:30:00Z") // 18:00 IST
        val pay = NotificationParser.parse("com.google.android.apps.nbu.paisa.user", "Paid ₹40.00", "Paid to Sharma Chaat Corner", t, tz)
        assertNotNull(pay); assertEquals("small_payment", pay!!.kind); assertEquals(40.0, pay.amount!!, 0.0)
        assertTrue("Sharma Chaat Corner" in pay.summary)
        assertNull(NotificationParser.parse("com.phonepe.app", "Payment request", "Rs 50 requested by X", t, tz))
        assertNull(NotificationParser.parse("com.phonepe.app", "Paid", "Paid ₹1,200 to Landlord", t, tz)) // not a snack
        val sbi = NotificationParser.parse("com.android.mms", "", "A/C X1234 debited by 60.0 on 06Oct26 trf to RAMU TEA STALL Refno 1234", t, tz)
        assertEquals(60.0, sbi!!.amount!!, 0.0)
        val order = NotificationParser.parse("in.swiggy.android", "Order delivered", "Your order from Biryani House has been delivered. Enjoy your meal!", t, tz)
        assertEquals("food_order", order!!.kind)
        assertNull(NotificationParser.parse("in.swiggy.android", "50% off", "Order now and get 50% off on your favourite meal deal", t, tz))
        assertNull(NotificationParser.parse("com.whatsapp", "Paid", "Paid ₹40 to chai", t, tz))
    }

    @Test fun wilsonIsConservative() {
        assertTrue(Patterns.wilsonLower(3, 3) < 0.75)
        assertTrue(Patterns.wilsonLower(8, 9) > 0.6)
        assertEquals(0.0, Patterns.wilsonLower(0, 0), 0.0)
    }

    @Test fun geminiRoutesTextToLiteAndFallsBackOnQuota() = runTest {
        val calls = mutableListOf<String>()
        val transport = GeminiProvider.Transport { url, _, _, _ ->
            calls += url.substringAfter("models/").substringBefore(":")
            when {
                "3.5-flash-lite" in url -> 429 to """{"error":{"message":"quota GenerateRequestsPerDayPerProjectPerModel-FreeTier PerDay"}}"""
                else -> 200 to """{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true},{"text":"ok"}]}}]}"""
            }
        }
        val g = GeminiProvider("k", transport = transport)
        val r = g.generate(LlmRequest("s", "hi", purpose = "reply"))
        assertEquals("ok", r.text)
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite"), calls)
        // The exhausted model is not tried again today.
        calls.clear(); g.generate(LlmRequest("s", "hi", purpose = "reply"))
        assertEquals(listOf("gemini-3.1-flash-lite"), calls)
        assertTrue(g.usage().getValue("gemini-3.5-flash-lite").contains("exhausted"))
    }

    @Test fun geminiMediaGoesToFlashFirst() {
        val g = GeminiProvider("k", transport = { _, _, _, _ -> 200 to "{}" })
        val route = g.route(LlmRequest("s", "", media = listOf(com.fitcoach.app.llm.MediaPart(ByteArray(4), "image/jpeg"))))
        assertEquals("gemini-3.8-flash", route.first())
    }

    @Test fun updateVersionCompareAndReleaseParsing() {
        assertTrue(com.fitcoach.app.update.Updater.isNewer("1.1.12", "1.1.9"))
        assertTrue(com.fitcoach.app.update.Updater.isNewer("1.2.0", "1.1.40"))
        assertFalse(com.fitcoach.app.update.Updater.isNewer("1.1.5", "1.1.5"))
        assertFalse(com.fitcoach.app.update.Updater.isNewer("1.0.9", "1.1.0"))
        val rel = com.fitcoach.app.update.Updater.parseRelease("""{"tag_name":"v1.1.7","body":"notes",
            "assets":[{"name":"checksums.txt","browser_download_url":"x"},{"name":"FitCoach-1.1.7.apk","browser_download_url":"https://e/a.apk"}]}""")
        assertEquals("1.1.7", rel!!.version); assertEquals("https://e/a.apk", rel.apkUrl)
        assertNull(com.fitcoach.app.update.Updater.parseRelease("""{"tag_name":"v1","assets":[]}"""))
    }

    @Test(expected = LlmException::class)
    fun geminiBadKeyThrows() = runTest {
        GeminiProvider("bad", transport = { _, _, _, _ -> 400 to """{"error":{"message":"API key not valid"}}""" })
            .generate(LlmRequest("s", "hi"))
    }
}
