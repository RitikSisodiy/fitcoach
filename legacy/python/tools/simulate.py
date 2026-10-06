"""30-day simulation of a lazy, inconsistent user.

    python tools/simulate.py --mode offline            # plumbing check, scripted LLM, ground-truth extraction
    python tools/simulate.py --mode live --days 30     # real LLM (from .env) for BOTH the coach and the simulated user

The simulated user:
- works from home, has long meeting-heavy days ~2-3x a week
- plays chess Tue/Sat ~18:00, often buys street food there (paid by UPI)
- orders Swiggy ~2 dinners a week
- logs food in chat only ~25% of the time, mentions chess ~half the time
- responds to bot messages rarely, and less when messaged a lot
- chats randomly (history questions, complaints, "bore ho raha")
- commits to an evening walk once, then mostly skips it

Passive signals are generated as the phone would send them: Health Connect steps/
exercise/weight, payment and Swiggy notifications, and a calendar ICS.

The report compares what the coach recorded/inferred/did against ground truth.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import random
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from coach.analytics.habits import day_coverage  # noqa: E402
from coach.config import Settings  # noqa: E402
from coach.engine.engagement import PROACTIVE_KINDS, compute_engagement  # noqa: E402
from coach.engine.policy import SlotLearner, effective_budget  # noqa: E402
from coach.engine.safety import check_message  # noqa: E402
from coach.engine.service import CoachService, InboundMessage  # noqa: E402
from coach.ingest.calendar import ingest_ics  # noqa: E402
from coach.ingest.health_connect import ingest_payload  # noqa: E402
from coach.ingest.notifications import ingest_notification  # noqa: E402
from coach.llm.base import LLMProvider, LLMRequest  # noqa: E402
from coach.llm.fake import FakeProvider  # noqa: E402
from coach.memory.db import connect  # noqa: E402
from coach.memory.store import Store  # noqa: E402
from coach.timeutil import to_iso  # noqa: E402

TZ = "Asia/Kolkata"
ZONE = ZoneInfo(TZ)

USUAL = {
    "breakfast": [("poha", 1, "katori", 230), ("chai", 1, "cup", 95)],
    "lunch": [("roti", 3, "piece", 315), ("dal", 1, "katori", 160), ("sabzi", 1, "katori", 135)],
    "dinner": [("roti", 2, "piece", 210), ("paneer sabzi", 1, "katori", 320)],
}
STREET = [("sev parmal", 1, "plate", 300), ("samosa", 2, "piece", 550), ("kachori", 1, "piece", 250)]
SWIGGY = [("chicken biryani", 1, "plate", 650), ("burger", 1, "piece", 450), ("pizza", 3, "slice", 850)]


# ============================================================== ground truth
@dataclass
class Eat:
    slot: str
    items: list[tuple[str, float, str, int]]
    at: datetime
    kind: str = "home"  # home | street | order
    paid_upi: bool = False

    @property
    def kcal(self) -> int:
        return sum(i[3] for i in self.items)

    def phrase(self) -> str:
        return ", ".join(f"{q:g} {n}" for n, q, _, _ in self.items)


@dataclass
class DayTruth:
    day: date
    long_day: bool
    chess: bool
    eats: list[Eat] = field(default_factory=list)
    walk_minutes: int = 0
    steps: int = 0
    weight: float | None = None


def local(day: date, hhmm: str) -> datetime:
    h, m = map(int, hhmm.split(":"))
    return datetime(day.year, day.month, day.day, h, m, tzinfo=ZONE)


def build_world(start: date, days: int, rng: random.Random) -> list[DayTruth]:
    world = []
    weight = 84.0
    seed = rng.random()
    for i in range(days):
        d = start + timedelta(days=i)
        rng = random.Random(f"{seed}-{d.isoformat()}")  # independent per day (no cross-day RNG coupling)
        wd = d.weekday()
        long_day = wd < 5 and rng.random() < 0.5
        chess = wd in (1, 5)
        t = DayTruth(d, long_day, chess)
        t.eats.append(Eat("breakfast", USUAL["breakfast"], local(d, "09:15")))
        if long_day and rng.random() < 0.4:
            t.eats.append(Eat("lunch", [("maggi", 1, "packet", 350)], local(d, "15:10")))
        else:
            t.eats.append(Eat("lunch", USUAL["lunch"], local(d, "13:40")))
        if chess and rng.random() < 0.7:
            item = rng.choice(STREET)
            t.eats.append(Eat("snack", [item], local(d, "18:40"), "street", paid_upi=True))
        elif rng.random() < 0.25:
            t.eats.append(Eat("snack", [rng.choice(STREET)], local(d, "17:30"), "street", paid_upi=True))
        if rng.random() < 0.28:
            t.eats.append(Eat("dinner", [rng.choice(SWIGGY)], local(d, "21:15"), "order"))
        else:
            t.eats.append(Eat("dinner", USUAL["dinner"], local(d, "21:00")))
        t.walk_minutes = rng.choice([0, 0, 0, 12, 25]) if not long_day else rng.choice([0, 0, 0, 0, 8])
        t.steps = (rng.randint(1800, 3500) if long_day else rng.randint(4000, 7000)) + t.walk_minutes * 100 + (2500 if chess else 0)
        weight += -0.025 + rng.gauss(0, 0.05)
        t.weight = round(weight + rng.gauss(0, 0.5), 1) if rng.random() < 0.3 else None
        world.append(t)
    return world


# ============================================================== simulated user
@dataclass
class UserEvent:
    at: datetime
    text: str
    truth: dict  # ground-truth extraction for offline mode
    tag: str


class LazyUser:
    def __init__(self, rng: random.Random, persona_llm: LLMProvider | None):
        self.rng = rng
        self.llm = persona_llm
        self.messages_today = 0
        self.told_usual = False

    async def write(self, situation: str, fallback: str) -> str:
        if self.llm is None:
            return fallback
        prompt = (
            "You are role-playing a lazy 29-year-old Indian software engineer (WFH) texting his fitness bot on Telegram. "
            "Write ONE short casual Hinglish message (romanised Hindi + English, lowercase ok, typos ok, no emojis mostly). "
            "Be vague about quantities sometimes. Output only the message.\n\nSituation: " + situation
        )
        try:
            resp = await self.llm.generate(LLMRequest(system="You write realistic user chat messages.", user_text=prompt, temperature=1.0, max_output_tokens=256, purpose="sim_user"))
            return resp.text.strip().strip('"')[:300] or fallback
        except Exception:
            return fallback

    def plan_day(self, t: DayTruth, day_index: int) -> list[UserEvent]:
        rng, ev = self.rng, []
        decay = max(0.5, 1 - day_index / 60)  # lazier over time
        if day_index == 0:
            ev.append(UserEvent(local(t.day, "10:05"), "hi bhai, weight kam karna hai 84 se 76 tak. WFH hu, desk job.",
                                {"profile_updates": {"goal_text": "lose weight from 84 to 76 kg", "goal_weight_kg": 76},
                                 "body_metrics": [{"metric": "weight_kg", "value": 84, "confidence": 0.9}],
                                 "facts": [{"category": "routine", "key": "work_mode", "value": "WFH desk job", "confidence": 0.9}]}, "intro"))
        if day_index == 2:
            ev.append(UserEvent(local(t.day, "11:20"), "roz shaam 7-8 ke beech 30 min walk karunga, nahi ho paya toh 10 min",
                                {"commitments": [{"kind": "habit", "title": "Evening walk", "action": "Walk 30 min",
                                                  "fallback_versions": ["30 min walk", "20 min walk", "10 min walk"],
                                                  "schedule_days": "daily", "window_start": "19:00", "window_end": "20:00",
                                                  "activity_kind": "walk", "user_words": "roz shaam 7-8 walk"}]}, "commit"))
        for eat in t.eats:
            p = (0.35 if eat.kind != "home" else 0.22) * decay
            if rng.random() < p:
                when = eat.at + timedelta(minutes=rng.randint(5, 90))
                text = {"home": f"{eat.slot} me {eat.phrase()} khaya", "street": f"{eat.phrase()} kha liya bahar",
                        "order": f"aaj swiggy se {eat.phrase()} mangaya"}[eat.kind]
                ev.append(UserEvent(when, text, {"food_items": [
                    {"name": n, "quantity": q, "unit": u, "meal_slot": eat.slot, "eaten": "eaten", "confidence": 0.85,
                     "est_kcal_low": k * 0.8, "est_kcal_high": k * 1.2} for n, q, u, k in eat.items],
                    "lapse": {"detected": eat.kind != "home"}}, f"food:{eat.slot}"))
        if t.chess and rng.random() < 0.5 * decay:
            ev.append(UserEvent(local(t.day, "17:05"), "aaj chess club ja raha 6 baje",
                                {"context": [{"tag": "chess", "timing": "planned", "time_hint": "18:00"}]}, "chess"))
        if t.long_day and rng.random() < 0.4:
            ev.append(UserEvent(local(t.day, "16:40"), "aaj meetings hi meetings, bilkul time nahi",
                                {"context": [{"tag": "long_workday", "timing": "now"}]}, "busy"))
        if rng.random() < 0.3 * decay:
            choice = rng.choice([
                ("kal kitna khaya tha?", {"data_needed": [{"query": "food_log", "days": 2}]}),
                ("weight ka kya scene hai", {"data_needed": [{"query": "weight", "days": 14}]}),
                ("bore ho raha yaar", {}),
                ("walk ka is hafte kya haal", {"data_needed": [{"query": "commitment_history", "days": 7}]}),
            ])
            ev.append(UserEvent(local(t.day, f"{rng.randint(11, 22)}:{rng.choice(['05', '25', '45'])}"), choice[0], choice[1], "chatter"))
        if t.weight and rng.random() < 0.2:
            ev.append(UserEvent(local(t.day, "08:10"), f"weight {t.weight}", {"body_metrics": [{"metric": "weight_kg", "value": t.weight, "confidence": 0.95}]}, "weight"))
        return sorted(ev, key=lambda e: e.at)

    def react(self, out, t: DayTruth, now: datetime, day_index: int, intent: str = "") -> tuple[str, str | None, dict] | None:
        """Decide whether/how to react to a bot message. Returns (type, payload, truth) or None."""
        rng = self.rng
        if intent == "onboarding:usual_meals" and not self.told_usual and rng.random() < 0.6:
            self.told_usual = True  # a simple question gets answered more often than a nag
            return ("text", "usually nashta me poha chai, lunch me 3 roti dal sabzi, raat ko 2 roti paneer sabzi", {
                "profile_updates": {"usual_meals": [
                    {"slot": "breakfast", "items": [{"name": "poha", "quantity": 1, "unit": "katori"}, {"name": "chai", "quantity": 1, "unit": "cup"}]},
                    {"slot": "lunch", "items": [{"name": "roti", "quantity": 3, "unit": "piece"}, {"name": "dal", "quantity": 1, "unit": "katori"}, {"name": "sabzi", "quantity": 1, "unit": "katori"}]},
                    {"slot": "dinner", "items": [{"name": "roti", "quantity": 2, "unit": "piece"}, {"name": "paneer sabzi", "quantity": 1, "unit": "katori"}]},
                ]}})
        base = 0.4 * max(0.45, 1 - day_index / 45)
        if self.messages_today >= 2:
            base *= 0.5
        if not rng.random() < base:
            return None
        if out.buttons:
            datas = [b[1] for b in out.buttons]
            pick = None
            for dta in datas:
                if dta.startswith("inf:"):
                    pick = dta if dta.endswith(":yes") else pick  # true street food was paid by UPI
                    break
            if pick is None:
                street = any(e.kind != "home" for e in t.eats)
                prefs = {"rc": "offplan" if street else "usual", "re": "ok", "pc": "yes", "iv": "done" if t.walk_minutes else "skip"}
                for dta in datas:
                    prefix, _, action = dta.split(":")
                    if prefs.get(prefix) == action:
                        pick = dta
                        break
                pick = pick or datas[0]
            return ("button", pick, {})
        return ("text", "haan theek hai", {})


# ============================================================== offline coach LLM
def offline_provider(truth_by_text: dict[str, dict]) -> FakeProvider:
    fake = FakeProvider()
    empty = {"food_items": [], "activities": [], "body_metrics": [], "context": [], "commitments": [],
             "commitment_updates": [], "facts": [], "lapse": {"detected": False}}

    def extract(req: LLMRequest):
        text = req.user_text.split("USER_MESSAGE:", 1)[1].strip()
        return {**empty, **truth_by_text.get(text, {})}

    def decide(req: LLMRequest):
        cands = json.loads(req.user_text.split("CANDIDATES:\n", 1)[1])
        c = cands[0]
        extra = {"proposed_rule": "On chess days: coffee only"} if c["intent"].startswith("propose_commitment") else {}
        return {"choice": 0, "message": f"[{c['intent']}] {c['why'][:80]}", "reason": "offline: top candidate", **extra}

    fake.set_default("extract", extract)
    fake.set_default("decide", decide)
    fake.set_default("reply", "ok, noted.")
    fake.set_default("nudge", "Walk time - 10 min bhi chalega?")
    fake.set_default("review", "Weekly review: one small experiment for next week?")
    return fake


# ============================================================== simulation
async def run(args) -> dict:
    rng = random.Random(args.seed)
    start = date.fromisoformat(args.start)
    world = build_world(start, args.days, rng)
    db_path = args.db or str(Path(args.out) / f"sim_{args.mode}.db")
    Path(db_path).unlink(missing_ok=True)
    store = Store(connect(db_path), TZ)

    truth_by_text: dict[str, dict] = {}
    if args.mode == "live":
        from coach.llm.factory import create_provider

        settings = Settings.from_env()
        coach_llm = create_provider(settings)
        # The persona LLM doubles API usage; on the free tier keep the user scripted unless asked.
        persona_llm = coach_llm if args.user_llm else None
    else:
        coach_llm = offline_provider(truth_by_text)
        persona_llm = None

    service = CoachService(store, coach_llm, learner=SlotLearner(store, random.Random(args.seed)))
    user = LazyUser(rng, persona_llm)
    outbound_log: list[dict] = []
    daily: list[dict] = []
    pending_reactions: list[tuple[datetime, object]] = []
    llm_calls_before = 0

    async def user_send(text: str, now: datetime, kind: str = "text"):
        for out in await service.handle_message(InboundMessage(text=text, received_at=now)):
            outbound_log.append({"at": now.isoformat(), "text": out.text, "intent": "reply", "buttons": [b[0] for b in out.buttons]})

    for i, t in enumerate(world):
        user.messages_today = 0
        # Calendar for long days (times only).
        if t.long_day:
            day_s = t.day.strftime("%Y%m%d")
            ics = ("BEGIN:VCALENDAR\nVERSION:2.0\n"
                   f"BEGIN:VEVENT\nUID:a{day_s}\nDTSTART:{day_s}T033000Z\nDTEND:{day_s}T073000Z\nEND:VEVENT\n"
                   f"BEGIN:VEVENT\nUID:b{day_s}\nDTSTART:{day_s}T081500Z\nDTEND:{day_s}T140000Z\nEND:VEVENT\nEND:VCALENDAR")
            ingest_ics(store, ics, local(t.day, "07:00"), days_ahead=0)

        events = user.plan_day(t, i)
        for e in events:
            if args.mode == "live":
                situation = {
                    "intro": "First message ever: you want to lose weight from 84 to 76 kg, you work from home at a desk job.",
                    "commit": "You decide to commit to a 30 min evening walk between 7 and 8 pm daily, or 10 min if you can't.",
                    "chess": "You're about to go to your chess club at 6 pm.",
                    "busy": "Back-to-back meetings all day, no time.",
                    "weight": f"You just weighed yourself: {t.weight} kg.",
                }.get(e.tag)
                if e.tag.startswith("food"):
                    situation = f"You just ate ({e.text}). Mention it casually."
                if e.tag == "chatter":
                    situation = f"Random message to the bot, roughly: '{e.text}'."
                e.text = await user.write(situation or e.text, e.text)
            truth_by_text[e.text] = e.truth
        queue = list(events)

        hc_steps_reported = 0
        tick = local(t.day, "07:00")
        end = local(t.day, "23:45")
        while tick <= end:
            # --- phone: Health Connect sync every hour (cumulative steps so far today)
            if tick.minute == 0:
                hours_awake = max(0, min(16, (tick.hour - 7)))
                so_far = int(t.steps * hours_awake / 16)
                if so_far > hc_steps_reported:
                    s = local(t.day, f"{7 + hours_awake - 1:02d}:00") if hours_awake else tick
                    ingest_payload(store, {"steps": [{"count": so_far - hc_steps_reported, "start_time": to_iso(s), "end_time": to_iso(tick)}]}, tick)
                    hc_steps_reported = so_far
                if tick.hour == 8 and t.weight:
                    ingest_payload(store, {"weight": [{"kilograms": t.weight, "time": to_iso(local(t.day, "07:50"))}]}, tick)
                if tick.hour == 21 and t.walk_minutes:
                    ws = local(t.day, "19:20")
                    ingest_payload(store, {"exercise": [{"type": "WALKING", "start_time": to_iso(ws), "end_time": to_iso(ws + timedelta(minutes=t.walk_minutes)), "duration_seconds": t.walk_minutes * 60}]}, tick)
            # --- phone: payment / order notifications at the moment they happen
            for eat in t.eats:
                if tick <= eat.at < tick + timedelta(minutes=15):
                    if eat.paid_upi:
                        ingest_notification(store, {"package": "com.phonepe.app", "title": "Payment successful",
                                                    "text": f"₹{rng.choice([30, 40, 50])} paid to SHARMA CHAAT CORNER", "time": to_iso(eat.at)}, eat.at)
                    if eat.kind == "order":
                        ingest_notification(store, {"package": "in.swiggy.android", "title": "Order delivered",
                                                    "text": "Your order from Behrouz Biryani has been delivered.", "time": to_iso(eat.at)}, eat.at)
            # --- user-initiated messages
            while queue and queue[0].at <= tick + timedelta(minutes=15):
                e = queue.pop(0)
                await user_send(e.text, e.at)
            # --- delayed reactions to bot messages
            for when, react in [p for p in pending_reactions if p[0] <= tick]:
                pending_reactions.remove((when, react))
                rtype, payload, _ = react
                if rtype == "button":
                    data = payload
                    res = await (service.handle_reason(data, when) if data.startswith("rs:") else service.handle_button(data, when))
                    if res:
                        outbound_log.append({"at": when.isoformat(), "text": res.text, "intent": "button_ack", "buttons": [b[0] for b in res.buttons]})
                        if res.buttons and rng.random() < 0.5:  # skip reason prompt
                            pending_reactions.append((when + timedelta(minutes=2), ("button", res.buttons[rng.randrange(len(res.buttons))][1], {})))
                else:
                    text = payload
                    if args.mode == "live":
                        text = await user.write(f"Reply briefly to your bot's last message (lazy, low effort). Today's truth: ate {', '.join(e.phrase() for e in t.eats)}; walked {t.walk_minutes} min.", payload)
                    if args.mode == "live" and react[2]:
                        text = await user.write("Your bot asked what you usually eat. Answer: breakfast poha + chai, lunch 3 roti dal sabzi, dinner 2 roti paneer sabzi.", payload)
                    truth_by_text.setdefault(text, react[2])
                    await user_send(text, when)
            # --- coach tick
            for out in await service.tick(tick):
                row = store.get_intervention(out.intervention_id) if out.intervention_id else None
                outbound_log.append({"at": tick.isoformat(), "text": out.text, "intent": row["intent"] if row else "?", "buttons": [b[0] for b in out.buttons]})
                user.messages_today += 1
                react = user.react(out, t, tick, i, row["intent"] if row else "")
                if react:
                    pending_reactions.append((tick + timedelta(minutes=rng.randint(5, 80)), react))
            tick += timedelta(minutes=15)

        eng = compute_engagement(store, local(t.day, "23:50"), effective_budget(store.get_profile()))
        daily.append({"day": t.day.isoformat(), "engagement": eng.state, "budget": eng.daily_budget,
                      "proactive": sum(1 for r in store.interventions_on(t.day.isoformat()) if r["kind"] in PROACTIVE_KINDS),
                      "retired": sorted(eng.retired_intents)})

    llm_calls = len(getattr(coach_llm, "requests", [])) - llm_calls_before
    return evaluate(store, world, outbound_log, daily, args, llm_calls)


# ============================================================== evaluation
def evaluate(store: Store, world: list[DayTruth], outbound: list[dict], daily: list[dict], args, llm_calls: int) -> dict:
    main_slots = ("breakfast", "lunch", "dinner")
    captured = Counter()
    for t in world:
        cov = day_coverage(store, t.day.isoformat())
        for slot in main_slots:
            src = cov["logged"].get(slot)
            captured[src or "missing"] += 1
    total_meals = len(world) * 3
    street_truth = [(t.day, e) for t in world for e in t.eats if e.kind == "street"]
    street_caught = 0
    for d, e in street_truth:
        rows = store.food_for_date(d.isoformat())
        if any(r["meal_slot"] == "snack" or r["source"] == "inferred_confirmed" for r in rows):
            street_caught += 1
    order_truth = [(t.day, e) for t in world for e in t.eats if e.kind == "order"]
    inferred = store.conn.execute("SELECT kind, status, COUNT(*) n FROM inferred_events GROUP BY kind, status").fetchall()

    kcal_err = []
    for t in world:
        rows = store.food_for_date(t.day.isoformat())
        cov = day_coverage(store, t.day.isoformat())
        if not cov["missing"] and rows and all(r["kcal_low"] is not None for r in rows):
            mid = sum((r["kcal_low"] + r["kcal_high"]) / 2 for r in rows)
            truth = sum(e.kcal for e in t.eats)
            kcal_err.append(abs(mid - truth) / truth)

    from coach.analytics.habits import estimated_day_intake

    est_err, est_days = [], 0
    for t in world:
        e = estimated_day_intake(store, t.day.isoformat())
        if e["total_kcal_range"]:
            est_days += 1
            mid = sum(e["total_kcal_range"]) / 2
            truth = sum(x.kcal for x in t.eats)
            est_err.append((mid - truth) / truth)

    patterns = [{"claim": p["claim"], "status": p["status"], "first_seen": p["first_seen"][:10]} for p in store.patterns(None)]
    chess_days = [t.day.isoformat() for t in world if t.chess]
    chess_set = set(chess_days)
    pre_chess = [o for o in outbound if (o["intent"] or "").startswith(("predicted_context", "propose_commitment"))
                 and o["at"][:10] in chess_set and o["at"][11:16] < "18:40"]
    by_intent = Counter((o["intent"] or "?").split(":")[0] for o in outbound if o["intent"] not in ("reply", "button_ack"))
    weeks = defaultdict(lambda: {"proactive": 0, "days": 0})
    for i, dd in enumerate(daily):
        w = i // 7 + 1
        weeks[w]["proactive"] += dd["proactive"]
        weeks[w]["days"] += 1
    rows = store.conn.execute("SELECT local_date, kind, status FROM interventions WHERE kind IN ('scheduled','proactive','follow_up','review')").fetchall()
    resp_by_week = defaultdict(lambda: [0, 0])
    first = world[0].day
    for r in rows:
        w = (date.fromisoformat(r["local_date"]) - first).days // 7 + 1
        resp_by_week[w][1] += 1
        if r["status"] in {"acted", "smaller", "skipped", "snoozed", "answered"}:
            resp_by_week[w][0] += 1
    violations = [o for o in outbound if not check_message(o["text"]).ok]
    quiet = [o for o in outbound if o["intent"] not in ("reply", "button_ack") and (datetime.fromisoformat(o["at"]).astimezone(ZONE).hour >= 23 or datetime.fromisoformat(o["at"]).astimezone(ZONE).hour < 7)]
    commitments = store.conn.execute("SELECT id, title, status FROM commitments").fetchall()
    walk_truth = sum(1 for t in world if t.walk_minutes)
    walk_logged = store.conn.execute("SELECT COUNT(DISTINCT local_date) FROM commitment_log WHERE outcome IN ('done','smaller')").fetchone()[0]
    steps_days = sum(1 for t in world if store.health_daily_sum("steps", t.day.isoformat()))

    return {
        "mode": args.mode,
        "days": len(world),
        "tracking": {
            "main_meals_truth": total_meals,
            "main_meals_captured_by_source": dict(captured),
            "main_meal_capture_pct": round(100 * (total_meals - captured["missing"]) / total_meals),
            "street_food_events_truth": len(street_truth),
            "street_food_days_captured": street_caught,
            "food_orders_truth": len(order_truth),
            "inferred_events": [dict(r) for r in inferred],
            "kcal_mape_on_fully_covered_days": round(100 * sum(kcal_err) / len(kcal_err)) if kcal_err else None,
            "fully_covered_days": len(kcal_err),
            "days_with_intake_estimate_incl_assumed": est_days,
            "intake_estimate_mape_pct": round(100 * sum(abs(x) for x in est_err) / len(est_err)) if est_err else None,
            "intake_estimate_bias_pct": round(100 * sum(est_err) / len(est_err)) if est_err else None,
            "steps_days_with_data": steps_days,
            "walk_days_truth": walk_truth,
            "walk_days_recorded": walk_logged,
        },
        "understanding": {"patterns": patterns, "chess_days": len(chess_days)},
        "coaching": {
            "proactive_by_intent": dict(by_intent),
            "pre_emptive_chess_messages": len(pre_chess),
            "proactive_per_day_by_week": {w: round(v["proactive"] / v["days"], 2) for w, v in sorted(weeks.items())},
            "response_rate_by_week": {w: f"{a}/{b}" for w, (a, b) in sorted(resp_by_week.items())},
            "engagement_states": Counter(dd["engagement"] for dd in daily),
            "engagement_timeline": [dd["engagement"][0].upper() for dd in daily],
            "retired_types_seen": sorted({x for dd in daily for x in dd["retired"]}),
            "commitments": [dict(c) for c in commitments],
        },
        "safety": {"tone_or_safety_violations": len(violations), "messages_in_quiet_hours": len(quiet)},
        "llm_calls": llm_calls,
        "sample_messages": [o for o in outbound if o["intent"] not in ("reply", "button_ack")][:6]
        + [o for o in outbound if o["intent"] not in ("reply", "button_ack")][-6:],
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["offline", "live"], default="offline")
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--start", default="2026-09-07")  # a Monday
    ap.add_argument("--out", default="sim_reports")
    ap.add_argument("--db", default="")
    ap.add_argument("--user-llm", action="store_true", help="live mode: let the LLM write the user's messages too")
    args = ap.parse_args()
    Path(args.out).mkdir(exist_ok=True)
    report = asyncio.run(run(args))
    path = Path(args.out) / f"report_{args.mode}_{args.days}d_seed{args.seed}.json"
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False, default=str))
    print(json.dumps(report, indent=2, ensure_ascii=False, default=str))
    print(f"\nReport written to {path}")


if __name__ == "__main__":
    main()
