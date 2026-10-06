"""Progress engine: trend weight, averages, and data-quality signals.

Single measurements are never treated as signal. Decisions use the EWMA trend
and require a minimum amount of data before any target change is suggested.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

EWMA_ALPHA = 0.1  # Hacker's Diet style smoothing
MIN_DAYS_FOR_TARGET_CHANGE = 14
MIN_WEIGHINS_FOR_TREND = 5
SAFE_LOSS_RATE_MAX_PCT_PER_WEEK = 1.0


@dataclass
class WeightTrend:
    latest_raw: float | None = None
    latest_trend: float | None = None
    avg_7d: float | None = None
    weekly_rate_kg: float | None = None  # negative = losing
    weekly_rate_pct: float | None = None
    days_covered: int = 0
    weigh_ins: int = 0
    status: str = "insufficient_data"  # insufficient_data | losing | stable | gaining | losing_too_fast
    notes: list[str] = field(default_factory=list)


def ewma(values: list[float], alpha: float = EWMA_ALPHA) -> list[float]:
    if not values:
        return []
    out = [values[0]]
    for v in values[1:]:
        out.append(out[-1] + alpha * (v - out[-1]))
    return out


def _slope_per_day(points: list[tuple[int, float]]) -> float | None:
    if len(points) < 2:
        return None
    n = len(points)
    mean_x = sum(p[0] for p in points) / n
    mean_y = sum(p[1] for p in points) / n
    denom = sum((p[0] - mean_x) ** 2 for p in points)
    if denom == 0:
        return None
    return sum((p[0] - mean_x) * (p[1] - mean_y) for p in points) / denom


def weight_trend(series: list[tuple[str, float]], today: str) -> WeightTrend:
    """`series` is one (local_date, kg) per day, ascending."""
    result = WeightTrend(weigh_ins=len(series))
    if not series:
        result.notes.append("No weight data.")
        return result
    first_day = date.fromisoformat(series[0][0])
    today_d = date.fromisoformat(today)
    result.days_covered = (today_d - first_day).days + 1
    result.latest_raw = series[-1][1]

    # Interpolate missing days so the EWMA is time-consistent.
    filled: list[tuple[int, float]] = []
    by_day = {date.fromisoformat(d): v for d, v in series}
    last_val = series[0][1]
    for offset in range((date.fromisoformat(series[-1][0]) - first_day).days + 1):
        d = date.fromordinal(first_day.toordinal() + offset)
        last_val = by_day.get(d, last_val)
        filled.append((offset, last_val))
    trend = ewma([v for _, v in filled])
    result.latest_trend = round(trend[-1], 2)

    last7 = [v for d, v in series if (today_d - date.fromisoformat(d)).days < 7]
    result.avg_7d = round(sum(last7) / len(last7), 2) if last7 else None

    window = [(i, t) for i, t in enumerate(trend)][-14:]
    slope = _slope_per_day(window)
    if slope is not None:
        result.weekly_rate_kg = round(slope * 7, 2)
        result.weekly_rate_pct = round(slope * 7 / trend[-1] * 100, 2)

    if result.weigh_ins < MIN_WEIGHINS_FOR_TREND or result.days_covered < 7:
        result.status = "insufficient_data"
        result.notes.append(
            f"Only {result.weigh_ins} weigh-ins over {result.days_covered} days; trend not reliable yet."
        )
        return result
    rate = result.weekly_rate_pct or 0.0
    if rate < -SAFE_LOSS_RATE_MAX_PCT_PER_WEEK:
        result.status = "losing_too_fast"
        result.notes.append("Losing faster than 1% of bodyweight per week; consider eating a bit more.")
    elif rate <= -0.15:
        result.status = "losing"
    elif rate >= 0.15:
        result.status = "gaining"
    else:
        result.status = "stable"
    return result


def can_adjust_targets(trend: WeightTrend, food_logged_days_14: int) -> tuple[bool, str]:
    """Deterministic gate for calorie-target changes (see BEHAVIOR.md §Goal adjustment)."""
    if trend.days_covered < MIN_DAYS_FOR_TARGET_CHANGE or trend.weigh_ins < 8:
        return False, "Not enough weight data (need ~14 days and 8+ weigh-ins)."
    if food_logged_days_14 < 8:
        return False, "Food data too incomplete to know whether intake or tracking is the issue."
    return True, "Sufficient data."


@dataclass
class DayTotals:
    kcal_low: float = 0.0
    kcal_high: float = 0.0
    protein_low: float = 0.0
    protein_high: float = 0.0
    items_logged: int = 0
    items_unknown: int = 0
    steps: float | None = None
    steps_source: str = "unknown"  # health_connect | user_reported | unknown
    active_minutes_reported: float = 0.0

    def as_context(self) -> dict:
        food = (
            {
                "kcal_range": [round(self.kcal_low), round(self.kcal_high)],
                "protein_g_range": [round(self.protein_low), round(self.protein_high)],
                "items_logged": self.items_logged,
                "items_without_estimate": self.items_unknown,
                "status": "estimated (only what the user mentioned; unlogged meals unknown)",
            }
            if self.items_logged
            else {"status": "unknown (nothing logged)"}
        )
        return {
            "food": food,
            "steps": {"value": self.steps, "source": self.steps_source}
            if self.steps is not None
            else {"status": "unknown"},
            "reported_activity_minutes": self.active_minutes_reported,
        }


def day_totals(food_rows, activity_rows, hc_steps: float | None) -> DayTotals:
    totals = DayTotals()
    for row in food_rows:
        totals.items_logged += 1
        if row["kcal_low"] is None:
            totals.items_unknown += 1
            continue
        totals.kcal_low += row["kcal_low"]
        totals.kcal_high += row["kcal_high"]
        totals.protein_low += row["protein_low"] or 0
        totals.protein_high += row["protein_high"] or 0
    reported_steps = 0
    for row in activity_rows:
        totals.active_minutes_reported += row["duration_min"] or 0
        reported_steps += row["steps"] or 0
    if hc_steps is not None:
        totals.steps, totals.steps_source = hc_steps, "health_connect"
    elif reported_steps:
        totals.steps, totals.steps_source = float(reported_steps), "user_reported"
    return totals
