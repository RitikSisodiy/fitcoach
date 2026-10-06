"""Time helpers. All persisted instants are ISO-8601 UTC; local dates use the user's timezone."""

from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def to_iso(dt: datetime) -> str:
    if dt.tzinfo is None:
        raise ValueError("Naive datetimes are not allowed")
    return dt.astimezone(timezone.utc).isoformat(timespec="seconds")


def parse_iso(value: str) -> datetime:
    dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def local_date(dt: datetime, tz: str) -> str:
    return dt.astimezone(ZoneInfo(tz)).date().isoformat()


def local_time(dt: datetime, tz: str) -> time:
    return dt.astimezone(ZoneInfo(tz)).time()


def parse_hhmm(value: str) -> time:
    hours, minutes = value.split(":")
    return time(int(hours), int(minutes))


def in_window(t: time, start: time, end: time) -> bool:
    """True if t is within [start, end). Handles windows that cross midnight."""
    if start <= end:
        return start <= t < end
    return t >= start or t < end


def slot_of(t: time, slot_minutes: int = 30) -> str:
    minutes = (t.hour * 60 + t.minute) // slot_minutes * slot_minutes
    return f"{minutes // 60:02d}:{minutes % 60:02d}"


def slots_between(start: time, end: time, slot_minutes: int = 30) -> list[str]:
    """Slots on the same grid as `slot_of` (window start is snapped down to the grid)."""
    result = []
    cursor = (start.hour * 60 + start.minute) // slot_minutes * slot_minutes
    stop = end.hour * 60 + end.minute
    if stop <= cursor:
        stop += 24 * 60
    while cursor < stop:
        m = cursor % (24 * 60)
        result.append(f"{m // 60:02d}:{m % 60:02d}")
        cursor += slot_minutes
    return result


WEEKDAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]


def weekday_key(d: date) -> str:
    return WEEKDAYS[d.weekday()]


def days_ago(d: str, n: int) -> str:
    return (date.fromisoformat(d) - timedelta(days=n)).isoformat()
