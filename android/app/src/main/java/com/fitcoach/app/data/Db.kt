package com.fitcoach.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite schema: the same tables as the Python reference implementation (v0.3),
 * so behaviour, queries and documentation carry over 1:1.
 */
class Db(context: Context?, name: String? = "coach.db") :
    SQLiteOpenHelper(context, name, null, SCHEMA_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        if (databaseName != null) db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach { db.execSQL(it) }
        MIGRATIONS.forEach { m -> m.forEach { db.execSQL(it) } }
    }

    /** MIGRATIONS[i] upgrades version i+1 to i+2. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (v in oldVersion until newVersion) MIGRATIONS[v - 1].forEach { db.execSQL(it) }
    }

    companion object {
        const val SCHEMA_VERSION = 2

        val MIGRATIONS = listOf(
            // v2: agent loop, channels, memory tiers, intervention outcomes.
            listOf(
                "ALTER TABLE messages ADD COLUMN channel TEXT NOT NULL DEFAULT 'app'",
                "ALTER TABLE messages ADD COLUMN intervention_id INTEGER",
                "ALTER TABLE facts ADD COLUMN valid_until TEXT",
                "ALTER TABLE interventions ADD COLUMN channel TEXT",
                "ALTER TABLE interventions ADD COLUMN quick_replies_json TEXT",
                "ALTER TABLE interventions ADD COLUMN expected_outcome TEXT",
                "ALTER TABLE interventions ADD COLUMN outcome TEXT",
                "ALTER TABLE interventions ADD COLUMN response_minutes REAL",
                "ALTER TABLE interventions ADD COLUMN evaluated_at TEXT",
                "CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL)",
                """CREATE TABLE observations (id INTEGER PRIMARY KEY, observed_at TEXT NOT NULL, kind TEXT NOT NULL,
                    summary TEXT NOT NULL, significant INTEGER NOT NULL DEFAULT 1)""",
            ),
        )

        val SCHEMA = listOf(
            "CREATE TABLE profile (key TEXT PRIMARY KEY, value_json TEXT NOT NULL, updated_at TEXT NOT NULL)",
            """CREATE TABLE facts (id INTEGER PRIMARY KEY, category TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,
                source TEXT NOT NULL, source_message_id INTEGER, confidence REAL NOT NULL, created_at TEXT NOT NULL,
                last_verified_at TEXT NOT NULL, superseded_by INTEGER)""",
            """CREATE TABLE messages (id INTEGER PRIMARY KEY, direction TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL,
                created_at TEXT NOT NULL, external_id TEXT UNIQUE, buttons_json TEXT)""",
            """CREATE TABLE food_events (id INTEGER PRIMARY KEY, occurred_at TEXT NOT NULL, local_date TEXT NOT NULL, meal_slot TEXT,
                item_name TEXT NOT NULL, food_key TEXT, quantity REAL, unit TEXT, kcal_low REAL, kcal_high REAL, protein_low REAL,
                protein_high REAL, nutrition_source TEXT NOT NULL, data_status TEXT NOT NULL, confidence REAL NOT NULL,
                source_message_id INTEGER, created_at TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'user_entered')""",
            "CREATE INDEX idx_food_date ON food_events(local_date)",
            """CREATE TABLE activity_events (id INTEGER PRIMARY KEY, occurred_at TEXT NOT NULL, local_date TEXT NOT NULL, kind TEXT NOT NULL,
                duration_min REAL, steps INTEGER, source TEXT NOT NULL, confidence REAL NOT NULL, source_message_id INTEGER,
                created_at TEXT NOT NULL)""",
            """CREATE TABLE body_metrics (id INTEGER PRIMARY KEY, measured_at TEXT NOT NULL, local_date TEXT NOT NULL, metric TEXT NOT NULL,
                value REAL NOT NULL, source TEXT NOT NULL, natural_key TEXT UNIQUE, created_at TEXT NOT NULL)""",
            """CREATE TABLE context_events (id INTEGER PRIMARY KEY, occurred_at TEXT NOT NULL, local_date TEXT NOT NULL, tag TEXT NOT NULL,
                timing TEXT NOT NULL, description TEXT, source_message_id INTEGER, time_hint TEXT, source TEXT NOT NULL DEFAULT 'said')""",
            "CREATE INDEX idx_context_tag ON context_events(tag, local_date)",
            """CREATE TABLE health_records (id INTEGER PRIMARY KEY, record_type TEXT NOT NULL, natural_key TEXT NOT NULL UNIQUE,
                start_time TEXT, end_time TEXT, local_date TEXT NOT NULL, value REAL, payload_json TEXT NOT NULL, received_at TEXT NOT NULL)""",
            "CREATE INDEX idx_health_type_date ON health_records(record_type, local_date)",
            """CREATE TABLE commitments (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, trigger_tag TEXT, action TEXT NOT NULL,
                versions_json TEXT NOT NULL, schedule_days TEXT, window_start TEXT, window_end TEXT, enforcement TEXT NOT NULL,
                is_focus INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, user_words TEXT, source_message_id INTEGER,
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL, activity_kind TEXT)""",
            """CREATE TABLE commitment_log (id INTEGER PRIMARY KEY, commitment_id INTEGER NOT NULL, local_date TEXT NOT NULL,
                outcome TEXT NOT NULL, version TEXT, reason_category TEXT, source TEXT NOT NULL, created_at TEXT NOT NULL)""",
            "CREATE INDEX idx_commitment_log ON commitment_log(commitment_id, local_date)",
            """CREATE TABLE interventions (id INTEGER PRIMARY KEY, commitment_id INTEGER, kind TEXT NOT NULL, level INTEGER NOT NULL,
                slot TEXT, style TEXT, version_offered TEXT, message_text TEXT NOT NULL, reason TEXT NOT NULL, status TEXT NOT NULL,
                reason_category TEXT, local_date TEXT NOT NULL, sent_at TEXT NOT NULL, responded_at TEXT, intent TEXT)""",
            "CREATE INDEX idx_interventions_date ON interventions(local_date)",
            """CREATE TABLE slot_stats (commitment_id INTEGER NOT NULL, slot TEXT NOT NULL, alpha REAL NOT NULL, beta REAL NOT NULL,
                updated_at TEXT NOT NULL, PRIMARY KEY (commitment_id, slot))""",
            "CREATE TABLE coach_decisions (id INTEGER PRIMARY KEY, created_at TEXT NOT NULL, kind TEXT NOT NULL, summary TEXT NOT NULL, data_json TEXT)",
            """CREATE TABLE inferred_events (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, occurred_at TEXT NOT NULL, local_date TEXT NOT NULL,
                summary TEXT NOT NULL, payee_key TEXT, amount REAL, payload_json TEXT NOT NULL, confidence REAL NOT NULL,
                status TEXT NOT NULL, natural_key TEXT NOT NULL UNIQUE, created_at TEXT NOT NULL, resolved_at TEXT)""",
            "CREATE TABLE payee_labels (payee_key TEXT PRIMARY KEY, label TEXT, is_food INTEGER NOT NULL, updated_at TEXT NOT NULL)",
            """CREATE TABLE patterns (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, key TEXT NOT NULL UNIQUE, claim TEXT NOT NULL,
                support INTEGER NOT NULL, contradict INTEGER NOT NULL, distinct_weeks INTEGER NOT NULL, confidence REAL NOT NULL,
                status TEXT NOT NULL, data_json TEXT NOT NULL, first_seen TEXT NOT NULL, last_confirmed TEXT NOT NULL, updated_at TEXT NOT NULL)""",
            """CREATE TABLE calendar_days (local_date TEXT PRIMARY KEY, meeting_hours REAL NOT NULL, first_start TEXT, last_end TEXT,
                free_slots_json TEXT NOT NULL, updated_at TEXT NOT NULL)""",
            "CREATE TABLE job_runs (job TEXT NOT NULL, run_key TEXT NOT NULL, ran_at TEXT NOT NULL, PRIMARY KEY (job, run_key))",
            // Android-only: saved places for geofences and phone-usage aggregates.
            """CREATE TABLE places (id INTEGER PRIMARY KEY, tag TEXT NOT NULL UNIQUE, label TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL,
                radius_m REAL NOT NULL, created_at TEXT NOT NULL)""",
            """CREATE TABLE screen_days (local_date TEXT PRIMARY KEY, screen_minutes INTEGER NOT NULL, longest_session_min INTEGER NOT NULL,
                late_night_minutes INTEGER NOT NULL, updated_at TEXT NOT NULL)""",
        )
    }
}
