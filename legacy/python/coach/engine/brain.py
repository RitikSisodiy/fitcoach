"""Proactive brain: code proposes and gates, the LLM chooses and writes.

Every tick:
1. Candidate generators (deterministic) list interventions that *could* be useful now,
   each with the facts that justify it. None of them depend on the user having
   configured anything: recap, morning plan, predicted situations, inactivity,
   re-engagement and onboarding work from observed data alone.
2. The gate removes candidates that violate hard limits: pause, quiet hours, the
   engagement-adaptive budget, cooldowns, retired message types, once-per-day rules.
3. The LLM sees a compact digest plus the surviving candidates (max 3) and either
   picks one and writes it, or chooses silence. Silence is a valid outcome.
4. The safety guard checks the text; on LLM failure the top candidate is sent
   with a deterministic template.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

from ..analytics.habits import day_coverage, habitual_meals
from ..analytics.patterns import lapse_pattern_for, predicted_contexts
from ..llm.base import LLMError, LLMProvider, LLMRequest
from ..memory.store import Store
from ..timeutil import days_ago, in_window, local_time, parse_hhmm, parse_iso, slot_of, slots_between
from .coach import COACH_SYSTEM
from .engagement import Engagement, intent_family
from .policy import SlotLearner, analyse_commitment, decide_level, is_scheduled_on
from .safety import check_message

MEAL_SLOT_TIMES = {"breakfast": "09:00", "lunch": "13:30", "snack": "17:30", "dinner": "21:00", "drink": "16:00"}


@dataclass
class Candidate:
    intent: str  # family[:detail], e.g. "commitment:3", "recap", "predicted_context:chess"
    priority: int
    why: str  # concise, factual justification (goes to the LLM and the decision log)
    facts: dict = field(default_factory=dict)
    fallback_text: str = ""
    commitment_id: int | None = None
    level: int = 1
    version: str | None = None
    slot: str | None = None

    @property
    def family(self) -> str:
        return intent_family(self.intent)

    def for_llm(self, idx: int) -> dict:
        return {"id": idx, "intent": self.intent, "why": self.why, "facts": self.facts}


DECIDE_SCHEMA = {
    "type": "object",
    "properties": {
        "choice": {"type": "integer", "description": "id of the chosen candidate, or -1 for no message now"},
        "message": {"type": "string"},
        "proposed_rule": {"type": "string", "description": "only for propose_commitment: the default rule, in the user's language"},
        "reason": {"type": "string", "description": "one short sentence: why this (or silence) now"},
    },
    "required": ["choice", "reason"],
}

DECIDE_INSTRUCTIONS = """You are deciding whether to send ONE proactive message right now, and writing it if so.
The candidates were pre-approved by the coaching policy; you may pick one or choose silence (-1).
Choose silence when a message would add little (e.g. the user is clearly busy, it repeats something recent,
or it would feel like nagging). Prefer the candidate with the most concrete, timely value.

Writing rules:
- 1-2 short sentences, in the user's language/register (Hinglish if they use it). Specific, never generic.
- Use ONLY numbers that appear in the facts. Never invent data. Say "unknown" rather than guess.
- No guilt, no lectures. Make the next step tiny and easy. Buttons are added automatically - don't list them.
- commitment: follow facts.level: 1 gentle check-in; 2 reference the situation and offer the given version;
  3 state the observed pattern factually and propose one change; 4 warmly but clearly recommend the smaller version now;
  5 (user-authorised) ask for the minimum version or an explicit reason + reschedule. Offer facts.offer_version.
- recap: ask about the missing meals/pending items in one line; mention defaults if provided so one tap suffices.
- reengage: warm, zero pressure, one-tap; never mention how long they've been silent in a blaming way.
- predicted_context: remind them of their own default (if any) BEFORE the situation; if a pattern exists but no rule,
  suggest one simple default.
