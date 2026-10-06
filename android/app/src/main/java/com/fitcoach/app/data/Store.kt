package com.fitcoach.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.fitcoach.app.core.TimeUtil
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** A row as a plain map (column -> value). Keeps the port of the Python engine close to the original. */
typealias Row = Map<String, Any?>

fun Row.str(k: String): String? = this[k]?.toString()
fun Row.dbl(k: String): Double? = (this[k] as? Number)?.toDouble()
fun Row.long(k: String): Long? = (this[k] as? Number)?.toLong()

val COACHING_MODES = listOf("gentle", "normal", "accountability", "strong")

fun normalizeTag(tag: String): String =
    tag.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48)

data class Commitment(
    val id: Long,
    val kind: String,
    val title: String,
    val triggerTag: String?,
    val action: String,
    val versions: List<String>,
    val scheduleDays: String?,
    val windowStart: String?,
    val windowEnd: String?,
    val enforcement: String,
    val status: String,
    val userWords: String?,
    val createdAt: String,
    val activityKind: String?,
) {
    fun asContext(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind).put("title", title).put("trigger", triggerTag ?: JSONObject.NULL)
        .put("action", action).put("fallback_versions", JSONArray(versions))
        .put("schedule", scheduleDays ?: JSONObject.NULL)
        .put("window", if (windowStart != null) "$windowStart-$windowEnd" else JSONObject.NULL)
        .put("enforcement", enforcement).put("user_words", userWords ?: JSONObject.NULL)

    companion object {
        fun from(r: Row): Commitment {
            val arr = JSONArray(r.str("versions_json") ?: "[]")
            return Commitment(
                id = r.long("id")!!, kind = r.str("kind")!!, title = r.str("title")!!, triggerTag = r.str("trigger_tag"),
                action = r.str("action")!!, versions = List(arr.length()) { arr.getString(it) },
                scheduleDays = r.str("schedule_days"), windowStart = r.str("window_start"), windowEnd = r.str("window_end"),
                enforcement = r.str("enforcement") ?: "normal", status = r.str("status") ?: "active",
                userWords = r.str("user_words"), createdAt = r.str("created_at") ?: "", activityKind = r.str("activity_kind"),
            )
        }
    }
}

/** Repository over SQLite. All writes go through here. */
class Store(val db: SQLiteDatabase, val tz: ZoneId) {

    // ---------------------------------------------------------------- utils
    fun date(t: Instant): String = TimeUtil.localDate(t, tz)

    fun fmtLocal(t: Instant, pattern: String = "dd MMM HH:mm"): String =
        java.time.format.DateTimeFormatter.ofPattern(pattern).format(t.atZone(tz))

    fun query(sql: String, vararg args: Any?): List<Row> {
        val cursor = db.rawQuery(sql, args.map { it?.toString() }.toTypedArray())
        return cursor.use { c -> buildList { while (c.moveToNext()) add(rowOf(c)) } }
    }

    fun one(sql: String, vararg args: Any?): Row? = query(sql, *args).firstOrNull()

    fun exec(sql: String, vararg args: Any?) = db.execSQL(sql, args)

