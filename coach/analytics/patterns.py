"""Deterministic pattern mining with evidence counts.

Patterns are hypotheses about the user's behaviour, mined from logs:
- weekday_context: a situation recurs on a weekday ("chess on Tuesdays ~18:00")
- context_lapse:   a situation is usually followed by an off-plan snack/lapse
- meal_gap:        a meal slot is rarely logged

Rules (see BEHAVIOR.md §9 and RESEARCH.md §8):
- Counts are computed here, never by the LLM. The LLM may only phrase a claim.
- A pattern is `active` (usable for proactive behaviour) only with enough evidence across
  distinct weeks; otherwise it stays a `candidate`.
- Missing logs are "unknown", not evidence of absence.
- A pattern the user rejected is never re-activated automatically.
"""

from __future__ import annotations

import math
import statistics
from collections import defaultdict
from datetime import date, datetime

from ..memory.store import Store
from ..nutrition.estimator import FoodTable  # noqa: F401 (kept for API compatibility)
from ..timeutil import days_ago, local_time, parse_iso, WEEKDAYS

LOOKBACK_DAYS = 56
# Explicitly off-plan foods (not the whole "snack" category: makhana, chana, peanuts are fine).
OFFPLAN_KEYS = {
    "samosa", "kachori", "pakora", "vada_pav", "pav_bhaji", "sev", "namkeen", "instant_noodles", "gulab_jamun",
    "jalebi", "ladoo", "pizza", "burger", "french_fries", "soft_drink", "street_snack", "chowmein", "beer",
}
MIN_LIFT = 0.25  # situation-day lapse rate must exceed the other-day rate by this much
MIN_SUPPORT = 3
MIN_WEEKS = 2
MIN_WILSON = 0.35


def wilson_lower(successes: int, n: int, z: float = 1.64) -> float:
    if n == 0:
        return 0.0
    p = successes / n
    denom = 1 + z * z / n
    centre = p + z * z / (2 * n)
    margin = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return max(0.0, (centre - margin) / denom)


def _iso_week(day: str) -> tuple[int, int]:
    y, w, _ = date.fromisoformat(day).isocalendar()
    return (y, w)


def _minutes(hhmm: str) -> int:
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


def _hhmm(minutes: float) -> str:
    m = int(round(minutes)) % (24 * 60)
    return f"{m // 60:02d}:{m % 60:02d}"


