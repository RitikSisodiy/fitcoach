package com.fitcoach.app.engine

import com.fitcoach.app.core.TimeUtil
import com.fitcoach.app.data.Row
import com.fitcoach.app.data.Store
import com.fitcoach.app.data.long
import com.fitcoach.app.data.str
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** A new short-term intention proposed by the agent (local "HH:MM" window within the next 24 h). */
data class PlanDraft(
    val title: String, val reason: String, val channel: String, val intent: String,
    val windowStart: String, val windowEnd: String?, val skipIf: String?, val commitmentId: Long?,
)

/** The agent's verdict on an existing intention after re-evaluating it. action: keep | cancel | reschedule. */
data class PlanUpdate(val id: Long, val action: String, val reason: String, val windowStart: String?, val windowEnd: String?)

/**
 * The agent's near-term plan: temporary intentions, not appointments (docs/AGENT.md, D-043).
 *
 * An intention never fires by itself. Its window only wakes the agent, which then decides again with fresh context
 * whether to contact the person, change the plan, or drop it. Code here only stores, validates (24 h horizon, window
 * length, quiet hours, a small number of open items), expires, and records why each intention ended.
 */
class Plan(private val store: Store) {

    companion object {
        val HORIZON: Duration = Duration.ofHours(24)
        val MAX_WINDOW: Duration = Duration.ofHours(4)
        val MIN_WINDOW: Duration = Duration.ofMinutes(30)
        const val MAX_OPEN = 5
        /** Statuses of an intention that may still happen. */
        val OPEN = setOf("planned", "muted")
    }

    fun open(): List<Row> = store.query("SELECT * FROM intentions WHERE status IN ('planned','muted') ORDER BY window_start")
    fun get(id: Long): Row? = store.one("SELECT * FROM intentions WHERE id = ?", id)
    fun recent(now: Instant, hours: Long = 12): List<Row> =
        store.query("SELECT * FROM intentions WHERE status NOT IN ('planned','muted') AND resolved_at >= ? ORDER BY resolved_at DESC",
            TimeUtil.iso(now.minus(Duration.ofHours(hours))))

    /** "HH:MM" local -> the next occurrence (a start up to 15 min in the past means "now"); null if invalid. */
    fun window(now: Instant, start: String, end: String?): Pair<Instant, Instant>? {
        val s = ExtractionValidator.hhmm(start) ?: return null
        val today = store.date(now)
        var from = TimeUtil.atLocal(today, s, store.tz)
        if (from.isBefore(now.minus(Duration.ofMinutes(15)))) from = from.plus(Duration.ofDays(1))
        if (from.isBefore(now)) from = now
        var to = end?.let(ExtractionValidator::hhmm)?.let { TimeUtil.atLocal(store.date(from), it, store.tz) } ?: from.plus(Duration.ofMinutes(60))
        if (!to.isAfter(from)) to = to.plus(Duration.ofDays(1))
        if (Duration.between(from, to) > MAX_WINDOW) to = from.plus(MAX_WINDOW)
        if (Duration.between(from, to) < MIN_WINDOW) to = from.plus(MIN_WINDOW)
        if (Duration.between(now, from) > HORIZON) return null
        return from to to
    }

    private fun inQuietHours(at: Instant): Boolean {
        val p = store.profile()
        return TimeUtil.inWindow(TimeUtil.localTime(at, store.tz), TimeUtil.hhmm(p.optString("quiet_start", "22:30")), TimeUtil.hhmm(p.optString("quiet_end", "07:30")))
    }

    /** Validates and stores a new intention; returns its id or a reason it was rejected. */
    fun add(now: Instant, d: PlanDraft): Pair<Long?, String> {
        val w = window(now, d.windowStart, d.windowEnd) ?: return null to "invalid or beyond 24 h window '${d.windowStart}'"
        if (inQuietHours(w.first)) return null to "window ${d.windowStart} is inside quiet hours"
        if (open().size >= MAX_OPEN) return null to "already $MAX_OPEN open intentions"
        val id = store.insert("intentions", mapOf(
            "created_at" to TimeUtil.iso(now), "updated_at" to TimeUtil.iso(now), "status" to "planned",
            "title" to d.title.take(120), "reason" to d.reason.take(400), "channel" to d.channel, "intent" to d.intent.take(40),
            "window_start" to TimeUtil.iso(w.first), "window_end" to TimeUtil.iso(w.second), "skip_if" to d.skipIf?.take(200),
            "commitment_id" to d.commitmentId,
        ))
        return id to "planned"
    }