    private fun rowOf(c: Cursor): Row = buildMap {
        for (i in 0 until c.columnCount) {
            put(
                c.getColumnName(i),
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    else -> c.getString(i)
                },
            )
        }
    }

    fun insert(table: String, values: Map<String, Any?>): Long {
        val cv = ContentValues()
        values.forEach { (k, v) ->
            when (v) {
                null -> cv.putNull(k)
                is Int -> cv.put(k, v)
                is Long -> cv.put(k, v)
                is Double -> cv.put(k, v)
                is Float -> cv.put(k, v.toDouble())
                is Boolean -> cv.put(k, if (v) 1 else 0)
                else -> cv.put(k, v.toString())
            }
        }
        return db.insertOrThrow(table, null, cv)
    }

    fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    // -------------------------------------------------------------- profile
    fun profile(): JSONObject {
        val p = JSONObject(PROFILE_DEFAULTS.toString())
        query("SELECT key, value_json FROM profile").forEach { r ->
            p.put(r.str("key")!!, JSONObject("{\"v\":${r.str("value_json")}}").get("v"))
        }
        return p
    }

    fun setProfile(now: Instant, key: String, value: Any?) {
        require(PROFILE_DEFAULTS.has(key)) { "Unknown profile key '$key'" }
        val json = JSONObject().put("v", value ?: JSONObject.NULL).toString().removePrefix("{\"v\":").removeSuffix("}")
        exec(
            "INSERT INTO profile(key, value_json, updated_at) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET " +
                "value_json=excluded.value_json, updated_at=excluded.updated_at",
            key, json, TimeUtil.iso(now),
        )
    }

    // ------------------------------------------------------------- messages
    fun addMessage(now: Instant, direction: String, kind: String, text: String, externalId: String? = null, buttons: JSONArray? = null): Long =
        insert("messages", mapOf("direction" to direction, "kind" to kind, "text" to text, "created_at" to TimeUtil.iso(now),
            "external_id" to externalId, "buttons_json" to buttons?.toString()))

    /** Buttons are one-shot: once one is pressed, the row keeps only the text. */
    fun clearButtons(messageId: Long) = exec("UPDATE messages SET buttons_json = NULL WHERE id = ?", messageId)

    fun setLatestSkipReason(commitmentId: Long, day: String, reason: String): Boolean {
        val r = one("SELECT id FROM commitment_log WHERE commitment_id = ? AND local_date = ? AND outcome = 'skipped' ORDER BY id DESC LIMIT 1", commitmentId, day)
            ?: return false
        exec("UPDATE commitment_log SET reason_category = ? WHERE id = ?", reason, r.long("id"))
        return true
    }

    fun contextTagCounts(sinceDay: String): Map<String, Int> =
        query("SELECT tag, COUNT(*) AS n FROM context_events WHERE local_date >= ? GROUP BY tag ORDER BY n DESC LIMIT 10", sinceDay)
            .associate { it.str("tag")!! to (it.long("n") ?: 0L).toInt() }

    fun messageExists(externalId: String) = one("SELECT 1 AS x FROM messages WHERE external_id = ?", externalId) != null

    fun recentMessages(limit: Int = 12): List<Row> =
        query("SELECT * FROM messages WHERE kind != 'system' ORDER BY id DESC LIMIT ?", limit).reversed()

    fun allMessages(limit: Int = 300): List<Row> = query("SELECT * FROM messages ORDER BY id DESC LIMIT ?", limit).reversed()

    fun searchMessages(term: String, limit: Int = 10): List<Row> =
        query("SELECT * FROM messages WHERE text LIKE ? ORDER BY id DESC LIMIT ?", "%${term.replace("%", "")}%", limit)

    fun lastInboundAt(): String? = one("SELECT MAX(created_at) AS t FROM messages WHERE direction = 'in'")?.str("t")

    fun inboundTimesSince(sinceIso: String): List<String> =
        query("SELECT created_at FROM messages WHERE direction = 'in' AND created_at >= ? ORDER BY created_at", sinceIso).mapNotNull { it.str("created_at") }

    // ---------------------------------------------------------------- facts
    fun upsertFact(now: Instant, category: String, rawKey: String, value: String, source: String, confidence: Double, msgId: Long? = null): Long {
        val key = normalizeTag(rawKey)
        val cur = one("SELECT * FROM facts WHERE category = ? AND key = ? AND superseded_by IS NULL", category, key)
        if (cur != null && cur.str("value")!!.trim().equals(value.trim(), ignoreCase = true)) {
            exec("UPDATE facts SET last_verified_at = ?, confidence = MAX(confidence, ?) WHERE id = ?", TimeUtil.iso(now), confidence, cur.long("id"))
            return cur.long("id")!!
        }
        val id = insert(
            "facts", mapOf(
                "category" to category, "key" to key, "value" to value, "source" to source, "source_message_id" to msgId,
                "confidence" to confidence, "created_at" to TimeUtil.iso(now), "last_verified_at" to TimeUtil.iso(now),
            ),
        )
        if (cur != null) exec("UPDATE facts SET superseded_by = ? WHERE id = ?", id, cur.long("id"))
        return id
    }

    fun activeFacts(): List<Row> = query("SELECT * FROM facts WHERE superseded_by IS NULL ORDER BY category, key")

    // ----------------------------------------------------------------- food
    fun addFood(now: Instant, occurred: Instant, values: Map<String, Any?>): Long =
        insert("food_events", values + mapOf("occurred_at" to TimeUtil.iso(occurred), "local_date" to date(occurred), "created_at" to TimeUtil.iso(now)))

    fun foodOn(day: String): List<Row> = query("SELECT * FROM food_events WHERE local_date = ? ORDER BY occurred_at", day)

    fun foodBetween(start: String, end: String): List<Row> =
        query("SELECT * FROM food_events WHERE local_date >= ? AND local_date <= ? ORDER BY occurred_at", start, end)

    fun deleteFood(id: Long) = exec("DELETE FROM food_events WHERE id = ?", id)

    fun updateFood(id: Long, values: Map<String, Any?>) {
        if (values.isEmpty()) return
        exec("UPDATE food_events SET ${values.keys.joinToString { "$it = ?" }} WHERE id = ?", *values.values.toTypedArray(), id)
    }

    fun foodLoggedDays(endDay: String, days: Long): Int =
        one("SELECT COUNT(DISTINCT local_date) AS n FROM food_events WHERE local_date > ? AND local_date <= ?", TimeUtil.daysAgo(endDay, days), endDay)!!.long("n")!!.toInt()

    // ------------------------------------------------------------- activity
    fun addActivity(now: Instant, occurred: Instant, values: Map<String, Any?>): Long =
        insert("activity_events", values + mapOf("occurred_at" to TimeUtil.iso(occurred), "local_date" to date(occurred), "created_at" to TimeUtil.iso(now)))

    fun activitiesOn(day: String): List<Row> = query("SELECT * FROM activity_events WHERE local_date = ? ORDER BY occurred_at", day)

    // --------------------------------------------------------- body metrics
    fun addMetric(now: Instant, at: Instant, metric: String, value: Double, source: String, naturalKey: String? = null): Long? = try {
        insert(
            "body_metrics", mapOf(
                "measured_at" to TimeUtil.iso(at), "local_date" to date(at), "metric" to metric, "value" to value,
                "source" to source, "natural_key" to naturalKey, "created_at" to TimeUtil.iso(now),
            ),
        )
    } catch (e: android.database.sqlite.SQLiteConstraintException) {
        null
    }

    /** One value per local day (earliest of the day). */
    fun metricSeries(metric: String, sinceDay: String): List<Pair<String, Double>> {
        val out = linkedMapOf<String, Double>()
        query("SELECT local_date, value FROM body_metrics WHERE metric = ? AND local_date >= ? ORDER BY local_date, measured_at", metric, sinceDay)
            .forEach { r -> out.putIfAbsent(r.str("local_date")!!, r.dbl("value")!!) }
        return out.toList()
    }

    // -------------------------------------------------------------- context
    fun addContext(now: Instant, tag: String, timing: String, description: String?, msgId: Long?, forTomorrow: Boolean = false,
                   timeHint: String? = null, source: String = "said"): Long {
        val whenT = if (forTomorrow) now.plusSeconds(86400) else now
        return insert(
            "context_events", mapOf(
                "occurred_at" to TimeUtil.iso(whenT), "local_date" to date(whenT), "tag" to normalizeTag(tag), "timing" to timing,
                "description" to description, "source_message_id" to msgId, "time_hint" to timeHint, "source" to source,
            ),
        )
    }

    fun knownTags(limit: Int = 40): List<String> = query(
        "SELECT tag FROM (SELECT tag, MAX(occurred_at) AS last FROM context_events GROUP BY tag " +
            "UNION SELECT trigger_tag AS tag, updated_at AS last FROM commitments WHERE trigger_tag IS NOT NULL " +
            "UNION SELECT tag, created_at AS last FROM places) GROUP BY tag ORDER BY MAX(last) DESC LIMIT ?", limit,
    ).mapNotNull { it.str("tag") }

    fun contextSince(day: String): List<Row> = query("SELECT * FROM context_events WHERE local_date >= ? ORDER BY occurred_at", day)

    // ------------------------------------------------------- health records
    fun upsertHealth(now: Instant, type: String, naturalKey: String, start: Instant?, end: Instant?, anchor: Instant, value: Double?, payload: JSONObject): Boolean {
        val existing = one("SELECT payload_json FROM health_records WHERE natural_key = ?", naturalKey)
        val pj = payload.toString()
        if (existing != null && existing.str("payload_json") == pj) return false
        exec(
            "INSERT INTO health_records(record_type, natural_key, start_time, end_time, local_date, value, payload_json, received_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(natural_key) DO UPDATE SET value=excluded.value, " +
                "payload_json=excluded.payload_json, received_at=excluded.received_at",
            type, naturalKey, start?.let(TimeUtil::iso), end?.let(TimeUtil::iso), date(anchor), value, pj, TimeUtil.iso(now),
        )
        return true
    }

    fun healthSum(type: String, day: String): Double? {
        val r = one("SELECT SUM(value) AS total, COUNT(*) AS n FROM health_records WHERE record_type = ? AND local_date = ?", type, day)!!
        return if ((r.long("n") ?: 0) > 0) r.dbl("total") else null
    }

    fun healthBetween(type: String, start: String, end: String): List<Row> =
        query("SELECT * FROM health_records WHERE record_type = ? AND local_date >= ? AND local_date <= ? ORDER BY start_time", type, start, end)

    fun lastHealthSync(): String? = one("SELECT MAX(received_at) AS t FROM health_records")?.str("t")

    // ---------------------------------------------------------- commitments
    fun addCommitment(now: Instant, values: Map<String, Any?>, versions: List<String>): Long {
        val v = values.toMutableMap()
        (v["trigger_tag"] as? String)?.let { v["trigger_tag"] = normalizeTag(it) }
        if (v["window_start"] != null && v["schedule_days"] == null) v["schedule_days"] = "daily"
        return insert(
            "commitments", v + mapOf(
                "versions_json" to JSONArray(versions.ifEmpty { listOf(v["action"].toString()) }).toString(),
                "status" to "active", "created_at" to TimeUtil.iso(now), "updated_at" to TimeUtil.iso(now),
                "enforcement" to (v["enforcement"] ?: "normal"),
            ),
        )
    }

    fun commitment(id: Long): Commitment? = one("SELECT * FROM commitments WHERE id = ?", id)?.let(Commitment::from)
    fun activeCommitments(): List<Commitment> = query("SELECT * FROM commitments WHERE status = 'active' ORDER BY id").map(Commitment::from)
    fun commitmentsWithTrigger(tag: String): List<Commitment> =
        query("SELECT * FROM commitments WHERE trigger_tag = ? AND status = 'active'", normalizeTag(tag)).map(Commitment::from)

    fun updateCommitment(now: Instant, id: Long, values: Map<String, Any?>) {
        val v = values.toMutableMap()
        (v.remove("versions") as? List<*>)?.let { v["versions_json"] = JSONArray(it).toString() }
        if (v.isEmpty()) return
        v["updated_at"] = TimeUtil.iso(now)
        exec("UPDATE commitments SET ${v.keys.joinToString { "$it = ?" }} WHERE id = ?", *v.values.toTypedArray(), id)
    }

    fun logCommitment(now: Instant, id: Long, outcome: String, source: String, version: String? = null, reason: String? = null, localDate: String? = null): Long =
        insert(
            "commitment_log", mapOf(
                "commitment_id" to id, "local_date" to (localDate ?: date(now)), "outcome" to outcome, "version" to version,
                "reason_category" to reason, "source" to source, "created_at" to TimeUtil.iso(now),
            ),
        )

    fun daySkipReason(id: Long, day: String): String? = one(
        "SELECT reason_category FROM commitment_log WHERE commitment_id = ? AND local_date = ? AND outcome = 'skipped' " +
            "AND reason_category IS NOT NULL ORDER BY id DESC LIMIT 1", id, day,
    )?.str("reason_category")

    fun commitmentOutcome(id: Long, day: String): String? {
        val outcomes = query("SELECT outcome FROM commitment_log WHERE commitment_id = ? AND local_date = ?", id, day).mapNotNull { it.str("outcome") }.toSet()
        return listOf("done", "smaller", "skipped", "postponed").firstOrNull { it in outcomes }
    }

    fun commitmentHistory(id: Long, sinceDay: String): List<Row> =
        query("SELECT * FROM commitment_log WHERE commitment_id = ? AND local_date >= ? ORDER BY created_at", id, sinceDay)

    // -------------------------------------------------------- interventions
    fun addIntervention(now: Instant, values: Map<String, Any?>): Long =
        insert("interventions", values + mapOf("sent_at" to TimeUtil.iso(now), "local_date" to date(now), "status" to "sent"))

    fun intervention(id: Long): Row? = one("SELECT * FROM interventions WHERE id = ?", id)

    fun updateIntervention(now: Instant, id: Long, status: String, reason: String? = null) = exec(
        "UPDATE interventions SET status = ?, responded_at = ?, reason_category = COALESCE(?, reason_category) WHERE id = ?",
        status, TimeUtil.iso(now), reason, id,
    )

    fun openInterventions(): List<Row> = query("SELECT * FROM interventions WHERE status = 'sent' ORDER BY sent_at")
    fun interventionsOn(day: String): List<Row> = query("SELECT * FROM interventions WHERE local_date = ? ORDER BY sent_at", day)
    fun interventionsSince(day: String): List<Row> = query("SELECT * FROM interventions WHERE local_date >= ? ORDER BY sent_at", day)
    fun interventionHistory(id: Long, sinceDay: String): List<Row> =
        query("SELECT * FROM interventions WHERE commitment_id = ? AND local_date >= ? ORDER BY sent_at", id, sinceDay)

    fun lastProactiveSentAt(): String? = one("SELECT MAX(sent_at) AS t FROM interventions WHERE kind != 'contextual'")?.str("t")

    fun markOpenAnswered(now: Instant) = exec(
        "UPDATE interventions SET status = 'answered', responded_at = ? WHERE status = 'sent' AND kind NOT IN ('contextual') " +
            "AND (commitment_id IS NULL OR kind = 'proactive')", TimeUtil.iso(now),
    )

    fun lastButtonAt(): String? {
        val a = one("SELECT MAX(responded_at) AS t FROM interventions WHERE status IN ('acted','smaller','skipped','snoozed')")?.str("t")
        val b = one("SELECT MAX(resolved_at) AS t FROM inferred_events WHERE status IN ('confirmed','rejected')")?.str("t")
        return listOfNotNull(a, b).maxOrNull()
    }

    // ----------------------------------------------------------- slot stats
    fun slotStats(id: Long): Map<String, Pair<Double, Double>> =
        query("SELECT slot, alpha, beta FROM slot_stats WHERE commitment_id = ?", id).associate { it.str("slot")!! to (it.dbl("alpha")!! to it.dbl("beta")!!) }

    fun setSlotStats(now: Instant, id: Long, slot: String, a: Double, b: Double) = exec(
        "INSERT INTO slot_stats(commitment_id, slot, alpha, beta, updated_at) VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT(commitment_id, slot) DO UPDATE SET alpha=excluded.alpha, beta=excluded.beta, updated_at=excluded.updated_at",
        id, slot, a, b, TimeUtil.iso(now),
    )

    // ------------------------------------------------------- inferred events
    fun addInferred(now: Instant, occurred: Instant, values: Map<String, Any?>, payload: JSONObject): Long? = try {
        insert(
            "inferred_events", values + mapOf(
                "occurred_at" to TimeUtil.iso(occurred), "local_date" to date(occurred), "payload_json" to payload.toString(),
                "status" to "pending", "created_at" to TimeUtil.iso(now),
            ),
        )
    } catch (e: android.database.sqlite.SQLiteConstraintException) {
        null
    }

    fun pendingInferred(sinceDay: String? = null): List<Row> = if (sinceDay != null)
        query("SELECT * FROM inferred_events WHERE status = 'pending' AND local_date >= ? ORDER BY occurred_at", sinceDay)
    else query("SELECT * FROM inferred_events WHERE status = 'pending' ORDER BY occurred_at")

    fun inferred(id: Long): Row? = one("SELECT * FROM inferred_events WHERE id = ?", id)

    fun resolveInferred(now: Instant, id: Long, status: String) =
        exec("UPDATE inferred_events SET status = ?, resolved_at = ? WHERE id = ?", status, TimeUtil.iso(now), id)

    fun expireInferred(now: Instant, beforeDay: String) = exec(
        "UPDATE inferred_events SET status = 'expired', resolved_at = ? WHERE status = 'pending' AND local_date < ?", TimeUtil.iso(now), beforeDay,
    )

    fun setPayeeLabel(now: Instant, key: String, isFood: Boolean, label: String? = null) = exec(
        "INSERT INTO payee_labels(payee_key, label, is_food, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT(payee_key) DO UPDATE SET " +
            "label = COALESCE(excluded.label, label), is_food = excluded.is_food, updated_at = excluded.updated_at",
        key, label, if (isFood) 1 else 0, TimeUtil.iso(now),
    )

    fun payeeLabel(key: String): Row? = one("SELECT * FROM payee_labels WHERE payee_key = ?", key)

    // -------------------------------------------------------------- patterns
    fun upsertPattern(now: Instant, p: JSONObject) {
        val key = p.getString("key")
        val existing = one("SELECT * FROM patterns WHERE key = ?", key)
        if (existing?.str("status") == "rejected") return
        val data = p.optJSONObject("data")?.toString() ?: "{}"
        if (existing != null) {
            exec(
                "UPDATE patterns SET claim = ?, support = ?, contradict = ?, distinct_weeks = ?, confidence = ?, status = ?, data_json = ?, " +
                    "last_confirmed = CASE WHEN ? > support THEN ? ELSE last_confirmed END, updated_at = ? WHERE key = ?",
                p.getString("claim"), p.getInt("support"), p.getInt("contradict"), p.getInt("distinct_weeks"), p.getDouble("confidence"),
                p.getString("status"), data, p.getInt("support"), TimeUtil.iso(now), TimeUtil.iso(now), key,
            )
        } else {
            insert(
                "patterns", mapOf(
                    "kind" to p.getString("kind"), "key" to key, "claim" to p.getString("claim"), "support" to p.getInt("support"),
                    "contradict" to p.getInt("contradict"), "distinct_weeks" to p.getInt("distinct_weeks"), "confidence" to p.getDouble("confidence"),
                    "status" to p.getString("status"), "data_json" to data, "first_seen" to TimeUtil.iso(now),
                    "last_confirmed" to TimeUtil.iso(now), "updated_at" to TimeUtil.iso(now),
                ),
            )
        }
    }

    fun patterns(status: String? = "active", kind: String? = null): List<Row> {
        val where = mutableListOf("1=1")
        val args = mutableListOf<Any?>()
        if (status != null) { where += "status = ?"; args += status }
        if (kind != null) { where += "kind = ?"; args += kind }
        return query("SELECT * FROM patterns WHERE ${where.joinToString(" AND ")} ORDER BY confidence DESC", *args.toTypedArray())
    }

    fun setPatternStatus(now: Instant, id: Long, status: String) =
        exec("UPDATE patterns SET status = ?, updated_at = ? WHERE id = ?", status, TimeUtil.iso(now), id)

    // -------------------------------------------------------------- calendar
    fun setCalendarDay(now: Instant, day: String, hours: Double, first: String?, last: String?, slots: JSONObject) = exec(
        "INSERT INTO calendar_days(local_date, meeting_hours, first_start, last_end, free_slots_json, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(local_date) DO UPDATE SET meeting_hours=excluded.meeting_hours, first_start=excluded.first_start, " +
            "last_end=excluded.last_end, free_slots_json=excluded.free_slots_json, updated_at=excluded.updated_at",
        day, hours, first, last, slots.toString(), TimeUtil.iso(now),
    )

    fun calendarDay(day: String): JSONObject? {
        val r = one("SELECT * FROM calendar_days WHERE local_date = ?", day) ?: return null
        val slots = JSONObject(r.str("free_slots_json") ?: "{}")
        return JSONObject().put("meeting_hours", r.dbl("meeting_hours")).put("first_start", r.str("first_start"))
            .put("last_end", r.str("last_end")).put("free_slots", slots.optJSONArray("free") ?: JSONArray())
            .put("busy", slots.optJSONArray("busy") ?: JSONArray())
    }

    // ---------------------------------------------------------------- places
    fun addPlace(now: Instant, tag: String, label: String, lat: Double, lng: Double, radius: Double): Long {
        exec("DELETE FROM places WHERE tag = ?", normalizeTag(tag))
        return insert("places", mapOf("tag" to normalizeTag(tag), "label" to label, "lat" to lat, "lng" to lng, "radius_m" to radius, "created_at" to TimeUtil.iso(now)))
    }

    fun places(): List<Row> = query("SELECT * FROM places ORDER BY id")
    fun deletePlace(tag: String) = exec("DELETE FROM places WHERE tag = ?", tag)

    // ---------------------------------------------------------- screen time
    fun setScreenDay(now: Instant, day: String, minutes: Int, longest: Int, lateNight: Int) = exec(
        "INSERT INTO screen_days(local_date, screen_minutes, longest_session_min, late_night_minutes, updated_at) VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT(local_date) DO UPDATE SET screen_minutes=excluded.screen_minutes, longest_session_min=excluded.longest_session_min, " +
            "late_night_minutes=excluded.late_night_minutes, updated_at=excluded.updated_at",
        day, minutes, longest, lateNight, TimeUtil.iso(now),
    )

    fun screenDay(day: String): Row? = one("SELECT * FROM screen_days WHERE local_date = ?", day)

    // ------------------------------------------------------------------ jobs
    fun jobDone(job: String, key: String) = one("SELECT 1 AS x FROM job_runs WHERE job = ? AND run_key = ?", job, key) != null
    fun markJob(now: Instant, job: String, key: String) =
        exec("INSERT OR IGNORE INTO job_runs(job, run_key, ran_at) VALUES (?, ?, ?)", job, key, TimeUtil.iso(now))

    // ------------------------------------------------------------- decisions
    fun logDecision(now: Instant, kind: String, summary: String, data: JSONObject? = null): Long =
        insert("coach_decisions", mapOf("created_at" to TimeUtil.iso(now), "kind" to kind, "summary" to summary.take(600), "data_json" to data?.toString()))

    fun recentDecisions(limit: Int = 50): List<Row> = query("SELECT * FROM coach_decisions ORDER BY id DESC LIMIT ?", limit)

    companion object {
        val PROFILE_DEFAULTS: JSONObject = JSONObject()
            .put("coaching_mode", "normal").put("strong_mode_authorized", false).put("daily_message_budget", 3)
            .put("quiet_start", "22:30").put("quiet_end", "07:30").put("paused_until", JSONObject.NULL)
            .put("height_cm", JSONObject.NULL).put("goal_weight_kg", JSONObject.NULL).put("kcal_target", JSONObject.NULL)
            .put("protein_target_g", JSONObject.NULL).put("never_do", JSONArray()).put("goal_text", JSONObject.NULL)
            .put("recap_time", "21:00").put("usual_meals", JSONObject())
    }
}
