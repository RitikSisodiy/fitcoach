"""Health Connect ingest.

Accepts the JSON payload posted by the open-source "Health Connect Webhook" app
(https://github.com/mcnaveen/health-connect-webhook, docs/webhook.md). That app
sends no record ids and re-sends overlapping windows, so every record is keyed on
(type, start, end) or (type, time) and upserted idempotently.

Only the types the coach uses are stored (data minimisation). Everything else is
counted and dropped.
"""

from __future__ import annotations

import hmac
from dataclasses import dataclass, field
from datetime import datetime

from ..memory.store import Store
from ..timeutil import parse_iso, to_iso


@dataclass
class IngestResult:
    stored: dict[str, int] = field(default_factory=dict)
    unchanged: int = 0
    ignored_types: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


def _interval(store: Store, now: datetime, result: IngestResult, rtype: str, rec: dict, value_field: str, scale=1.0):
    start, end = parse_iso(rec["start_time"]), parse_iso(rec["end_time"])
    value = float(rec[value_field]) * scale
    key = f"{rtype}|{to_iso(start)}|{to_iso(end)}"  # normalised so format differences don't duplicate
    return store.upsert_health_record(now, rtype, key, start, end, start, value, rec)


def ingest_payload(store: Store, payload: dict, now: datetime) -> IngestResult:
    result = IngestResult()
    if not isinstance(payload, dict):
        result.errors.append("payload must be a JSON object")
        return result

    handlers = {
        "steps": lambda rec: _interval(store, now, result, "steps", rec, "count"),
        "distance": lambda rec: _interval(store, now, result, "distance", rec, "meters"),
        "active_calories": lambda rec: _interval(store, now, result, "active_calories", rec, "calories"),
        "exercise": lambda rec: _interval(store, now, result, "exercise", rec, "duration_seconds", 1 / 60),
        "sleep": lambda rec: _sleep(store, now, rec),
        "weight": lambda rec: _weight(store, now, rec),
    }
    for key, records in payload.items():
        if key in {"timestamp", "app_version"}:
            continue
        handler = handlers.get(key)
        if handler is None:
            result.ignored_types.append(key)
            continue
        if not isinstance(records, list):
            result.errors.append(f"{key}: expected a list")
            continue
        for rec in records:
            try:
                changed = handler(rec)
            except (KeyError, TypeError, ValueError) as exc:
                result.errors.append(f"{key}: bad record ({exc.__class__.__name__}: {exc})")
                continue
            if changed:
                result.stored[key] = result.stored.get(key, 0) + 1
            else:
                result.unchanged += 1
    return result


def _sleep(store: Store, now: datetime, rec: dict) -> bool:
    end = parse_iso(rec["session_end_time"])
    hours = float(rec["duration_seconds"]) / 3600
    if not (0 < hours <= 24):
        raise ValueError(f"implausible sleep duration {hours:.1f} h")
    key = f"sleep|{to_iso(end)}"
    # Anchored to the wake-up date: "sleep last night" belongs to the day it ended.
    return store.upsert_health_record(now, "sleep", key, None, end, end, hours, rec)


def _weight(store: Store, now: datetime, rec: dict) -> bool:
    at = parse_iso(rec["time"])
    kg = float(rec["kilograms"])
    if not (30 <= kg <= 300):
        raise ValueError(f"implausible weight {kg}")
    key = f"weight|{to_iso(at)}"
    changed = store.upsert_health_record(now, "weight", key, None, None, at, kg, rec)
    store.add_body_metric(now, at, "weight_kg", round(kg, 2), "health_connect", natural_key=f"hc|{key}")
    return changed


def check_secret(expected: str, provided: str | None) -> bool:
    if not expected:
        return False  # ingest is disabled until a secret is configured
    return provided is not None and hmac.compare_digest(expected.encode(), provided.encode())
