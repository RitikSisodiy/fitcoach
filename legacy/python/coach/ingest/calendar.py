"""Calendar (private ICS URL) -> workday load and free slots.

Used to recognise long workdays and to suggest micro-actions in real free slots
instead of generic times. Only start/end times are stored, never titles.
"""

from __future__ import annotations

import logging
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from ..memory.store import Store

log = logging.getLogger(__name__)

WORKDAY_START = time(8, 0)
WORKDAY_END = time(22, 0)
MIN_FREE_MINUTES = 20


def summarise_day(intervals: list[tuple[datetime, datetime]], day: date, tz: str) -> dict:
    zone = ZoneInfo(tz)
    start_bound = datetime.combine(day, WORKDAY_START, zone)
    end_bound = datetime.combine(day, WORKDAY_END, zone)
    clipped = sorted(
        (max(s.astimezone(zone), start_bound), min(e.astimezone(zone), end_bound))
        for s, e in intervals
        if e.astimezone(zone) > start_bound and s.astimezone(zone) < end_bound
    )
    merged: list[list[datetime]] = []
    for s, e in clipped:
        if merged and s <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], e)
        else:
            merged.append([s, e])
    busy = sum((e - s).total_seconds() for s, e in merged) / 3600
    free, cursor = [], start_bound
    for s, e in merged:
        if (s - cursor) >= timedelta(minutes=MIN_FREE_MINUTES):
            free.append([cursor.strftime("%H:%M"), s.strftime("%H:%M")])
        cursor = max(cursor, e)
    if merged and (end_bound - cursor) >= timedelta(minutes=MIN_FREE_MINUTES):
        free.append([cursor.strftime("%H:%M"), end_bound.strftime("%H:%M")])
    return {
        "meeting_hours": round(busy, 1),
        "first_start": merged[0][0].strftime("%H:%M") if merged else None,
        "last_end": merged[-1][1].strftime("%H:%M") if merged else None,
        "free_slots": free if merged else [],
        "busy": [[s.strftime("%H:%M"), e.strftime("%H:%M")] for s, e in merged],
    }


def ingest_ics(store: Store, ics_text: str, now: datetime, days_ahead: int = 2) -> int:
    """Parse ICS text and store summaries for today .. today+days_ahead. Returns days stored."""
    import icalendar
    import recurring_ical_events

    zone = ZoneInfo(store.tz)
    cal = icalendar.Calendar.from_ical(ics_text)
    today = now.astimezone(zone).date()
    stored = 0
    for offset in range(days_ahead + 1):
        day = today + timedelta(days=offset)
        start = datetime.combine(day, time(0, 0), zone)
        events = recurring_ical_events.of(cal).between(start, start + timedelta(days=1))
        intervals = []
        for ev in events:
            s, e = ev.get("DTSTART").dt, ev.get("DTEND").dt if ev.get("DTEND") else None
            if not isinstance(s, datetime):
                continue  # all-day events don't block time
            if e is None:
                e = s + timedelta(minutes=30)
            if s.tzinfo is None:
                s, e = s.replace(tzinfo=zone), e.replace(tzinfo=zone)
            transp = str(ev.get("TRANSP", "OPAQUE")).upper()
            if transp == "TRANSPARENT":
                continue
            intervals.append((s, e))
        summary = summarise_day(intervals, day, store.tz)
        store.set_calendar_day(now, day.isoformat(), summary["meeting_hours"], summary["first_start"], summary["last_end"],
                               {"free": summary["free_slots"], "busy": summary["busy"]})
        stored += 1
    return stored


async def fetch_ics(url: str, timeout: float = 20.0) -> str:
    import httpx

    async with httpx.AsyncClient(timeout=timeout, follow_redirects=True) as client:
        resp = await client.get(url)
        resp.raise_for_status()
        return resp.text