    /** Applies the agent's re-evaluation of open intentions. Returns log lines. */
    fun apply(now: Instant, updates: List<PlanUpdate>): List<String> = updates.mapNotNull { u ->
        // Muted intentions belong to the person: only they (un-mute) or the window's end change them.
        val row = get(u.id)?.takeIf { it.str("status") == "planned" } ?: return@mapNotNull null
        when (u.action) {
            "cancel" -> { resolve(now, u.id, "cancelled", u.reason); "cancelled #${u.id} ${row.str("title")}: ${u.reason}" }
            "reschedule" -> {
                val w = u.windowStart?.let { window(now, it, u.windowEnd) } ?: return@mapNotNull "reschedule of #${u.id} rejected: invalid window"
                if (inQuietHours(w.first)) return@mapNotNull "reschedule of #${u.id} rejected: quiet hours"
                store.exec("UPDATE intentions SET window_start = ?, window_end = ?, updated_at = ?, note = ? WHERE id = ?",
                    TimeUtil.iso(w.first), TimeUtil.iso(w.second), TimeUtil.iso(now), "rescheduled: ${u.reason}".take(300), u.id)
                "rescheduled #${u.id} ${row.str("title")} to ${store.fmtLocal(w.first, "HH:mm")}: ${u.reason}"
            }
            else -> null
        }
    }

    fun resolve(now: Instant, id: Long, status: String, resolution: String, interventionId: Long? = null) =
        store.exec("UPDATE intentions SET status = ?, resolution = ?, resolved_at = ?, updated_at = ?, intervention_id = COALESCE(?, intervention_id) WHERE id = ?",
            status, resolution.take(300), TimeUtil.iso(now), TimeUtil.iso(now), interventionId, id)

    /** User control: a muted intention stays visible but the agent may not carry it out. */
    fun mute(now: Instant, id: Long, muted: Boolean): Boolean {
        val row = get(id)?.takeIf { it.str("status") in OPEN } ?: return false
        store.exec("UPDATE intentions SET status = ?, updated_at = ? WHERE id = ?", if (muted) "muted" else "planned", TimeUtil.iso(now), id)
        store.addObservation(now, "user_${if (muted) "muted" else "unmuted"}_plan",
            "The user ${if (muted) "muted" else "un-muted"} your planned action: ${row.str("title")}", significant = false)
        return true
    }

    /** Intentions whose window passed without being carried out. */
    fun expire(now: Instant) = open().filter { TimeUtil.parse(it.str("window_end")!!).isBefore(now) }.forEach {
        resolve(now, it.long("id")!!, "expired", if (it.str("status") == "muted") "muted by you; window passed" else "window passed without contacting you")
    }

    /** Earliest window start of an intention the agent may still act on (muted ones do not wake it). */
    fun nextWake(): Pair<Instant, String>? = open().filter { it.str("status") == "planned" }
        .map { TimeUtil.parse(it.str("window_start")!!) to (it.str("title") ?: "") }.minByOrNull { it.first }

    /** What the agent sees about its own plan. */
    fun asSituation(now: Instant): JSONObject {
        fun item(r: Row): JSONObject {
            val s = TimeUtil.parse(r.str("window_start")!!); val e = TimeUtil.parse(r.str("window_end")!!)
            return JSONObject().put("intention_id", r.long("id")).put("title", r.str("title")).put("reason", r.str("reason"))
                .put("channel", r.str("channel")).put("window", "${store.fmtLocal(s, "EEE HH:mm")}-${store.fmtLocal(e, "HH:mm")}")
                .put("status", when {
                    r.str("status") == "muted" -> "muted_by_user"
                    !now.isBefore(s) -> "window_open_now"
                    else -> "in_${Duration.between(now, s).toMinutes()}_min"
                })
                .put("skip_if", r.str("skip_if") ?: JSONObject.NULL).put("planned_at", store.fmtLocal(TimeUtil.parse(r.str("created_at")!!), "HH:mm"))
        }
        return JSONObject()
            .put("open_intentions", JSONArray(open().map(::item)))
            .put("recently_resolved", JSONArray(recent(now).take(8).map {
                JSONObject().put("title", it.str("title")).put("status", it.str("status")).put("resolution", it.str("resolution"))
            }))
            .put("max_open", MAX_OPEN).put("horizon", "next 24 hours")
    }
}
