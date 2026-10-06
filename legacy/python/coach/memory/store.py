"""Repository layer over SQLite. All writes go through here."""

from __future__ import annotations

import json
import re
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Iterable

from ..timeutil import days_ago, local_date, to_iso

COACHING_MODES = ("gentle", "normal", "accountability", "strong")

PROFILE_DEFAULTS: dict[str, Any] = {
    "coaching_mode": "normal",
    "strong_mode_authorized": False,
    "daily_message_budget": 3,
    "quiet_start": "22:30",
    "quiet_end": "07:30",
    "paused_until": None,
    "chat_id": None,
    "height_cm": None,
    "goal_weight_kg": None,
    "kcal_target": None,
    "protein_target_g": None,
    "never_do": [],
    "goal_text": None,
    "recap_time": "21:00",
    "usual_meals": {},  # declared by the user: {slot: [{name, quantity, unit}]}
}


def normalize_tag(tag: str) -> str:
    """Normalise a context/trigger tag to a stable slug ("Chess Club " -> "chess_club")."""
    slug = re.sub(r"[^a-z0-9]+", "_", tag.strip().lower()).strip("_")
    return slug[:48]


@dataclass
class Commitment:
    id: int
    kind: str
    title: str
    trigger_tag: str | None
    action: str
    versions: list[str]
    schedule_days: str | None
    window_start: str | None
    window_end: str | None
    enforcement: str
    is_focus: bool
    status: str
    user_words: str | None
    created_at: str = ""
    activity_kind: str | None = None

    @classmethod
    def from_row(cls, row: sqlite3.Row) -> "Commitment":
        return cls(
            id=row["id"],
            kind=row["kind"],
            title=row["title"],
            trigger_tag=row["trigger_tag"],
            action=row["action"],
            versions=json.loads(row["versions_json"]),
            schedule_days=row["schedule_days"],
            window_start=row["window_start"],
            window_end=row["window_end"],
            enforcement=row["enforcement"],
            is_focus=bool(row["is_focus"]),
            status=row["status"],
            user_words=row["user_words"],
            created_at=row["created_at"],
            activity_kind=row["activity_kind"] if "activity_kind" in row.keys() else None,
        )

    def as_context(self) -> dict:
        return {
            "id": self.id,
            "kind": self.kind,
            "title": self.title,
            "trigger": self.trigger_tag,
            "action": self.action,
            "fallback_versions": self.versions,
            "schedule": self.schedule_days,
            "window": f"{self.window_start}-{self.window_end}" if self.window_start else None,
            "enforcement": self.enforcement,
            "user_words": self.user_words,
        }