- propose_commitment: state the observed pattern with its numbers and propose ONE default rule (put it in proposed_rule).
- onboarding: ask exactly one easy question that fills the missing profile item.
"""


class Brain:
    def __init__(self, store: Store, llm: LLMProvider, learner: SlotLearner):
        self.store = store
        self.llm = llm
        self.learner = learner

    # ============================================================ candidates
    def candidates(self, now: datetime, engagement: Engagement, profile: dict) -> list[Candidate]:
        store = self.store
        today = store._date(now)
        t = local_time(now, store.tz)
        sent_today = {r["intent"] for r in store.interventions_on(today) if r["intent"]}
        out: list[Candidate] = []

        def add(c: Candidate | None) -> None:
            if c is not None and c.intent not in sent_today:
                out.append(c)

        for c in self._commitment_candidates(now, today, t, profile):
            add(c)
        add(self._recap(now, today, t, profile, engagement))
        add(self._morning_plan(now, today, t))
        for c in self._predicted_context(now, today, t):
            add(c)
        add(self._inactivity(now, today, t))
        add(self._reengage(now, today, t, engagement))
        add(self._propose_commitment(now, today, t))
        add(self._onboarding(now, today, t, profile))
        add(self._stale_sync(now, today, t))
        return sorted(out, key=lambda c: -c.priority)

    def _commitment_candidates(self, now, today, t, profile) -> list[Candidate]:
        store = self.store
        result = []
        todays = store.interventions_on(today)
        for c in store.active_commitments():
            if not c.window_start or not is_scheduled_on(c, date.fromisoformat(today)):
                continue
            if store.commitment_outcome_on(c.id, today) in {"done", "smaller", "skipped"}:
                continue
            mine = [r for r in todays if r["commitment_id"] == c.id and r["kind"] in {"scheduled", "follow_up"}]
            if mine:
                continue  # follow-ups after "Later" are handled by the service
            start, end = parse_hhmm(c.window_start), parse_hhmm(c.window_end)
            if not in_window(t, start, end):
                continue
            pattern = analyse_commitment(store, c, today)
            decision = decide_level(c, pattern, profile, today)
            if decision.back_off and any(
                r["commitment_id"] == c.id and r["kind"] == "scheduled" for r in store.interventions_on(days_ago(today, 1))
            ):
                store.log_decision(now, "back_off", f"Commitment {c.id}: skipping today. {decision.reason}", pattern)
                continue
            current = slot_of(t)
            window_slots = slots_between(start, end)
            remaining = window_slots[window_slots.index(current):] if current in window_slots else [current]
            send, why = self.learner.should_send_now(c.id, current, remaining)
            if not send:
                continue
            result.append(
                Candidate(
                    intent=f"commitment:{c.id}",
                    priority=80 + decision.level,
                    why=f"Commitment due now (level {decision.level}: {decision.reason}; timing: {why})",
                    facts={"commitment": c.as_context(), "offer_version": decision.version, "pattern": pattern, "level": decision.level},
                    fallback_text=f"{c.title}: how about {decision.version} now?",
                    commitment_id=c.id,
                    level=decision.level,
                    version=decision.version,
                    slot=current,
                )
            )
        return result

    def recap_items(self, now: datetime, today: str) -> dict:
        store = self.store
        coverage = day_coverage(store, today)
        weekend = date.fromisoformat(today).weekday() >= 5
        habits = habitual_meals(store, today, weekend=weekend) or habitual_meals(store, today)
        defaults = {s: habits[s].describe() for s in coverage["missing"] if s in habits}
        pending = [
            {"id": r["id"], "kind": r["kind"], "summary": r["summary"], "time": store.fmt_local(parse_iso(r["occurred_at"]), "%H:%M")}
            for r in store.pending_inferred(today)
        ][:3]
        return {"missing_meals": coverage["missing"], "defaults": defaults, "pending_inferred": pending}

    def _recap(self, now, today, t, profile, engagement: Engagement) -> Candidate | None:
        for offset in range(1, engagement.recap_every_days):
            if any(r["intent"] == "recap" for r in self.store.interventions_on(days_ago(today, offset))):
                return None  # recaps are being ignored: ask less often rather than stop tracking
        start = parse_hhmm(profile.get("recap_time") or "21:00")
        end_minutes = start.hour * 60 + start.minute + 90
        end = parse_hhmm(f"{min(end_minutes // 60, 23):02d}:{end_minutes % 60:02d}")
        if not in_window(t, start, end):
            return None
        items = self.recap_items(now, today)
        if not items["missing_meals"] and not items["pending_inferred"]:
            return None
        missing = ", ".join(items["missing_meals"]) or "nothing"
        return Candidate(
            intent="recap",
            priority=70,
            why=f"Evening recap: meals not logged today: {missing}; {len(items['pending_inferred'])} inferred item(s) to confirm",
            facts=items,
            fallback_text="Quick recap: " + (
                "; ".join(f"{s} - usual ({d})?" for s, d in items["defaults"].items()) or f"what did you have for {missing}?"
            ),
        )

    def _morning_plan(self, now, today, t) -> Candidate | None:
        if not in_window(t, parse_hhmm("08:30"), parse_hhmm("11:00")):
            return None
        store = self.store
        facts: dict = {}
        preds = predicted_contexts(store, today)
        if preds:
            facts["predicted_today"] = [{"situation": p["tag"], "around": p["typical_time"]} for p in preds]
        cal = store.calendar_day(today)
        if cal and cal["meeting_hours"] >= 6:
            facts["long_workday"] = {"meeting_hours": cal["meeting_hours"], "free_slots": cal["free_slots"][:4]}
        yesterday_lapse = any(
            d["kind"] == "lapse" and store._date(parse_iso(d["created_at"])) == days_ago(today, 1)
            for d in store.recent_decisions(50)
        )
        if yesterday_lapse:
            facts["yesterday"] = "an off-plan moment was reported; today is a normal day"
        if not facts:
            return None
        due = [c.title for c in store.active_commitments() if c.window_start and is_scheduled_on(c, date.fromisoformat(today))]
        if due:
            facts["commitments_today"] = due
        return Candidate(
            intent="morning_plan",
            priority=40,
            why="Morning: there is something specific to plan for today",
            facts=facts,
            fallback_text="Today's plan: " + "; ".join(f"{k}: {v}" for k, v in facts.items())[:200],
        )

    def _predicted_context(self, now, today, t) -> list[Candidate]:
        store = self.store
        result = []
        mentioned_today = {c["tag"] for c in store.context_events_since(today)}
        for p in predicted_contexts(store, today):
            if p["tag"] in mentioned_today:
                continue  # the in-reply reminder already handles it
            typical = parse_hhmm(p["typical_time"])
            minutes = typical.hour * 60 + typical.minute
            start = parse_hhmm(f"{max(minutes - 120, 0) // 60:02d}:{max(minutes - 120, 0) % 60:02d}")
            end = parse_hhmm(f"{max(minutes - 15, 0) // 60:02d}:{max(minutes - 15, 0) % 60:02d}")
            if not in_window(t, start, end):
                continue
            rules = store.commitments_with_trigger(p["tag"])
            lapse = lapse_pattern_for(store, p["tag"])
            if p["kind"] == "weekday_payee" and not lapse:
                lapse = {"claim": p["claim"]}  # a recurring snack purchase is itself the off-plan pattern
            if not rules and not lapse:
                continue  # predicting a situation is only worth a message if there is something to act on
            facts = {"situation": p["tag"], "pattern": p["claim"]}
            if rules:
                facts["user_rule"] = rules[0].user_words or rules[0].action
            if lapse:
                facts["observed"] = lapse["claim"]
            result.append(
                Candidate(
                    intent=f"predicted_context:{p['tag']}",
                    priority=75,
                    why=f"'{p['tag']}' is predicted around {p['typical_time']} today; acting before the situation",
                    facts=facts,
                    fallback_text=f"{p['tag'].replace('_', ' ').title()} today? Your plan: {facts.get('user_rule', 'keep it light')}.",
                )
            )
        return result

    def _inactivity(self, now, today, t) -> Candidate | None:
        if not in_window(t, parse_hhmm("15:00"), parse_hhmm("19:30")):
            return None
        store = self.store
        last_sync = store.last_health_sync()
        if not last_sync or now - parse_iso(last_sync) > timedelta(hours=3):
            return None  # stale data: don't coach on numbers we can't trust
        steps = store.health_daily_sum("steps", today) or 0
        history = [store.health_daily_sum("steps", days_ago(today, i)) for i in range(1, 15)]
        known = [s for s in history if s]
        if len(known) < 5:
            return None
        typical = sum(known) / len(known)
        if steps >= max(1500, 0.35 * typical):
            return None
        cal = store.calendar_day(today)
        facts = {"steps_so_far": int(steps), "typical_daily_steps": int(typical)}
        if cal:
            current = t.strftime("%H:%M")
            if any(b[0] <= current < b[1] for b in cal.get("busy", [])):
                return None  # in a meeting now; wait for a free slot
            free_now = [s for s in cal["free_slots"] if s[0] <= current < s[1]]
            facts["calendar_free_until"] = free_now[0][1] if free_now else None
        return Candidate(
            intent="inactivity",
            priority=45,
            why=f"Low movement today ({int(steps)} steps vs ~{int(typical)} typical)",
            facts=facts,
            fallback_text="Long sitting day - 5 minute walk now?",
        )

    def _reengage(self, now, today, t, engagement: Engagement) -> Candidate | None:
        if engagement.days_since_inbound is None or engagement.days_since_inbound < 3:
            return None
        if not in_window(t, parse_hhmm("10:00"), parse_hhmm("20:00")):
            return None
        gap = max(engagement.min_days_between, 2)
        for offset in range(0, gap):
            if any(r["intent"] == "reengage" for r in self.store.interventions_on(days_ago(today, offset))):
                return None
        return Candidate(
            intent="reengage",
            priority=90 if engagement.state in {"silent", "dormant"} else 60,
            why=f"No message from the user for {engagement.days_since_inbound:.0f} days; offer a one-tap way back",
            facts={"engagement": engagement.state},
            fallback_text="Quick check-in - how's it going? One tap is enough.",
        )

    def _propose_commitment(self, now, today, t) -> Candidate | None:
        if not in_window(t, parse_hhmm("10:00"), parse_hhmm("18:00")):
            return None
        store = self.store
        for p in list(store.patterns("active", "context_lapse")) + list(store.patterns("active", "weekday_payee")):
            data = json.loads(p["data_json"])
            tag = data["tag"]
            if store.commitments_with_trigger(tag):
                continue
            key = f"propose_commitment:{tag}"
            recent = [r for r in store.interventions_since(days_ago(today, 10)) if r["intent"] == key]
            if recent:
                continue
            return Candidate(
                intent=key,
                priority=50,
                why=f"Repeated pattern without a plan: {p['claim']}",
                facts={"pattern": p["claim"], "situation": tag, "pattern_id": p["id"]},
                fallback_text=f"I've noticed: {p['claim']}. Want a simple default for {tag.replace('_', ' ')} days?",
            )
        return None

    def _onboarding(self, now, today, t, profile) -> Candidate | None:
        if not in_window(t, parse_hhmm("10:00"), parse_hhmm("20:00")):
            return None
        store = self.store
        topics: list[tuple[str, str]] = []
        if not profile.get("goal_text") and not profile.get("goal_weight_kg"):
            topics.append(("goal", "main goal (e.g. lose weight, how much, by when)"))
        if not store.metric_series("weight_kg", "2000-01-01"):
            topics.append(("weight", "current weight"))
        keys = {f["key"] for f in store.active_facts()}
        declared = profile.get("usual_meals") or {}
        if len(declared) < 2 and len(habitual_meals(store, today)) < 2:
            topics.append(("usual_meals", "what they USUALLY eat for breakfast/lunch/dinner (so evening check-ins become one tap)"))
        if not any(k.startswith("work") for k in keys):
            topics.append(("work_routine", "work routine (WFH/office, usual hours)"))
        if not store.active_commitments() and len(store.inbound_times_since("2000-01-01")) >= 5:
            topics.append(("first_habit", "one small daily habit they'd like help with (e.g. a 10-min walk after lunch)"))
        # Never nag: each topic is asked at most twice in 14 days, and not on consecutive days.
        recent = [r for r in store.interventions_since(days_ago(today, 14)) if (r["intent"] or "").startswith("onboarding:")]
        for key, ask in topics:
            asked = [r for r in recent if r["intent"] == f"onboarding:{key}"]
            if len(asked) >= 2 or any(r["local_date"] >= days_ago(today, 2) for r in asked):
                continue
            return Candidate(
                intent=f"onboarding:{key}",
                priority=30,
                why="Profile is missing basics the coach needs",
                facts={"ask_about": ask, "still_missing": [a for _, a in topics]},
                fallback_text=f"Quick one so I can help better: {ask}?",
            )
        return None

    def _stale_sync(self, now, today, t) -> Candidate | None:
        last = self.store.last_health_sync()
        if not last or now - parse_iso(last) < timedelta(hours=30):
            return None
        if not in_window(t, parse_hhmm("10:00"), parse_hhmm("19:00")):
            return None
        for offset in range(0, 3):
            if any(r["intent"] == "stale_sync" for r in self.store.interventions_on(days_ago(today, offset))):
                return None
        hours = (now - parse_iso(last)).total_seconds() / 3600
        return Candidate(
            intent="stale_sync",
            priority=20,
            why=f"Health Connect hasn't synced for {hours:.0f} h (steps/sleep now unknown)",
            facts={"hours_since_sync": round(hours)},
            fallback_text="Your phone hasn't synced steps for over a day - open the Health Connect Webhook app once?",
        )

    # ================================================================ gating
    def reserved_slots(self, now: datetime, profile: dict, engagement: Engagement) -> int:
        """High-value messages still possible later today (commitment windows, recap)."""
        store = self.store
        today = store._date(now)
        hhmm = local_time(now, store.tz).strftime("%H:%M")
        todays = store.interventions_on(today)
        reserved = 0
        for c in store.active_commitments():
            if not c.window_start or not is_scheduled_on(c, date.fromisoformat(today)):
                continue
            if c.window_end <= hhmm or store.commitment_outcome_on(c.id, today) in {"done", "smaller", "skipped"}:
                continue
            if any(r["commitment_id"] == c.id and r["kind"] in {"scheduled", "follow_up"} for r in todays):
                continue
            reserved += 1
        recap_start = profile.get("recap_time") or "21:00"
        recap_allowed = engagement.allowed_intents is None or "recap" in engagement.allowed_intents
        if recap_allowed and hhmm < recap_start and not any(r["intent"] == "recap" for r in todays):
            reserved += 1
        return reserved

    def gate(
        self, candidates: list[Candidate], engagement: Engagement, now: datetime, budget_left: int | None = None,
        reserved: int = 0,
    ) -> tuple[list[Candidate], list[str]]:
        kept, dropped = [], []
        for c in candidates:
            fam = c.family
            if budget_left is not None and c.priority < 70 and budget_left - reserved <= 0:
                dropped.append(f"{c.intent}: budget kept for higher-value messages later today ({reserved} reserved)")
                continue
            if engagement.allowed_intents is not None and fam not in engagement.allowed_intents:
                dropped.append(f"{c.intent}: not allowed while {engagement.state}")
                continue
            if fam in engagement.retired_intents:
                dropped.append(f"{c.intent}: message type retired (no responses recently)")
                continue
            if self.store.job_done("brain_silence", f"{c.intent}|{self.store._date(now)}|{now.hour // 2}"):
                dropped.append(f"{c.intent}: LLM chose silence recently")
                continue
            kept.append(c)
        return kept, dropped

    # ================================================================ decide
    async def decide(self, candidates: list[Candidate], digest: dict, now: datetime) -> tuple[Candidate | None, str, str, str | None]:
        """Returns (candidate or None, message, reason, proposed_rule)."""
        shortlist = candidates[:3]
        prompt = (
            DECIDE_INSTRUCTIONS
            + "\nDIGEST (computed by code; trust it):\n"
            + json.dumps(digest, ensure_ascii=False, default=str, indent=1)
            + "\n\nCANDIDATES:\n"
            + json.dumps([c.for_llm(i) for i, c in enumerate(shortlist)], ensure_ascii=False, default=str, indent=1)
        )
        request = LLMRequest(
            system=COACH_SYSTEM, user_text=prompt, json_schema=DECIDE_SCHEMA, temperature=0.5, max_output_tokens=4096, purpose="decide"
        )
        try:
            raw = (await self.llm.generate(request)).json()
            choice = int(raw.get("choice", -1))
            reason = str(raw.get("reason") or "")[:300]
        except (LLMError, ValueError, TypeError) as exc:
            top = shortlist[0]
            return top, top.fallback_text, f"LLM unavailable ({exc.__class__.__name__}); template for top candidate", None
        if choice < 0 or choice >= len(shortlist):
            for c in shortlist:
                self.store.mark_job(now, "brain_silence", f"{c.intent}|{self.store._date(now)}|{now.hour // 2}")
            return None, "", reason or "LLM chose silence", None
        chosen = shortlist[choice]
        message = str(raw.get("message") or "").strip()
        proposed_rule = str(raw.get("proposed_rule") or "").strip() or None
        if not message or not check_message(message).ok:
            violations = check_message(message).violations if message else ["empty"]
            self.store.log_decision(now, "safety_rewrite", "Proactive message replaced by template", {"violations": violations})
            message = chosen.fallback_text
        return chosen, message, reason, proposed_rule
