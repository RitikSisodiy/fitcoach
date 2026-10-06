"""Engagement model: how responsive is the user, and how much should we say?

Evidence (RESEARCH.md §8): notifications buy momentary attention but not retention;
habituation is the enemy; the first 1-3 silent weeks are the window to re-engage;
reminders are the most-resented persuasive feature. So when the user goes quiet we
send *less*, make it *easier*, and switch to one-tap re-entry - never guilt.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta

from ..memory.store import Store
from ..timeutil import days_ago, parse_iso, to_iso

PROACTIVE_KINDS = {"scheduled", "follow_up", "proactive", "review"}
RESPONDED = {"acted", "smaller", "skipped", "snoozed", "answered"}
RESPONSE_WINDOW_HOURS = 3
RETIRE_AFTER_SENDS = 6
RETIRE_DAYS = 7
NEVER_RETIRE = {"reengage", "weekly_review", "recap"}  # recap is the core tracking tool: it gets lighter/rarer instead


@dataclass
class Engagement:
    state: str  # engaged | drifting | silent | dormant
    days_since_inbound: float | None
    recent_response_rate: float | None
    recent_sent: int
    daily_budget: int
    min_days_between: int  # 0 = can message every day
    allowed_intents: set[str] | None  # None = all
    intent_stats: dict[str, dict] = field(default_factory=dict)
    retired_intents: set[str] = field(default_factory=set)
    best_hours: list[int] = field(default_factory=list)
    recap_every_days: int = 1

    def as_context(self) -> dict:
        return {
            "state": self.state,
            "days_since_user_message": None if self.days_since_inbound is None else round(self.days_since_inbound, 1),
            "recent_response_rate": self.recent_response_rate,
            "daily_budget": self.daily_budget,
            "retired_message_types": sorted(self.retired_intents),
            "intent_response_rates": {
                k: f"{v['responded']}/{v['sent']}" for k, v in self.intent_stats.items() if v["sent"]
            },
            "hours_user_usually_replies": self.best_hours,
            "recap_every_days": self.recap_every_days,
        }


def intent_family(intent: str | None) -> str:
    return (intent or "unknown").split(":", 1)[0]


def _responded(row, inbound: list[datetime]) -> bool:
    if row["status"] in RESPONDED:
        return True
    sent = parse_iso(row["sent_at"])
    return any(sent <= t <= sent + timedelta(hours=RESPONSE_WINDOW_HOURS) for t in inbound)


def compute_engagement(store: Store, now: datetime, mode_budget: int) -> Engagement:
    today = store._date(now)
    rows = [r for r in store.interventions_since(days_ago(today, 21)) if r["kind"] in PROACTIVE_KINDS]
    inbound = [parse_iso(t) for t in store.inbound_times_since(to_iso(now - timedelta(days=22)))]
    last_in = max([x for x in (store.last_inbound_at(), store.last_button_at()) if x], default=None)
    days_since = (now - parse_iso(last_in)).total_seconds() / 86400 if last_in else None

    # Only judge prompts whose response window has passed.
    judged = [r for r in rows if parse_iso(r["sent_at"]) < now - timedelta(hours=RESPONSE_WINDOW_HOURS) or r["status"] != "sent"]
    last8 = judged[-8:]
    rate = round(sum(_responded(r, inbound) for r in last8) / len(last8), 2) if last8 else None

    stats: dict[str, dict] = {}
    for r in judged:
        fam = intent_family(r["intent"] or r["kind"])
        s = stats.setdefault(fam, {"sent": 0, "responded": 0, "last_sent": None, "last_responded": None})
        s["sent"] += 1
        s["last_sent"] = r["sent_at"]
        if _responded(r, inbound):
            s["responded"] += 1
            s["last_responded"] = r["sent_at"]
    retired = set()
    for fam, s in stats.items():
        if fam in NEVER_RETIRE:
            continue
        recent = [r for r in judged if intent_family(r["intent"] or r["kind"]) == fam][-RETIRE_AFTER_SENDS:]
        if len(recent) == RETIRE_AFTER_SENDS and not any(_responded(r, inbound) for r in recent):
            last = parse_iso(recent[-1]["sent_at"])
            if now - last < timedelta(days=RETIRE_DAYS):
                retired.add(fam)

    ignored_recaps = 0
    for r in reversed([r for r in judged if intent_family(r["intent"]) == "recap"]):
        if _responded(r, inbound):
            break
        ignored_recaps += 1
    recap_every = 1 if ignored_recaps < 4 else 2 if ignored_recaps < 8 else 3

    hours: dict[int, int] = {}
    from zoneinfo import ZoneInfo

    for t in inbound:
        h = t.astimezone(ZoneInfo(store.tz)).hour
        hours[h] = hours.get(h, 0) + 1
    best_hours = sorted(hours, key=hours.get, reverse=True)[:3]

    # ---- state machine
    if days_since is None:
        state = "engaged"  # brand new user: onboarding phase
    elif days_since <= 1.5 or (rate is not None and rate >= 0.4):
        state = "engaged"
    elif days_since <= 4:
        state = "drifting"
    elif days_since <= 10:
        state = "silent"
    else:
        state = "dormant"

    if state == "engaged":
        budget, gap, allowed = mode_budget, 0, None
    elif state == "drifting":
        budget, gap, allowed = min(mode_budget, 2), 0, None
    elif state == "silent":
        budget, gap = min(mode_budget, 1), 0
        allowed = {"recap", "reengage", "commitment", "predicted_context"}
    else:
        budget = min(mode_budget, 1)
        gap = 3 if days_since <= 21 else 7
        allowed = {"reengage"}
    return Engagement(
        state=state,
        days_since_inbound=days_since,
        recent_response_rate=rate,
        recent_sent=len(last8),
        daily_budget=budget,
        min_days_between=gap,
        allowed_intents=allowed,
        intent_stats=stats,
        retired_intents=retired,
        best_hours=best_hours,
        recap_every_days=recap_every,
    )