def mine_patterns(store: Store, now: datetime, food_table: FoodTable | None = None) -> list[dict]:
    """Recompute all patterns and upsert them. Returns the computed pattern dicts."""
    today = store._date(now)
    since = days_ago(today, LOOKBACK_DAYS)
    contexts = [c for c in store.context_events_since(since) if c["timing"] != "past" and c["local_date"] < today]
    results: list[dict] = []

    # ---- weekday recurrence of situations
    tag_days: dict[str, dict[str, list]] = defaultdict(lambda: defaultdict(list))
    for c in contexts:
        tag_days[c["tag"]][c["local_date"]].append(c)
    for tag, days in tag_days.items():
        first = min(days)
        for wd in range(7):
            hits = sorted(d for d in days if date.fromisoformat(d).weekday() == wd)
            if not hits:
                continue
            # Opportunities: every such weekday from the first mention of the tag until yesterday.
            span_days = (date.fromisoformat(today) - date.fromisoformat(first)).days
            opportunities = sum(
                1 for k in range(span_days) if date.fromordinal(date.fromisoformat(first).toordinal() + k).weekday() == wd
            )
            opportunities = max(opportunities, len(hits))
            times = []
            for d in hits:
                for c in days[d]:
                    if c["time_hint"]:
                        times.append(_minutes(c["time_hint"]))
                    else:
                        # A mention ("ja raha hu") precedes the visit; assume ~30 min later.
                        t = local_time(parse_iso(c["occurred_at"]), store.tz)
                        times.append(t.hour * 60 + t.minute + 30)
            typical = _hhmm(statistics.median(times)) if times else None
            support, contradict = len(hits), opportunities - len(hits)
            conf = wilson_lower(support, opportunities)
            active = support >= MIN_SUPPORT and len({_iso_week(d) for d in hits}) >= MIN_SUPPORT and conf >= MIN_WILSON
            results.append(
                {
                    "kind": "weekday_context",
                    "key": f"weekday_context:{tag}:{wd}",
                    "claim": f"'{tag}' usually happens on {WEEKDAYS[wd]} around {typical} ({support} of {opportunities} {WEEKDAYS[wd]}s)",
                    "support": support,
                    "contradict": contradict,
                    "distinct_weeks": len({_iso_week(d) for d in hits}),
                    "confidence": round(conf, 2),
                    "status": "active" if active else "candidate",
                    "data": {"tag": tag, "weekday": wd, "typical_time": typical, "dates": hits[-6:]},
                }
            )

    # ---- situation -> off-plan eating / lapse on the same day
    lapse_times: dict[str, list[datetime]] = defaultdict(list)
    for d in store.recent_decisions(1000):
        if d["kind"] == "lapse":
            at = parse_iso(d["created_at"])
            lapse_times[store._date(at)].append(at)
    foods = store.food_between(since, days_ago(today, 1))
    offplan_by_day: dict[str, list] = defaultdict(list)
    for f in foods:
        if f["food_key"] in OFFPLAN_KEYS or (f["meal_slot"] == "snack" and f["nutrition_source"] == "llm_estimate"):
            offplan_by_day[f["local_date"]].append(f)
    interaction_days = {store._date(parse_iso(t)) for t in store.inbound_times_since(f"{since}T00:00:00")}
    for tag, days in tag_days.items():
        support_days, contra_days, items = [], [], []
        for d, events in days.items():
            first_mention = min(parse_iso(e["occurred_at"]) for e in events)
            later = [f for f in offplan_by_day.get(d, []) if parse_iso(f["occurred_at"]) >= first_mention.replace(minute=0)]
            lapse_after = any(t >= first_mention for t in lapse_times.get(d, []))
            if later or lapse_after:
                support_days.append(d)
                items += [f["item_name"] for f in later]
            else:
                contra_days.append(d)
        n = len(support_days) + len(contra_days)
        if n < 2:
            continue
        other_days = [d for d in interaction_days if d not in days and d < today]
        other_lapse = sum(1 for d in other_days if offplan_by_day.get(d) or lapse_times.get(d))
        base_rate = other_lapse / len(other_days) if other_days else 0.0
        weeks = len({_iso_week(d) for d in support_days})
        conf = wilson_lower(len(support_days), n)
        lift = len(support_days) / n - base_rate
        active = len(support_days) >= MIN_SUPPORT and weeks >= MIN_WEEKS and conf >= MIN_WILSON and lift >= MIN_LIFT
        common = sorted(set(items), key=items.count, reverse=True)[:3]
        results.append(
            {
                "kind": "context_lapse",
                "key": f"context_lapse:{tag}",
                "claim": f"On '{tag}' days, off-plan eating happened {len(support_days)} of {n} times"
                + (f" (usually {', '.join(common)})" if common else ""),
                "support": len(support_days),
                "contradict": len(contra_days),
                "distinct_weeks": weeks,
                "confidence": round(conf, 2),
                "status": "active" if active else "candidate",
                "data": {"tag": tag, "common_items": common, "support_days": sorted(support_days)[-6:],
                         "other_day_rate": round(base_rate, 2)},
            }
        )

    # ---- recurring small payments (passive): same payee on the same weekday = a habitual snack stop
    payments = store.conn.execute(
        "SELECT * FROM inferred_events WHERE kind = 'small_payment' AND status != 'rejected' AND local_date >= ? AND local_date < ?",
        (since, today),
    ).fetchall()
    by_payee: dict[str, dict[str, list]] = defaultdict(lambda: defaultdict(list))
    for pay in payments:
        if pay["payee_key"]:
            label = store.get_payee_label(pay["payee_key"])
            if label is not None and not label["is_food"]:
                continue
            by_payee[pay["payee_key"]][pay["local_date"]].append(pay)
    for payee, days in by_payee.items():
        first = min(days)
        label = store.get_payee_label(payee)
        name = (label["label"] if label and label["label"] else payee)[:40]
        tag = "snack_stop_" + "".join(ch if ch.isalnum() else "_" for ch in name.lower())[:30].strip("_")
        for wd in range(7):
            hits = sorted(dd for dd in days if date.fromisoformat(dd).weekday() == wd)
            if len(hits) < 2:
                continue
            span = (date.fromisoformat(today) - date.fromisoformat(first)).days
            opportunities = max(len(hits), sum(1 for k in range(span) if date.fromordinal(date.fromisoformat(first).toordinal() + k).weekday() == wd))
            times = [local_time(parse_iso(p["occurred_at"]), store.tz) for dd in hits for p in days[dd]]
            typical = _hhmm(statistics.median(t.hour * 60 + t.minute for t in times))
            conf = wilson_lower(len(hits), opportunities)
            weeks = len({_iso_week(dd) for dd in hits})
            active = len(hits) >= MIN_SUPPORT and weeks >= MIN_SUPPORT and conf >= MIN_WILSON
            results.append(
                {
                    "kind": "weekday_payee",
                    "key": f"weekday_payee:{payee}:{wd}",
                    "claim": f"Small payments to {name} on {WEEKDAYS[wd]}s around {typical} ({len(hits)} of {opportunities}) - likely a snack stop",
                    "support": len(hits),
                    "contradict": opportunities - len(hits),
                    "distinct_weeks": weeks,
                    "confidence": round(conf, 2),
                    "status": "active" if active else "candidate",
                    "data": {"tag": tag, "weekday": wd, "typical_time": typical, "payee": name},
                }
            )

    # ---- rarely logged meal slots (only meaningful if the user interacts at all)
    from .habits import MAIN_MEALS, day_coverage

    active_days = {store._date(parse_iso(t)) for t in store.inbound_times_since(f"{days_ago(today, 14)}T00:00:00")}
    if len(active_days) >= 5:
        for slot in MAIN_MEALS:
            logged = sum(1 for d in active_days if slot in day_coverage(store, d)["logged"])
            if logged / len(active_days) < 0.3:
                results.append(
                    {
                        "kind": "meal_gap",
                        "key": f"meal_gap:{slot}",
                        "claim": f"{slot} is rarely logged ({logged} of {len(active_days)} active days)",
                        "support": len(active_days) - logged,
                        "contradict": logged,
                        "distinct_weeks": len({_iso_week(d) for d in active_days}),
                        "confidence": round(1 - logged / len(active_days), 2),
                        "status": "active",
                        "data": {"slot": slot},
                    }
                )

    for p in results:
        store.upsert_pattern(now, **p)
    fresh = {p["key"] for p in results}
    for row in store.patterns("active"):
        if row["key"] not in fresh:
            store.set_pattern_status(now, row["id"], "candidate")  # no longer supported by recent data
    return results


def predicted_contexts(store: Store, day: str) -> list[dict]:
    """Active weekday patterns that predict a situation on `day`."""
    wd = date.fromisoformat(day).weekday()
    out = []
    import json

    for p in list(store.patterns("active", "weekday_context")) + list(store.patterns("active", "weekday_payee")):
        data = json.loads(p["data_json"])
        if data["weekday"] == wd and data.get("typical_time"):
            out.append({"pattern_id": p["id"], "tag": data["tag"], "typical_time": data["typical_time"],
                        "claim": p["claim"], "kind": p["kind"]})
    return out


def lapse_pattern_for(store: Store, tag: str) -> dict | None:
    import json

    for p in store.patterns("active", "context_lapse"):
        data = json.loads(p["data_json"])
        if data["tag"] == tag:
            return {"pattern_id": p["id"], "claim": p["claim"], "common_items": data.get("common_items", [])}
    return None