class Store:
    def __init__(self, conn: sqlite3.Connection, tz: str):
        self.conn = conn
        self.tz = tz

    # ------------------------------------------------------------------ utils
    def _date(self, dt: datetime) -> str:
        return local_date(dt, self.tz)

    def fmt_local(self, dt: datetime, pattern: str = "%d %b %H:%M") -> str:
        from zoneinfo import ZoneInfo

        return dt.astimezone(ZoneInfo(self.tz)).strftime(pattern)

    @contextmanager
    def transaction(self):
        """Group writes atomically. Must not span an `await` (single shared connection)."""
        self.conn.execute("BEGIN")
        try:
            yield
        except BaseException:
            self.conn.execute("ROLLBACK")
            raise
        else:
            self.conn.execute("COMMIT")

    def _insert(self, table: str, values: dict) -> int:
        cols = ", ".join(values)
        marks = ", ".join("?" for _ in values)
        cur = self.conn.execute(f"INSERT INTO {table} ({cols}) VALUES ({marks})", tuple(values.values()))
        return int(cur.lastrowid)

    # ---------------------------------------------------------------- profile
    def get_profile(self) -> dict:
        data = dict(PROFILE_DEFAULTS)
        for row in self.conn.execute("SELECT key, value_json FROM profile"):
            data[row["key"]] = json.loads(row["value_json"])
        return data

    def set_profile(self, now: datetime, **values: Any) -> None:
        for key, value in values.items():
            if key not in PROFILE_DEFAULTS:
                raise KeyError(f"Unknown profile key '{key}'")
            self.conn.execute(
                "INSERT INTO profile(key, value_json, updated_at) VALUES (?, ?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value_json=excluded.value_json, updated_at=excluded.updated_at",
                (key, json.dumps(value), to_iso(now)),
            )

    # --------------------------------------------------------------- messages
    def add_message(
        self, now: datetime, direction: str, kind: str, text: str, external_id: str | None = None
    ) -> int:
        return self._insert(
            "messages",
            {
                "direction": direction,
                "kind": kind,
                "text": text,
                "created_at": to_iso(now),
                "external_id": external_id,
            },
        )

    def message_exists(self, external_id: str) -> bool:
        row = self.conn.execute("SELECT 1 FROM messages WHERE external_id = ?", (external_id,)).fetchone()
        return row is not None

    def recent_messages(self, limit: int = 12) -> list[sqlite3.Row]:
        rows = self.conn.execute(
            "SELECT * FROM messages WHERE kind != 'system' ORDER BY id DESC LIMIT ?", (limit,)
        ).fetchall()
        return list(reversed(rows))

    def search_messages(self, query: str, limit: int = 10) -> list[sqlite3.Row]:
        safe = " ".join(f'"{w}"' for w in re.findall(r"\w+", query))
        if not safe:
            return []
        return self.conn.execute(
            "SELECT m.* FROM messages_fts f JOIN messages m ON m.id = f.rowid "
            "WHERE messages_fts MATCH ? ORDER BY m.id DESC LIMIT ?",
            (safe, limit),
        ).fetchall()

    # ------------------------------------------------------------------ facts
    def upsert_fact(
        self,
        now: datetime,
        category: str,
        key: str,
        value: str,
        source: str,
        confidence: float,
        source_message_id: int | None = None,
    ) -> int:
        """Insert a fact; supersede the active fact with the same key if the value changed."""
        key = normalize_tag(key)
        current = self.conn.execute(
            "SELECT * FROM facts WHERE category = ? AND key = ? AND superseded_by IS NULL",
            (category, key),
        ).fetchone()
        if current and current["value"].strip().lower() == value.strip().lower():
            self.conn.execute(
                "UPDATE facts SET last_verified_at = ?, confidence = MAX(confidence, ?) WHERE id = ?",
                (to_iso(now), confidence, current["id"]),
            )
            return int(current["id"])
        new_id = self._insert(
            "facts",
            {
                "category": category,
                "key": key,
                "value": value,
                "source": source,
                "source_message_id": source_message_id,
                "confidence": confidence,
                "created_at": to_iso(now),
                "last_verified_at": to_iso(now),
            },
        )
        if current:
            self.conn.execute("UPDATE facts SET superseded_by = ? WHERE id = ?", (new_id, current["id"]))
        return new_id

    def active_facts(self) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM facts WHERE superseded_by IS NULL ORDER BY category, key"
        ).fetchall()

    # ------------------------------------------------------------------- food
    def add_food_event(self, now: datetime, occurred_at: datetime, **values: Any) -> int:
        payload = {
            "occurred_at": to_iso(occurred_at),
            "local_date": self._date(occurred_at),
            "created_at": to_iso(now),
            **values,
        }
        return self._insert("food_events", payload)

    def food_for_date(self, day: str) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM food_events WHERE local_date = ? ORDER BY occurred_at", (day,)
        ).fetchall()

    def food_logged_days(self, end_day: str, days: int) -> int:
        row = self.conn.execute(
            "SELECT COUNT(DISTINCT local_date) AS n FROM food_events WHERE local_date > ? AND local_date <= ?",
            (days_ago(end_day, days), end_day),
        ).fetchone()
        return int(row["n"])

    # --------------------------------------------------------------- activity
    def add_activity(self, now: datetime, occurred_at: datetime, **values: Any) -> int:
        payload = {
            "occurred_at": to_iso(occurred_at),
            "local_date": self._date(occurred_at),
            "created_at": to_iso(now),
            **values,
        }
        return self._insert("activity_events", payload)

    def activities_for_date(self, day: str) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM activity_events WHERE local_date = ? ORDER BY occurred_at", (day,)
        ).fetchall()

    # ----------------------------------------------------------- body metrics
    def add_body_metric(
        self,
        now: datetime,
        measured_at: datetime,
        metric: str,
        value: float,
        source: str,
        natural_key: str | None = None,
    ) -> int | None:
        try:
            return self._insert(
                "body_metrics",
                {
                    "measured_at": to_iso(measured_at),
                    "local_date": self._date(measured_at),
                    "metric": metric,
                    "value": value,
                    "source": source,
                    "natural_key": natural_key,
                    "created_at": to_iso(now),
                },
            )
        except sqlite3.IntegrityError:
            return None  # duplicate natural key: already stored

    def metric_series(self, metric: str, since_day: str) -> list[tuple[str, float]]:
        """One value per local day (the earliest measurement that day, to reduce intra-day noise)."""
        rows = self.conn.execute(
            "SELECT local_date, value FROM body_metrics WHERE metric = ? AND local_date >= ? "
            "ORDER BY local_date, measured_at",
            (metric, since_day),
        ).fetchall()
        series: dict[str, float] = {}
        for row in rows:
            series.setdefault(row["local_date"], row["value"])
        return sorted(series.items())

    # ---------------------------------------------------------------- context
    def add_context_event(
        self, now: datetime, tag: str, timing: str, description: str | None, source_message_id: int | None,
        for_tomorrow: bool = False,
    ) -> int:
        from datetime import timedelta

        when = now + timedelta(days=1) if for_tomorrow else now
        return self._insert(
            "context_events",
            {
                "occurred_at": to_iso(when) if for_tomorrow else to_iso(now),
                "local_date": self._date(when),
                "tag": normalize_tag(tag),
                "timing": timing,
                "description": description,
                "source_message_id": source_message_id,
            },
        )

    def known_context_tags(self, limit: int = 40) -> list[str]:
        rows = self.conn.execute(
            "SELECT tag FROM (SELECT tag, MAX(occurred_at) AS last FROM context_events GROUP BY tag "
            "UNION SELECT trigger_tag AS tag, updated_at AS last FROM commitments WHERE trigger_tag IS NOT NULL) "
            "GROUP BY tag ORDER BY MAX(last) DESC LIMIT ?",
            (limit,),
        ).fetchall()
        return [r["tag"] for r in rows]

    def context_tag_counts(self, since_day: str) -> dict[str, int]:
        rows = self.conn.execute(
            "SELECT tag, COUNT(*) AS n FROM context_events WHERE local_date >= ? GROUP BY tag", (since_day,)
        ).fetchall()
        return {r["tag"]: int(r["n"]) for r in rows}

    # -------------------------------------------------------- health records
    def upsert_health_record(
        self,
        now: datetime,
        record_type: str,
        natural_key: str,
        start: datetime | None,
        end: datetime | None,
        anchor: datetime,
        value: float | None,
        payload: dict,
    ) -> bool:
        """Insert or replace by natural key. Returns True if the row is new or changed."""
        existing = self.conn.execute(
            "SELECT value, payload_json FROM health_records WHERE natural_key = ?", (natural_key,)
        ).fetchone()
        payload_json = json.dumps(payload, sort_keys=True)
        if existing and existing["payload_json"] == payload_json:
            return False
        self.conn.execute(
            "INSERT INTO health_records(record_type, natural_key, start_time, end_time, local_date, value, "
            "payload_json, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(natural_key) DO UPDATE SET value=excluded.value, payload_json=excluded.payload_json, "
            "received_at=excluded.received_at",
            (
                record_type,
                natural_key,
                to_iso(start) if start else None,
                to_iso(end) if end else None,
                self._date(anchor),
                value,
                payload_json,
                to_iso(now),
            ),
        )
        return True

    def health_daily_sum(self, record_type: str, day: str) -> float | None:
        row = self.conn.execute(
            "SELECT SUM(value) AS total, COUNT(*) AS n FROM health_records WHERE record_type = ? AND local_date = ?",
            (record_type, day),
        ).fetchone()
        return float(row["total"]) if row["n"] else None

    def last_health_sync(self) -> str | None:
        row = self.conn.execute("SELECT MAX(received_at) AS last FROM health_records").fetchone()
        return row["last"]

    # ------------------------------------------------------------ commitments
    def add_commitment(self, now: datetime, **values: Any) -> int:
        values = dict(values)
        versions = values.pop("versions", None) or [values["action"]]
        if values.get("trigger_tag"):
            values["trigger_tag"] = normalize_tag(values["trigger_tag"])
        if values.get("window_start") and not values.get("schedule_days"):
            values["schedule_days"] = "daily"  # a time window without days means every day
        return self._insert(
            "commitments",
            {
                "versions_json": json.dumps(versions),
                "status": "active",
                "created_at": to_iso(now),
                "updated_at": to_iso(now),
                **values,
            },
        )

    def get_commitment(self, commitment_id: int) -> Commitment | None:
        row = self.conn.execute("SELECT * FROM commitments WHERE id = ?", (commitment_id,)).fetchone()
        return Commitment.from_row(row) if row else None

    def active_commitments(self) -> list[Commitment]:
        rows = self.conn.execute("SELECT * FROM commitments WHERE status = 'active' ORDER BY id").fetchall()
        return [Commitment.from_row(r) for r in rows]

    def commitments_for_tags(self, tags: Iterable[str]) -> list[Commitment]:
        normalized = sorted({normalize_tag(t) for t in tags if t})
        if not normalized:
            return []
        marks = ",".join("?" for _ in normalized)
        rows = self.conn.execute(
            f"SELECT * FROM commitments WHERE status = 'active' AND trigger_tag IN ({marks})", normalized
        ).fetchall()
        return [Commitment.from_row(r) for r in rows]

    def set_commitment_status(self, now: datetime, commitment_id: int, status: str) -> None:
        self.conn.execute(
            "UPDATE commitments SET status = ?, updated_at = ? WHERE id = ?", (status, to_iso(now), commitment_id)
        )

    def log_commitment(
        self,
        now: datetime,
        commitment_id: int,
        outcome: str,
        source: str,
        version: str | None = None,
        reason_category: str | None = None,
        local_date: str | None = None,
    ) -> int:
        """`local_date` overrides the day the outcome belongs to (e.g. a late button press for yesterday's nudge)."""
        return self._insert(
            "commitment_log",
            {
                "commitment_id": commitment_id,
                "local_date": local_date or self._date(now),
                "outcome": outcome,
                "version": version,
                "reason_category": reason_category,
                "source": source,
                "created_at": to_iso(now),
            },
        )

    def set_latest_skip_reason(self, commitment_id: int, day: str, reason: str) -> bool:
        cur = self.conn.execute(
            "UPDATE commitment_log SET reason_category = ? WHERE id = (SELECT id FROM commitment_log "
            "WHERE commitment_id = ? AND local_date = ? AND outcome = 'skipped' ORDER BY id DESC LIMIT 1)",
            (reason, commitment_id, day),
        )
        return cur.rowcount > 0

    def day_skip_reason(self, commitment_id: int, day: str) -> str | None:
        row = self.conn.execute(
            "SELECT reason_category FROM commitment_log WHERE commitment_id = ? AND local_date = ? "
            "AND outcome = 'skipped' AND reason_category IS NOT NULL ORDER BY id DESC LIMIT 1",
            (commitment_id, day),
        ).fetchone()
        return row["reason_category"] if row else None

    def commitment_outcome_on(self, commitment_id: int, day: str) -> str | None:
        """Final outcome for the day: done/smaller beat skipped/postponed."""
        rows = self.conn.execute(
            "SELECT outcome FROM commitment_log WHERE commitment_id = ? AND local_date = ?", (commitment_id, day)
        ).fetchall()
        outcomes = {r["outcome"] for r in rows}
        for candidate in ("done", "smaller", "skipped", "postponed"):
            if candidate in outcomes:
                return candidate
        return None

    def commitment_history(self, commitment_id: int, since_day: str) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM commitment_log WHERE commitment_id = ? AND local_date >= ? ORDER BY created_at",
            (commitment_id, since_day),
        ).fetchall()

    # ---------------------------------------------------------- interventions
    def add_intervention(self, now: datetime, **values: Any) -> int:
        return self._insert(
            "interventions",
            {"sent_at": to_iso(now), "local_date": self._date(now), "status": "sent", **values},
        )

    def get_intervention(self, intervention_id: int) -> sqlite3.Row | None:
        return self.conn.execute("SELECT * FROM interventions WHERE id = ?", (intervention_id,)).fetchone()

    def update_intervention(
        self, now: datetime, intervention_id: int, status: str, reason_category: str | None = None
    ) -> None:
        self.conn.execute(
            "UPDATE interventions SET status = ?, responded_at = ?, reason_category = COALESCE(?, reason_category) "
            "WHERE id = ?",
            (status, to_iso(now), reason_category, intervention_id),
        )

    def open_interventions(self) -> list[sqlite3.Row]:
        return self.conn.execute("SELECT * FROM interventions WHERE status = 'sent' ORDER BY sent_at").fetchall()

    def interventions_on(self, day: str) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM interventions WHERE local_date = ? ORDER BY sent_at", (day,)
        ).fetchall()

    def intervention_history(self, commitment_id: int, since_day: str) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM interventions WHERE commitment_id = ? AND local_date >= ? ORDER BY sent_at",
            (commitment_id, since_day),
        ).fetchall()

    def last_proactive_sent_at(self) -> str | None:
        row = self.conn.execute(
            "SELECT MAX(sent_at) AS last FROM interventions WHERE kind != 'contextual'"
        ).fetchone()
        return row["last"]

    # ------------------------------------------------------------- slot stats
    def get_slot_stats(self, commitment_id: int) -> dict[str, tuple[float, float]]:
        rows = self.conn.execute(
            "SELECT slot, alpha, beta FROM slot_stats WHERE commitment_id = ?", (commitment_id,)
        ).fetchall()
        return {r["slot"]: (r["alpha"], r["beta"]) for r in rows}

    def set_slot_stats(self, now: datetime, commitment_id: int, slot: str, alpha: float, beta: float) -> None:
        self.conn.execute(
            "INSERT INTO slot_stats(commitment_id, slot, alpha, beta, updated_at) VALUES (?, ?, ?, ?, ?) "
            "ON CONFLICT(commitment_id, slot) DO UPDATE SET alpha=excluded.alpha, beta=excluded.beta, "
            "updated_at=excluded.updated_at",
            (commitment_id, slot, alpha, beta, to_iso(now)),
        )

    # ------------------------------------------------------------ v2: food edits
    def delete_food_event(self, event_id: int) -> None:
        self.conn.execute("DELETE FROM food_events WHERE id = ?", (event_id,))

    def update_food_event(self, event_id: int, **values: Any) -> None:
        cols = ", ".join(f"{k} = ?" for k in values)
        self.conn.execute(f"UPDATE food_events SET {cols} WHERE id = ?", (*values.values(), event_id))

    def food_between(self, start_day: str, end_day: str) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM food_events WHERE local_date >= ? AND local_date <= ? ORDER BY occurred_at",
            (start_day, end_day),
        ).fetchall()

    # ------------------------------------------------------ v2: commitment edits
    def update_commitment(self, now: datetime, commitment_id: int, **values: Any) -> None:
        if "versions" in values:
            values["versions_json"] = json.dumps(values.pop("versions"))
        if not values:
            return
        values["updated_at"] = to_iso(now)
        cols = ", ".join(f"{k} = ?" for k in values)
        self.conn.execute(f"UPDATE commitments SET {cols} WHERE id = ?", (*values.values(), commitment_id))

    def commitments_with_trigger(self, tag: str) -> list[Commitment]:
        rows = self.conn.execute(
            "SELECT * FROM commitments WHERE trigger_tag = ? AND status = 'active'", (normalize_tag(tag),)
        ).fetchall()
        return [Commitment.from_row(r) for r in rows]

    # --------------------------------------------------------- v2: inferred events
    def add_inferred_event(self, now: datetime, occurred_at: datetime, **values: Any) -> int | None:
        payload = values.pop("payload", {})
        try:
            return self._insert(
                "inferred_events",
                {
                    "occurred_at": to_iso(occurred_at),
                    "local_date": self._date(occurred_at),
                    "payload_json": json.dumps(payload, ensure_ascii=False),
                    "status": "pending",
                    "created_at": to_iso(now),
                    **values,
                },
            )
        except sqlite3.IntegrityError:
            return None  # same order/payment already recorded

    def pending_inferred(self, since_day: str | None = None) -> list[sqlite3.Row]:
        if since_day:
            return self.conn.execute(
                "SELECT * FROM inferred_events WHERE status = 'pending' AND local_date >= ? ORDER BY occurred_at",
                (since_day,),
            ).fetchall()
        return self.conn.execute(
            "SELECT * FROM inferred_events WHERE status = 'pending' ORDER BY occurred_at"
        ).fetchall()

    def get_inferred(self, inferred_id: int) -> sqlite3.Row | None:
        return self.conn.execute("SELECT * FROM inferred_events WHERE id = ?", (inferred_id,)).fetchone()

    def resolve_inferred(self, now: datetime, inferred_id: int, status: str) -> None:
        self.conn.execute(
            "UPDATE inferred_events SET status = ?, resolved_at = ? WHERE id = ?", (status, to_iso(now), inferred_id)
        )

    def expire_inferred(self, now: datetime, before_day: str) -> int:
        cur = self.conn.execute(
            "UPDATE inferred_events SET status = 'expired', resolved_at = ? WHERE status = 'pending' AND local_date < ?",
            (to_iso(now), before_day),
        )
        return cur.rowcount

    def set_payee_label(self, now: datetime, payee_key: str, is_food: bool, label: str | None = None) -> None:
        self.conn.execute(
            "INSERT INTO payee_labels(payee_key, label, is_food, updated_at) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(payee_key) DO UPDATE SET label = COALESCE(excluded.label, label), is_food = excluded.is_food, "
            "updated_at = excluded.updated_at",
            (payee_key, label, int(is_food), to_iso(now)),
        )

    def get_payee_label(self, payee_key: str) -> sqlite3.Row | None:
        return self.conn.execute("SELECT * FROM payee_labels WHERE payee_key = ?", (payee_key,)).fetchone()

    # --------------------------------------------------------------- v2: patterns
    def upsert_pattern(self, now: datetime, **values: Any) -> None:
        data = json.dumps(values.pop("data", {}), ensure_ascii=False, default=str)
        existing = self.conn.execute("SELECT * FROM patterns WHERE key = ?", (values["key"],)).fetchone()
        if existing and existing["status"] == "rejected":
            return  # the user said this pattern is wrong; never resurrect it automatically
        if existing:
            self.conn.execute(
                "UPDATE patterns SET claim = ?, support = ?, contradict = ?, distinct_weeks = ?, confidence = ?, "
                "status = ?, data_json = ?, last_confirmed = CASE WHEN ? > support THEN ? ELSE last_confirmed END, "
                "updated_at = ? WHERE key = ?",
                (
                    values["claim"], values["support"], values["contradict"], values["distinct_weeks"],
                    values["confidence"], values["status"], data, values["support"], to_iso(now), to_iso(now),
                    values["key"],
                ),
            )
        else:
            self._insert(
                "patterns",
                {**values, "data_json": data, "first_seen": to_iso(now), "last_confirmed": to_iso(now), "updated_at": to_iso(now)},
            )

    def patterns(self, status: str | None = "active", kind: str | None = None) -> list[sqlite3.Row]:
        sql, args = "SELECT * FROM patterns WHERE 1=1", []
        if status:
            sql += " AND status = ?"
            args.append(status)
        if kind:
            sql += " AND kind = ?"
            args.append(kind)
        return self.conn.execute(sql + " ORDER BY confidence DESC", args).fetchall()

    def set_pattern_status(self, now: datetime, pattern_id: int, status: str) -> None:
        self.conn.execute("UPDATE patterns SET status = ?, updated_at = ? WHERE id = ?", (status, to_iso(now), pattern_id))

    # --------------------------------------------------------------- v2: calendar
    def set_calendar_day(self, now: datetime, day: str, meeting_hours: float, first_start, last_end, free_slots) -> None:
        self.conn.execute(
            "INSERT INTO calendar_days(local_date, meeting_hours, first_start, last_end, free_slots_json, updated_at) "
            "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(local_date) DO UPDATE SET meeting_hours = excluded.meeting_hours, "
            "first_start = excluded.first_start, last_end = excluded.last_end, free_slots_json = excluded.free_slots_json, "
            "updated_at = excluded.updated_at",
            (day, meeting_hours, first_start, last_end, json.dumps(free_slots), to_iso(now)),
        )

    def calendar_day(self, day: str) -> dict | None:
        row = self.conn.execute("SELECT * FROM calendar_days WHERE local_date = ?", (day,)).fetchone()
        if not row:
            return None
        slots = json.loads(row["free_slots_json"])
        free, busy = (slots.get("free", []), slots.get("busy", [])) if isinstance(slots, dict) else (slots, [])
        return {
            "meeting_hours": row["meeting_hours"],
            "first_start": row["first_start"],
            "last_end": row["last_end"],
            "free_slots": free,
            "busy": busy,
        }

    # --------------------------------------------------------------- v2: jobs
    def job_done(self, job: str, run_key: str) -> bool:
        return self.conn.execute("SELECT 1 FROM job_runs WHERE job = ? AND run_key = ?", (job, run_key)).fetchone() is not None

    def mark_job(self, now: datetime, job: str, run_key: str) -> None:
        self.conn.execute(
            "INSERT OR IGNORE INTO job_runs(job, run_key, ran_at) VALUES (?, ?, ?)", (job, run_key, to_iso(now))
        )

    def context_events_since(self, since_day: str) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM context_events WHERE local_date >= ? ORDER BY occurred_at", (since_day,)
        ).fetchall()

    def interventions_since(self, since_day: str) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM interventions WHERE local_date >= ? ORDER BY sent_at", (since_day,)
        ).fetchall()

    def last_inbound_at(self) -> str | None:
        row = self.conn.execute("SELECT MAX(created_at) AS last FROM messages WHERE direction = 'in'").fetchone()
        return row["last"]

    def last_button_at(self) -> str | None:
        """Most recent one-tap answer (buttons count as contact, not just typed messages)."""
        a = self.conn.execute(
            "SELECT MAX(responded_at) AS t FROM interventions WHERE status IN ('acted','smaller','skipped','snoozed')"
        ).fetchone()["t"]
        b = self.conn.execute(
            "SELECT MAX(resolved_at) AS t FROM inferred_events WHERE status IN ('confirmed','rejected') AND resolved_at IS NOT NULL"
        ).fetchone()["t"]
        return max([x for x in (a, b) if x], default=None)

    def inbound_times_since(self, since_iso: str) -> list[str]:
        rows = self.conn.execute(
            "SELECT created_at FROM messages WHERE direction = 'in' AND created_at >= ? ORDER BY created_at", (since_iso,)
        ).fetchall()
        return [r["created_at"] for r in rows]

    def mark_open_interventions_answered(self, now: datetime) -> int:
        """Any inbound message answers open non-commitment prompts (recap, check-in, ...)."""
        cur = self.conn.execute(
            "UPDATE interventions SET status = 'answered', responded_at = ? WHERE status = 'sent' "
            "AND kind NOT IN ('contextual') AND (commitment_id IS NULL OR kind = 'proactive')",
            (to_iso(now),),
        )
        return cur.rowcount

    def health_records_between(self, record_type: str, start_day: str, end_day: str) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM health_records WHERE record_type = ? AND local_date >= ? AND local_date <= ? ORDER BY start_time",
            (record_type, start_day, end_day),
        ).fetchall()

    # -------------------------------------------------------------- decisions
    def log_decision(self, now: datetime, kind: str, summary: str, data: dict | None = None) -> int:
        return self._insert(
            "coach_decisions",
            {
                "created_at": to_iso(now),
                "kind": kind,
                "summary": summary,
                "data_json": json.dumps(data, default=str) if data else None,
            },
        )

    def recent_decisions(self, limit: int = 20) -> list[sqlite3.Row]:
        return self.conn.execute("SELECT * FROM coach_decisions ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
