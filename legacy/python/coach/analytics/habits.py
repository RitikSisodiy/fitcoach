"""Habitual meals and daily logging coverage.

Used for the evening recap ("Lunch: usual - 2 roti, dal, sabzi?") so the common
case costs one tap. Habitual defaults confirmed by the user are stored with
source `default_confirmed` and lower confidence (acquiescence bias is real).
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from datetime import date

from ..memory.store import Store
from ..timeutil import days_ago

MAIN_MEALS = ("breakfast", "lunch", "dinner")
MIN_OCCURRENCES = 3
LOOKBACK_DAYS = 28


@dataclass
class HabitualMeal:
    slot: str
    items: list[dict]  # [{name, quantity, unit}]
    occurrences: int
    days_observed: int

    def describe(self) -> str:
        parts = []
        for it in self.items:
            qty = it.get("quantity")
            qty_s = (f"{qty:g} " if isinstance(qty, (int, float)) else "") if qty else ""
            parts.append(f"{qty_s}{it['name']}".strip())
        return ", ".join(parts)


def _signature(rows) -> frozenset:
    return frozenset((r["food_key"] or r["item_name"].lower()) for r in rows)


def habitual_meals(store: Store, today: str, weekend: bool | None = None) -> dict[str, HabitualMeal]:
    """Most frequent meal signature per main slot over the lookback window (user-entered data only)."""
    rows = store.food_between(days_ago(today, LOOKBACK_DAYS), days_ago(today, 1))
    by_day_slot: dict[tuple[str, str], list] = {}
    for r in rows:
        if r["meal_slot"] not in MAIN_MEALS or r["source"] != "user_entered":  # defaults must not confirm themselves
            continue
        if weekend is not None and (date.fromisoformat(r["local_date"]).weekday() >= 5) != weekend:
            continue
        by_day_slot.setdefault((r["local_date"], r["meal_slot"]), []).append(r)

    result: dict[str, HabitualMeal] = {}
    declared = store.get_profile().get("usual_meals") or {}
    for slot in MAIN_MEALS:
        instances = [(d, items) for (d, s), items in by_day_slot.items() if s == slot]
        if not instances or Counter(_signature(items) for _, items in instances).most_common(1)[0][1] < MIN_OCCURRENCES:
            if declared.get(slot):  # what the user told us they usually eat, until observed habits exist
                result[slot] = HabitualMeal(slot=slot, items=declared[slot], occurrences=0, days_observed=0)
            continue
        counts = Counter(_signature(items) for _, items in instances)
        signature, n = counts.most_common(1)[0]
        if n < MIN_OCCURRENCES:
            continue
        latest = max((d, items) for d, items in instances if _signature(items) == signature)[1]
        result[slot] = HabitualMeal(
            slot=slot,
            items=[{"name": r["item_name"], "quantity": r["quantity"], "unit": r["unit"]} for r in latest],
            occurrences=n,
            days_observed=len(instances),
        )
    return result


def day_coverage(store: Store, day: str) -> dict:
    """Which main meals are logged today, and how (for honest totals and the recap)."""
    rows = store.food_for_date(day)
    slots: dict[str, str] = {}
    for r in rows:
        slot = r["meal_slot"] or "unknown"
        if slot in MAIN_MEALS:
            slots[slot] = r["source"]
    missing = [s for s in MAIN_MEALS if s not in slots]
    return {
        "logged": slots,
        "missing": missing,
        "items_logged": len(rows),
        "has_unslotted_items": any((r["meal_slot"] or "unknown") == "unknown" for r in rows),
    }


def coverage_stats(store: Store, today: str, days: int = 14) -> dict:
    """Fraction of main meals logged over recent days (any source), and the least-logged slot."""
    per_slot = {s: 0 for s in MAIN_MEALS}
    for offset in range(1, days + 1):
        logged = day_coverage(store, days_ago(today, offset))["logged"]
        for s in logged:
            per_slot[s] += 1
    total = sum(per_slot.values())
    return {
        "days": days,
        "meal_coverage_pct": round(100 * total / (3 * days)),
        "per_slot_days": per_slot,
        "least_logged_slot": min(per_slot, key=per_slot.get),
    }


def estimated_day_intake(store: Store, day: str, table=None) -> dict:
    """Best honest estimate of a day's intake for a user who doesn't log everything.

    = what was recorded (any source) + the user's usual meal for each main meal with no record.
    Assumed meals are NOT stored as food events; they are computed here and always reported
    separately, so the coach can say "~1,900-2,400 kcal (lunch and dinner assumed usual)".
    """
    from ..nutrition.estimator import FoodTable, estimate

    table = table or FoodTable.load()
    rows = store.food_for_date(day)
    low = sum(r["kcal_low"] or 0 for r in rows)
    high = sum(r["kcal_high"] or 0 for r in rows)
    unknown_items = sum(1 for r in rows if r["kcal_low"] is None)
    cov = day_coverage(store, day)
    habits = habitual_meals(store, day, weekend=date.fromisoformat(day).weekday() >= 5) or habitual_meals(store, day)
    assumed, a_low, a_high, unknown_slots = [], 0.0, 0.0, []
    for slot in cov["missing"]:
        meal = habits.get(slot)
        if not meal:
            unknown_slots.append(slot)
            continue
        for item in meal.items:
            est = estimate(table, item["name"], item.get("quantity"), item.get("unit"))
            if est.kcal_low is not None:
                a_low += est.kcal_low
                a_high += est.kcal_high
        assumed.append(slot)
    return {
        "recorded_kcal_range": [round(low), round(high)],
        "assumed_usual_slots": assumed,
        "assumed_kcal_range": [round(a_low), round(a_high)],
        "total_kcal_range": [round(low + a_low), round(high + a_high)] if not unknown_slots else None,
        "unknown_slots": unknown_slots,
        "unestimated_items": unknown_items,
        "label": "estimate: recorded + assumed usual meals" if assumed else "estimate: recorded only",
    }
