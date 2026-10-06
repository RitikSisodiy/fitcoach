"""Deterministic intervention policy: gating, escalation ladder, and slot learning.

See BEHAVIOR.md for the rationale. The LLM never decides whether a proactive
message is allowed; it only writes the words once this module says yes.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

from ..memory.store import Commitment, Store
from ..timeutil import WEEKDAYS, days_ago, in_window, local_time, parse_hhmm, parse_iso, weekday_key

MODE_LEVEL_CAP = {"gentle": 1, "normal": 3, "accountability": 4, "strong": 5}
MODE_BUDGET_CAP = {"gentle": 1, "normal": 3, "accountability": 4, "strong": 4}

LEVEL_NAMES = {
    0: "silent",
    1: "gentle_reminder",
    2: "contextual",
    3: "accountability",
    4: "strong_recommendation",
    5: "commitment_enforcement",
}

SUCCESS_STATUSES = {"acted", "smaller"}
FAILURE_STATUSES = {"ignored", "skipped"}


# --------------------------------------------------------------------- gating
@dataclass
class GateResult:
    allowed: bool
    reason: str


def effective_budget(profile: dict) -> int:
    mode = profile.get("coaching_mode", "normal")
    return max(0, min(int(profile.get("daily_message_budget", 3)), MODE_BUDGET_CAP.get(mode, 3)))


def gate_proactive(store: Store, profile: dict, now: datetime, min_gap_minutes: int) -> GateResult:
    paused_until = profile.get("paused_until")
    if paused_until and parse_iso(paused_until) > now:
        return GateResult(False, "coach paused by user")
    t = local_time(now, store.tz)
    if in_window(t, parse_hhmm(profile["quiet_start"]), parse_hhmm(profile["quiet_end"])):
        return GateResult(False, "quiet hours")
    today = store._date(now)
    sent_today = [i for i in store.interventions_on(today) if i["kind"] != "contextual"]
    budget = effective_budget(profile)
    if len(sent_today) >= budget:
        return GateResult(False, f"daily budget used ({len(sent_today)}/{budget})")
    last = store.last_proactive_sent_at()
    if last and now - parse_iso(last) < timedelta(minutes=min_gap_minutes):
        return GateResult(False, "too soon after previous proactive message")
    return GateResult(True, "ok")


def is_scheduled_on(commitment: Commitment, day: date) -> bool:
    spec = (commitment.schedule_days or "").strip().lower()
    if not spec or not commitment.window_start:
        return False
    key = weekday_key(day)
    if spec in {"daily", "everyday", "every day"}:
        return True
    if spec == "weekdays":
        return key in WEEKDAYS[:5]
    if spec == "weekends":
        return key in WEEKDAYS[5:]
    return key in {p.strip()[:3] for p in spec.split(",")}


# --------------------------------------------------------------------- ladder
@dataclass
class LadderDecision:
    level: int
    version_index: int
    version: str
    back_off: bool
    pattern: dict = field(default_factory=dict)
    reason: str = ""


def analyse_commitment(store: Store, commitment: Commitment, today: str, lookback_days: int = 14) -> dict:
    """Summarise recent behaviour for one commitment (deterministic)."""
    today_d = date.fromisoformat(today)
    # Only nudges with buttons can be acted on or ignored; in-reply reminders ("contextual") are excluded.
    history = [
        row
        for row in store.intervention_history(commitment.id, days_ago(today, lookback_days))
        if row["kind"] in {"scheduled", "follow_up"}
    ]
    nudged_days = {row["local_date"] for row in history if row["kind"] == "scheduled"}
    created_day = commitment.created_at[:10] if commitment.created_at else ""
    days: list[dict] = []
    for offset in range(lookback_days, 0, -1):
        d = today_d - timedelta(days=offset)
        iso = d.isoformat()
        # created_at is UTC; comparing date prefixes may include the creation day itself, which is harmless.
        if iso < created_day or not is_scheduled_on(commitment, d):
            continue
        outcome = store.commitment_outcome_on(commitment.id, iso)
        if outcome == "skipped" and store.day_skip_reason(commitment.id, iso) == "cannot":
            outcome = "excused"  # genuinely unable: neutral, never escalates
        if outcome is None:
            # No report. It is only a miss if we actually asked; otherwise we simply don't know
            # (the user may have done it without telling us, or we never nudged due to budget).
            outcome = "missed" if iso in nudged_days else "no_data"
        days.append({"date": iso, "outcome": outcome})

    consecutive_misses = 0
    for entry in reversed(days):
        if entry["outcome"] in {"no_data", "excused"}:
            continue
        if entry["outcome"] in {"done", "smaller"}:
            break
        consecutive_misses += 1

    week_ago = days_ago(today, 7)
    recent = [d for d in days if d["date"] > week_ago]
    misses_7d = sum(1 for d in recent if d["outcome"] in {"missed", "skipped", "postponed"})
    done_7d = sum(1 for d in recent if d["outcome"] in {"done", "smaller"})
    no_data_7d = sum(1 for d in recent if d["outcome"] == "no_data")

    ignored_streak = 0
    for row in reversed(history):
        if row["status"] == "sent":
            continue
        if row["status"] != "ignored":
            break
        ignored_streak += 1
    last_accountability = max(
        (row["local_date"] for row in history if row["level"] >= 3), default=None
    )
    reasons = [
        r["reason_category"]
        for r in store.commitment_history(commitment.id, days_ago(today, lookback_days))
        if r["reason_category"]
    ]
    dominant_reason = max(set(reasons), key=reasons.count) if reasons else None

    slot_outcomes: dict[str, list[int]] = {}
    for row in history:
        if row["slot"] and row["status"] in SUCCESS_STATUSES | FAILURE_STATUSES:
            slot_outcomes.setdefault(row["slot"], []).append(1 if row["status"] in SUCCESS_STATUSES else 0)

    return {
        "scheduled_days_considered": len(days),
        "consecutive_misses": consecutive_misses,
        "misses_7d": misses_7d,
        "done_7d": done_7d,
        "no_data_7d": no_data_7d,
        "ignored_streak": ignored_streak,
        "last_accountability_date": last_accountability,
        "dominant_skip_reason": dominant_reason,
        "slot_success": {s: f"{sum(v)}/{len(v)}" for s, v in slot_outcomes.items()},
    }


def decide_level(
    commitment: Commitment, pattern: dict, profile: dict, today: str
) -> LadderDecision:
    mode = profile.get("coaching_mode", "normal")
    cap = MODE_LEVEL_CAP.get(mode, 3)
    misses = pattern["consecutive_misses"]
    ignored = pattern["ignored_streak"]
    reasons: list[str] = []

    level = 1
    if misses >= 1:
        reasons.append(f"{misses} consecutive missed day(s): never miss twice")
        level = 2
    if pattern["misses_7d"] >= 3:
        last_acc = pattern["last_accountability_date"]
        if not last_acc or last_acc <= days_ago(today, 5):
            level = 3
            reasons.append(f"{pattern['misses_7d']} misses in 7 days: name the pattern, change strategy")
    if misses >= 2 and level < 4:
        level = 4
        reasons.append("2+ misses in a row: strongly recommend the minimum version")
    strong_ok = (
        commitment.enforcement == "strong" and mode == "strong" and bool(profile.get("strong_mode_authorized"))
    )
    if strong_ok and misses >= 1:
        level = 5
        reasons.append("user-authorised strong enforcement")
    if level > cap:
        reasons.append(f"capped at {cap} by mode '{mode}'")
        level = cap

    version_index = misses + (1 if ignored >= 2 else 0)
    version_index = max(0, min(version_index, len(commitment.versions) - 1))

    back_off = ignored >= 4
    if back_off:
        reasons.append(f"{ignored} nudges ignored in a row: back off and review strategy instead of repeating")

    return LadderDecision(
        level=level,
        version_index=version_index,
        version=commitment.versions[version_index],
        back_off=back_off,
        pattern=pattern,
        reason="; ".join(reasons) or "routine reminder",
    )


# ------------------------------------------------------------- slot learning
PRIOR = (1.0, 1.0)
DECAY = 0.9  # forgetting factor: old evidence fades (habituation is real)
EXPLORATION = 0.15


class SlotLearner:
    """Beta-Bernoulli Thompson sampling over 30-minute slots, per commitment."""

    def __init__(self, store: Store, rng: random.Random | None = None):
        self.store = store
        self.rng = rng or random.Random()

    def should_send_now(self, commitment_id: int, current_slot: str, remaining_slots: list[str]) -> tuple[bool, str]:
        if current_slot not in remaining_slots:
            return False, "current slot outside window"
        if len(remaining_slots) == 1:
            return True, "last slot in window"
        stats = self.store.get_slot_stats(commitment_id)
        samples = {s: self.rng.betavariate(*stats.get(s, PRIOR)) for s in remaining_slots}
        best = max(samples, key=samples.get)
        if best == current_slot:
            return True, f"slot {current_slot} has best sampled response rate"
        if self.rng.random() < EXPLORATION / len(remaining_slots):
            return True, f"exploring slot {current_slot}"
        return False, f"waiting for better slot ({best})"

    def update(self, now: datetime, commitment_id: int, slot: str, success: float) -> None:
        stats = self.store.get_slot_stats(commitment_id)
        a, b = stats.get(slot, PRIOR)
        a = PRIOR[0] + (a - PRIOR[0]) * DECAY + success
        b = PRIOR[1] + (b - PRIOR[1]) * DECAY + (1 - success)
        self.store.set_slot_stats(now, commitment_id, slot, a, b)
