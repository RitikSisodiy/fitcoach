package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.dbl
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Passive food signals from phone notifications (port of coach/ingest/notifications.py).
 * On Android these come straight from a NotificationListenerService - no forwarder app, no server.
 *
 * Nothing becomes a food log directly: signals become pending `inferred_events`, confirmed with one tap.
 * Privacy: the notification body is parsed and discarded. Only amount, time, app, a payee key and (for merchants)
 * a display name are stored. Person-to-person payees are never shown to the LLM or on buttons.
 */
object NotificationParser {
    val FOOD_DELIVERY_PACKAGES = mapOf(
        "in.swiggy.android" to "Swiggy", "com.application.zomato" to "Zomato", "com.done.faasos" to "EatSure",
    )
    val PAYMENT_PACKAGES = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "GPay", "com.phonepe.app" to "PhonePe", "net.one97.paytm" to "Paytm",
        "in.org.npci.upiapp" to "BHIM", "com.dreamplug.androidapp" to "CRED", "com.naviapp" to "Navi",
    )
    val SMS_PACKAGES = setOf(
        "sms", "android.sms", "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
        "com.oneplus.mms", "com.coloros.mms",
    )
    val ALL_PACKAGES: Set<String> get() = FOOD_DELIVERY_PACKAGES.keys + PAYMENT_PACKAGES.keys + SMS_PACKAGES

    const val SMALL_PAYMENT_MAX = 250.0
    private val ORDER_MERGE_WINDOW = Duration.ofHours(2)
    private val PAYMENT_DEDUPE_WINDOW = Duration.ofMinutes(3)

    private const val CURRENCY = "(?:rs\\.?|inr|₹)"
    private val I = RegexOption.IGNORE_CASE
    private val AMOUNT_PATTERNS = listOf(
        Regex("$CURRENCY\\s*([\\d,]+(?:\\.\\d{1,2})?)", I),
        Regex("\\b(?:debited|deducted)\\s+(?:by|for|with)?\\s*([\\d,]+(?:\\.\\d{1,2})?)\\b", I),
    )
    private val CREDIT_RE = Regex("\\b(credited|received)\\b", I)
    private val DEBIT_RE = Regex("\\b(debited|paid|sent|spent|payment of|transferred|deducted)\\b", I)
    private val STRONG_DEBIT_RE = Regex("\\b(debited|paid|sent)\\b", I)
    private val EXCLUDE_RE = Regex(
        "\\b(otp|one time password|verification code|request(?:ed|s)?|collect|due|reminder|refund|reversed|cashback|" +
            "failed|declined|autopay|mandate|emi|bill)\\b", I,
    )
    private val VPA_RE = Regex("\\b([a-z0-9][a-z0-9._-]{1,60}@[a-z][a-z0-9]{1,20})\\b", I)
    private val AXIS_UPI_RE = Regex("UPI/P2([AM])/\\d+/([^/\\n]{2,60})", I)
    private val PAYEE_RE = Regex(
        "(?:paid to|sent to|to vpa|trf to|transferred to|\\bto)\\s+([A-Za-z0-9 .&'-]{2,60}?)" +
            "(?=\\s+(?:on|via|ref|refno|ref\\.?no|upi|from|a/c|avl|using|for|sms|call|if|not)\\b|[.,;\\n]|$)", I,
    )
    private val MERCHANT_HINT_RE = Regex(
        "(chaat|chat|tea|chai|stall|store|stores|foods?|corner|cafe|caf[eé]|restaurant|sweets?|bakery|dhaba|bhandar|" +
            "mart|kitchen|snacks?|juice|canteen|hotel|dairy|kirana|bhojnalaya|paytmqr|bharatpe|^q\\d+@|merchant)", I,
    )
    private val ORDER_RE = Regex(
        "\\b(order (?:placed|confirmed|is on the way|picked up|delivered|has been delivered)|out for delivery|delivered|" +
            "on (?:its|the) way|enjoy your meal|being prepared)\\b", I,
    )
    private val PROMO_RE = Regex("\\b(off|offer|deal|coupon)\\b", I)
    private val ORDER_ID_RE = Regex("order\\s*(?:id|no\\.?|#)\\s*:?\\s*#?(\\d{6,})", I)
    private val RESTAURANT_RE = Regex("\\bfrom\\s+([A-Z][A-Za-z0-9&' .-]{1,50}?)(?=\\s+(?:is|has|will|at)\\b|[.,!\\n]|$)")
    private val PHONE_LIKE = Regex("[\\d\\s+-]{6,}")

    data class Signal(
        val kind: String, var summary: String, val naturalKey: String, var confidence: Double,
        val payeeKey: String? = null, val amount: Double? = null, val payload: JSONObject = JSONObject(), val app: String? = null,
    )

    private fun normKey(t: String) = t.lowercase().replace(Regex("[^a-z0-9@._]+"), " ").trim()
    private fun fmtAmount(a: Double) = if (a == Math.floor(a)) a.toLong().toString() else a.toString()

    fun amount(body: String): Double? {
        for (p in AMOUNT_PATTERNS) {
            val m = p.find(body) ?: continue
            m.groupValues[1].replace(",", "").toDoubleOrNull()?.let { return it }
        }
        return null
    }

    fun payee(body: String): Pair<String?, Boolean> {
        AXIS_UPI_RE.find(body)?.let { m ->
            val name = m.groupValues[2].trim()
            return name to (m.groupValues[1].uppercase() == "M" || MERCHANT_HINT_RE.containsMatchIn(name))
        }
        VPA_RE.find(body)?.let { m -> val v = m.groupValues[1].lowercase(); return v to MERCHANT_HINT_RE.containsMatchIn(v) }
        PAYEE_RE.find(body)?.let { m ->
            val name = m.groupValues[1].trim()
            if (PHONE_LIKE.matches(name) || name.lowercase() in setOf("your", "you", "a/c", "account")) return null to false
            return name to MERCHANT_HINT_RE.containsMatchIn(name)
        }
        return null to false
    }

    fun parse(pkg: String, title: String, text: String, whenT: Instant, tz: ZoneId): Signal? {
        val body = listOf(title.trim(), text.trim()).filter { it.isNotEmpty() }.joinToString("\n")
        if (body.isEmpty()) return null

        FOOD_DELIVERY_PACKAGES[pkg]?.let { app ->
            if (!ORDER_RE.containsMatchIn(body) || PROMO_RE.containsMatchIn(body)) return null
            val restaurant = RESTAURANT_RE.find(body)?.groupValues?.get(1)?.trim()
            val oid = ORDER_ID_RE.find(body)?.groupValues?.get(1)
            val key = if (oid != null) "order|$app|$oid" else "order|$app|${TimeUtil.iso(whenT)}"
            return Signal("food_order", "$app order" + (restaurant?.let { " from $it" } ?: ""), key, 0.8,
                payload = JSONObject().put("app", app).put("restaurant", restaurant ?: JSONObject.NULL), app = app)
        }

        val isPaymentApp = pkg in PAYMENT_PACKAGES
        val isSms = pkg in SMS_PACKAGES || pkg.startsWith("sms:")
        if (!(isPaymentApp || isSms)) return null
        if (EXCLUDE_RE.containsMatchIn(body) || !DEBIT_RE.containsMatchIn(body)) return null
        if (CREDIT_RE.containsMatchIn(body) && !STRONG_DEBIT_RE.containsMatchIn(body)) return null
        val amt = amount(body) ?: return null
        if (amt <= 0 || amt > SMALL_PAYMENT_MAX) return null
        val (payee, merchant) = payee(body)
        val lt = TimeUtil.localTime(whenT, tz)
        val snackWindow = lt.hour in 10..12 || lt.hour in 16..20
        val conf = (if (snackWindow) 0.45 else 0.25) + (if (merchant) 0.1 else 0.0)
        val shown = if (payee != null && merchant) payee else "UPI"
        return Signal(
            "small_payment", "₹${fmtAmount(amt)} payment ($shown) at ${TimeUtil.fmtHhmm(lt)}", "pay|${fmtAmount(amt)}|${TimeUtil.iso(whenT)}", conf,
            payeeKey = payee?.let(::normKey), amount = amt,
            payload = JSONObject().put("source", PAYMENT_PACKAGES[pkg] ?: "SMS").put("merchant", merchant).put("display", shown),
        )
    }

    private fun isDuplicate(store: Store, sig: Signal, whenT: Instant): Boolean {
        val window = if (sig.kind == "food_order") ORDER_MERGE_WINDOW else PAYMENT_DEDUPE_WINDOW
        val rows = store.query(
            "SELECT occurred_at, amount, payload_json FROM inferred_events WHERE kind = ? AND occurred_at >= ? AND occurred_at <= ?",
            sig.kind, TimeUtil.iso(whenT.minus(window)), TimeUtil.iso(whenT.plus(window)),
        )
        return rows.any { r ->
            (sig.kind == "small_payment" && r.dbl("amount") == sig.amount) ||
                (sig.kind == "food_order" && JSONObject(r.str("payload_json") ?: "{}").optString("app") == sig.app)
        }
    }

    /** Returns a short status string for logging. Never throws on bad input. */
    fun ingest(store: Store, pkg: String, title: String, text: String, whenT: Instant, now: Instant): String {
        val sig = parse(pkg, title, text, whenT, store.tz) ?: return "ignored"
        sig.payeeKey?.let { key ->
            val label = store.payeeLabel(key)
            if (label != null && (label.long("is_food") ?: 1L) == 0L) return "ignored: payee labelled not food"
            if (label != null) {
                sig.confidence = maxOf(sig.confidence, 0.75)
                label.str("label")?.takeIf { it.isNotBlank() }?.let {
                    sig.summary = "₹${fmtAmount(sig.amount ?: 0.0)} at $it (${TimeUtil.fmtHhmm(TimeUtil.localTime(whenT, store.tz))})"
                }
            }
        }
        if (isDuplicate(store, sig, whenT)) return "duplicate"
        val id = store.addInferred(
            now, whenT,
            mapOf("kind" to sig.kind, "summary" to sig.summary, "payee_key" to sig.payeeKey, "amount" to sig.amount,
                "confidence" to sig.confidence, "natural_key" to sig.naturalKey),
            sig.payload,
        )
        return if (id != null && id > 0) "stored:${sig.kind}:$id" else "duplicate"
    }
}
