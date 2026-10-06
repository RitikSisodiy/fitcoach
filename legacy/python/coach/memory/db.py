"""SQLite schema and connection management.

The database is the single source of truth. The LLM is never used as storage.
Schema changes are applied as ordered migrations recorded in `schema_version`.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

MIGRATIONS: list[str] = [
    # --- v1 -----------------------------------------------------------------
    """
    CREATE TABLE profile (
        key TEXT PRIMARY KEY,
        value_json TEXT NOT NULL,
        updated_at TEXT NOT NULL
    );

    -- Long-lived knowledge about the user. Never deleted; superseded instead.
    CREATE TABLE facts (
        id INTEGER PRIMARY KEY,
        category TEXT NOT NULL,          -- preference | constraint | routine | goal | pattern | health | other
        key TEXT NOT NULL,               -- normalised slug, e.g. "work_mode"
        value TEXT NOT NULL,
        source TEXT NOT NULL,            -- user_stated | inferred | observed
        source_message_id INTEGER,
        confidence REAL NOT NULL,
        created_at TEXT NOT NULL,
        last_verified_at TEXT NOT NULL,
        superseded_by INTEGER REFERENCES facts(id)
    );
    CREATE INDEX idx_facts_active ON facts(category, key) WHERE superseded_by IS NULL;

    CREATE TABLE messages (
        id INTEGER PRIMARY KEY,
        direction TEXT NOT NULL,         -- in | out
        kind TEXT NOT NULL,              -- text | voice | photo | system | nudge
        text TEXT NOT NULL,
        created_at TEXT NOT NULL,
        external_id TEXT UNIQUE          -- e.g. telegram update id
    );
    CREATE VIRTUAL TABLE messages_fts USING fts5(text, content='messages', content_rowid='id');
    CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
        INSERT INTO messages_fts(rowid, text) VALUES (new.id, new.text);
    END;

    CREATE TABLE food_events (
        id INTEGER PRIMARY KEY,
        occurred_at TEXT NOT NULL,
        local_date TEXT NOT NULL,
        meal_slot TEXT,
        item_name TEXT NOT NULL,
        food_key TEXT,                   -- key in the food table when matched
        quantity REAL,
        unit TEXT,
        kcal_low REAL,
        kcal_high REAL,
        protein_low REAL,
        protein_high REAL,
        nutrition_source TEXT NOT NULL,  -- food_table | llm_estimate | unknown
        data_status TEXT NOT NULL,       -- observed | estimated | inferred | unknown
        confidence REAL NOT NULL,
        source_message_id INTEGER REFERENCES messages(id),
        created_at TEXT NOT NULL
    );
    CREATE INDEX idx_food_date ON food_events(local_date);

    CREATE TABLE activity_events (
        id INTEGER PRIMARY KEY,
        occurred_at TEXT NOT NULL,
        local_date TEXT NOT NULL,
        kind TEXT NOT NULL,              -- walk | workout | sport | steps | other
        duration_min REAL,
        steps INTEGER,
        source TEXT NOT NULL,            -- user_reported | health_connect
        confidence REAL NOT NULL,
        source_message_id INTEGER REFERENCES messages(id),
        created_at TEXT NOT NULL
    );
    CREATE INDEX idx_activity_date ON activity_events(local_date);

    CREATE TABLE body_metrics (
        id INTEGER PRIMARY KEY,
        measured_at TEXT NOT NULL,
        local_date TEXT NOT NULL,
        metric TEXT NOT NULL,            -- weight_kg | waist_cm
        value REAL NOT NULL,
        source TEXT NOT NULL,            -- user_reported | health_connect
        natural_key TEXT UNIQUE,
        created_at TEXT NOT NULL
    );
    CREATE INDEX idx_body_metric ON body_metrics(metric, local_date);

    CREATE TABLE context_events (
        id INTEGER PRIMARY KEY,
        occurred_at TEXT NOT NULL,
        local_date TEXT NOT NULL,
        tag TEXT NOT NULL,               -- normalised trigger tag, e.g. "chess"
        timing TEXT NOT NULL,            -- now | planned | past
        description TEXT,
        source_message_id INTEGER REFERENCES messages(id)
    );
    CREATE INDEX idx_context_tag ON context_events(tag, local_date);

    -- Raw Health Connect records, upserted on a natural key for idempotency.
    CREATE TABLE health_records (
        id INTEGER PRIMARY KEY,
        record_type TEXT NOT NULL,
        natural_key TEXT NOT NULL UNIQUE,
        start_time TEXT,
        end_time TEXT,
        local_date TEXT NOT NULL,
        value REAL,
        payload_json TEXT NOT NULL,
        received_at TEXT NOT NULL
    );
    CREATE INDEX idx_health_type_date ON health_records(record_type, local_date);

    CREATE TABLE commitments (
        id INTEGER PRIMARY KEY,
        kind TEXT NOT NULL,              -- if_then | habit | precommitment | boundary
        title TEXT NOT NULL,
        trigger_tag TEXT,                -- for if_then / precommitment
        action TEXT NOT NULL,
        versions_json TEXT NOT NULL,     -- fallback ladder, full version first
        schedule_days TEXT,              -- e.g. "mon,tue,wed" or "daily"; NULL = no schedule
        window_start TEXT,               -- "HH:MM" local
        window_end TEXT,
        enforcement TEXT NOT NULL,       -- normal | strong (strong needs explicit user authorisation)
        is_focus INTEGER NOT NULL DEFAULT 0,
        status TEXT NOT NULL,            -- active | paused | retired
        user_words TEXT,
        source_message_id INTEGER,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
    );

    CREATE TABLE commitment_log (
        id INTEGER PRIMARY KEY,
        commitment_id INTEGER NOT NULL REFERENCES commitments(id),
        local_date TEXT NOT NULL,
        outcome TEXT NOT NULL,           -- done | smaller | skipped | postponed
        version TEXT,
        reason_category TEXT,            -- cannot | forgot | dont_want | too_hard | bad_timing | unknown
        source TEXT NOT NULL,            -- user_reported | button | observed
        created_at TEXT NOT NULL
    );
    CREATE INDEX idx_commitment_log ON commitment_log(commitment_id, local_date);

    CREATE TABLE interventions (
        id INTEGER PRIMARY KEY,
        commitment_id INTEGER REFERENCES commitments(id),
        kind TEXT NOT NULL,              -- scheduled | contextual | lapse_recovery | review
        level INTEGER NOT NULL,
        slot TEXT,                       -- "HH:MM" bucket used by the learner
        style TEXT,
        version_offered TEXT,
        message_text TEXT NOT NULL,
        reason TEXT NOT NULL,            -- concise decision summary
        status TEXT NOT NULL,            -- sent | acted | smaller | snoozed | skipped | ignored
        reason_category TEXT,
        local_date TEXT NOT NULL,
        sent_at TEXT NOT NULL,
        responded_at TEXT
    );
    CREATE INDEX idx_interventions_date ON interventions(local_date);

    -- Beta-Bernoulli statistics for slot learning, per commitment.
    CREATE TABLE slot_stats (
        commitment_id INTEGER NOT NULL,
        slot TEXT NOT NULL,
        alpha REAL NOT NULL,
        beta REAL NOT NULL,
        updated_at TEXT NOT NULL,
        PRIMARY KEY (commitment_id, slot)
    );

    -- Observability: concise decision summaries (never chain-of-thought).
    CREATE TABLE coach_decisions (
        id INTEGER PRIMARY KEY,
        created_at TEXT NOT NULL,
        kind TEXT NOT NULL,
        summary TEXT NOT NULL,
        data_json TEXT
    );
    """,
    # --- v2: autonomy, passive signals, engagement, patterns -----------------
    """
    ALTER TABLE interventions ADD COLUMN intent TEXT;
    ALTER TABLE food_events ADD COLUMN source TEXT NOT NULL DEFAULT 'user_entered';
    ALTER TABLE context_events ADD COLUMN time_hint TEXT;
    ALTER TABLE commitments ADD COLUMN activity_kind TEXT;

    -- Events the system inferred from passive signals; they become data only after confirmation.
    CREATE TABLE inferred_events (
        id INTEGER PRIMARY KEY,
        kind TEXT NOT NULL,              -- food_order | small_payment
        occurred_at TEXT NOT NULL,
        local_date TEXT NOT NULL,
        summary TEXT NOT NULL,
        payee_key TEXT,
        amount REAL,
        payload_json TEXT NOT NULL,
        confidence REAL NOT NULL,
        status TEXT NOT NULL,            -- pending | confirmed | rejected | expired
        natural_key TEXT NOT NULL UNIQUE,
        created_at TEXT NOT NULL,
        resolved_at TEXT
    );
    CREATE INDEX idx_inferred_status ON inferred_events(status, local_date);

    -- What the user told us about a payee ("chai stall" = food, "landlord" = not food).
    CREATE TABLE payee_labels (
        payee_key TEXT PRIMARY KEY,
        label TEXT,
        is_food INTEGER NOT NULL,
        updated_at TEXT NOT NULL
    );

    -- Patterns mined deterministically from logs. Counts are computed in code, never by the LLM.
    CREATE TABLE patterns (
        id INTEGER PRIMARY KEY,
        kind TEXT NOT NULL,              -- weekday_context | context_lapse | meal_gap | response_hour
        key TEXT NOT NULL UNIQUE,
        claim TEXT NOT NULL,
        support INTEGER NOT NULL,
        contradict INTEGER NOT NULL,
        distinct_weeks INTEGER NOT NULL,
        confidence REAL NOT NULL,
        status TEXT NOT NULL,            -- candidate | active | rejected
        data_json TEXT NOT NULL,
        first_seen TEXT NOT NULL,
        last_confirmed TEXT NOT NULL,
        updated_at TEXT NOT NULL
    );

    CREATE TABLE calendar_days (
        local_date TEXT PRIMARY KEY,
        meeting_hours REAL NOT NULL,
        first_start TEXT,
        last_end TEXT,
        free_slots_json TEXT NOT NULL,
        updated_at TEXT NOT NULL
    );

    CREATE TABLE job_runs (
        job TEXT NOT NULL,
        run_key TEXT NOT NULL,
        ran_at TEXT NOT NULL,
        PRIMARY KEY (job, run_key)
    );
    """,
]


def connect(path: str) -> sqlite3.Connection:
    if path != ":memory:":
        Path(path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    if path != ":memory:":
        conn.execute("PRAGMA journal_mode = WAL")
    migrate(conn)
    return conn


def migrate(conn: sqlite3.Connection) -> int:
    conn.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
    row = conn.execute("SELECT MAX(version) AS v FROM schema_version").fetchone()
    current = row["v"] or 0
    for index, sql in enumerate(MIGRATIONS[current:], start=current + 1):
        conn.execute("BEGIN")
        try:
            for statement in _split_sql(sql):
                conn.execute(statement)
            conn.execute("INSERT INTO schema_version(version) VALUES (?)", (index,))
            conn.execute("COMMIT")
        except Exception:
            conn.execute("ROLLBACK")
            raise
    return len(MIGRATIONS)


def _split_sql(script: str) -> list[str]:
    """Split a migration script into statements, keeping trigger bodies intact."""
    statements: list[str] = []
    buffer: list[str] = []
    in_trigger = False
    for line in script.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("--"):
            continue
        buffer.append(line)
        upper = stripped.upper()
        if upper.startswith("CREATE TRIGGER"):
            in_trigger = True
        if in_trigger:
            if upper == "END;":
                statements.append("\n".join(buffer))
                buffer, in_trigger = [], False
        elif stripped.endswith(";"):
            statements.append("\n".join(buffer))
            buffer = []
    if buffer:
        statements.append("\n".join(buffer))
    return statements
