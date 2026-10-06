"""CoachService: the orchestration layer used by every channel.

Inbound message  -> extract -> validate -> store -> deterministic guidance -> coach reply
Scheduler tick   -> expire nudges -> policy gate -> ladder -> slot learner -> nudge
Button press     -> record outcome -> update learner
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

from ..analytics.progress import can_adjust_targets, day_totals, weight_trend
from ..llm.base import CapabilityError, ChatTurn, LLMError, LLMProvider, MediaPart
from ..memory.store import COACHING_MODES, Commitment, Store, normalize_tag  # noqa: F401 (re-exported)
from ..nutrition.estimator import FoodTable, estimate
from ..timeutil import (
    days_ago,
    in_window,
    local_time,
    parse_hhmm,
    parse_iso,
    slot_of,
    to_iso,
)
from ..analytics.habits import coverage_stats, day_coverage, estimated_day_intake, habitual_meals
from ..analytics.patterns import mine_patterns
from .brain import MEAL_SLOT_TIMES, Brain
from .coach import Coach
from .engagement import PROACTIVE_KINDS, compute_engagement
from .extractor import FOCUSED_SCHEMAS, Extraction, Extractor, focused_extract
from .policy import (
    FAILURE_STATUSES,
    SUCCESS_STATUSES,
    SlotLearner,
    analyse_commitment,
    decide_level,
    effective_budget,
    is_scheduled_on,
)

log = logging.getLogger(__name__)

REVIEW_WEEKDAY = 6  # Sunday
REVIEW_WINDOW = ("19:00", "21:30")
SNOOZE_MINUTES = 60


@dataclass
class InboundMessage:
    text: str
    received_at: datetime
    kind: str = "text"  # text | voice | photo
    media: list[MediaPart] = field(default_factory=list)
    external_id: str | None = None


@dataclass
class OutboundMessage:
    text: str
    buttons: list[tuple[str, str]] = field(default_factory=list)  # (label, callback_data)
    intervention_id: int | None = None


NUDGE_BUTTONS = [("Done", "done"), ("Smaller version", "smaller"), ("Later", "later"), ("Skip today", "skip")]


def nudge_buttons(intervention_id: int) -> list[tuple[str, str]]:
    return [(label, f"iv:{intervention_id}:{action}") for label, action in NUDGE_BUTTONS]


class CoachService:
    def __init__(
        self,
        store: Store,
        llm: LLMProvider,
        food_table: FoodTable | None = None,
        learner: SlotLearner | None = None,
        ignore_after_minutes: int = 120,
        min_gap_minutes: int = 90,
    ):
        self.store = store
        self.llm = llm
        self.food_table = food_table or FoodTable.load()
        self.extractor = Extractor(llm, self.food_table)
        self.coach = Coach(llm)
        self.learner = learner or SlotLearner(store)
        self.brain = Brain(store, llm, self.learner)
        self.ignore_after = timedelta(minutes=ignore_after_minutes)
        self.min_gap_minutes = min_gap_minutes

    # ================================================================ context
    def build_context(self, now: datetime) -> dict:
        store = self.store
        today = store._date(now)
        profile = store.get_profile()
        totals = day_totals(
            store.food_for_date(today), store.activities_for_date(today), store.health_daily_sum("steps", today)
        )
        trend = weight_trend(store.metric_series("weight_kg", days_ago(today, 60)), today)
        last_sync = store.last_health_sync()
        hc_status = "never synced"
        if last_sync:
            age_h = (now - parse_iso(last_sync)).total_seconds() / 3600
            hc_status = f"last sync {age_h:.1f} h ago" + (" (stale)" if age_h > 6 else "")
        facts = [
            {"category": f["category"], "key": f["key"], "value": f["value"], "source": f["source"]}
            for f in store.active_facts()
        ]
        sleep_h = store.health_daily_sum("sleep", today)
        exercise_min = store.health_daily_sum("exercise", today)
        return {
            "last_7_days": self.week_summary(now),
            "local_time": now.astimezone(_tz(store.tz)).strftime("%a %Y-%m-%d %H:%M"),
            "coaching_mode": profile["coaching_mode"],
            "targets": {"kcal": profile.get("kcal_target"), "protein_g": profile.get("protein_target_g")},
            "goal_weight_kg": profile.get("goal_weight_kg"),
            "never_do": profile.get("never_do") or [],
            "known_facts": facts,
            "active_commitments": [c.as_context() for c in store.active_commitments()],
            "today": totals.as_context()
            | {
                "sleep_hours_last_night": round(sleep_h, 1) if sleep_h else "unknown",
                "exercise_minutes_health_connect": exercise_min if exercise_min else "unknown",
            },
            "weight": {
                "trend_kg": trend.latest_trend,
                "latest_raw_kg": trend.latest_raw,
                "avg_7d": trend.avg_7d,
                "weekly_rate_pct": trend.weekly_rate_pct,
                "status": trend.status,
                "notes": trend.notes,
            },
            "health_connect": hc_status,
            "meal_coverage_today": day_coverage(store, today),
            "pending_inferred_events": [r["summary"] for r in store.pending_inferred(days_ago(today, 1))][:5],
            "observed_patterns": [{"id": p["id"], "claim": p["claim"]} for p in store.patterns("active")][:8],
            "calendar_today": store.calendar_day(today),
            "data_labels": "observed = device data; estimated = computed ranges; inferred = from patterns; unknown = no data",
        }

    def _recent_onboarding_topic(self, now: datetime) -> str | None:
        """Topic of an onboarding question sent in the last 12 h that is still unanswered or just answered."""
        for row in reversed(self.store.interventions_since(days_ago(self.store._date(now), 1))):
            intent = row["intent"] or ""
            if intent.startswith("onboarding:") and now - parse_iso(row["sent_at"]) < timedelta(hours=12):
                return intent.split(":", 1)[1]
        return None

    def week_summary(self, now: datetime) -> list[dict]:
        """Compact per-day facts for the last 7 days, so history questions don't depend on the extractor."""
        store = self.store
        today = store._date(now)
        out = []
        for i in range(1, 8):
            d = days_ago(today, i)
            rows = store.food_for_date(d)
            cov = day_coverage(store, d)
            steps = store.health_daily_sum("steps", d)
            entry = {"date": d, "weekday": date.fromisoformat(d).strftime("%a")}
            if rows:
                lo = sum(r["kcal_low"] or 0 for r in rows)
                hi = sum(r["kcal_high"] or 0 for r in rows)
                entry["food"] = [r["item_name"] for r in rows][:8]
                entry["kcal_recorded"] = [round(lo), round(hi)]
            entry["meals_missing"] = cov["missing"]
            if steps is not None:
                entry["steps"] = int(steps)
            outcomes = store.conn.execute(
                "SELECT c.title, l.outcome FROM commitment_log l JOIN commitments c ON c.id = l.commitment_id WHERE l.local_date = ?",
                (d,),
            ).fetchall()
            if outcomes:
                entry["commitments"] = {o["title"]: o["outcome"] for o in outcomes}
            w = store.metric_series("weight_kg", d)
            if w and w[0][0] == d:
                entry["weight_kg"] = w[0][1]
            out.append(entry)
        return out

    def _history(self, limit: int = 10) -> list[ChatTurn]:
        turns = []
        for row in self.store.recent_messages(limit):
            turns.append(ChatTurn(role="user" if row["direction"] == "in" else "assistant", text=row["text"]))
        return turns

    # ========================================================= inbound message
    async def handle_message(self, msg: InboundMessage) -> list[OutboundMessage]:
        store, now = self.store, msg.received_at
        if msg.external_id and store.message_exists(msg.external_id):
            return []  # duplicate delivery
        history = self._history()
        stored_text = msg.text or f"[{msg.kind}]"

        commitments = store.active_commitments()
        open_nudges = [
            {"intervention_id": r["id"], "commitment_id": r["commitment_id"], "text": r["message_text"]}
            for r in store.open_interventions()
        ]
        try:
            extraction, _raw = await self.extractor.extract(
                text=msg.text,
                media=msg.media,
                known_tags=store.known_context_tags(),
                active_commitments=[c.as_context() for c in commitments],
                open_nudges=open_nudges,
                local_time_str=now.astimezone(_tz(store.tz)).strftime("%a %H:%M"),
                active_commitment_ids={c.id for c in commitments},
                pending_inferred=[
                    {"inferred_id": r["id"], "summary": r["summary"], "when": store.fmt_local(parse_iso(r["occurred_at"]))}
                    for r in store.pending_inferred()
                ],
                known_patterns=[{"pattern_id": p["id"], "claim": p["claim"]} for p in store.patterns("active")][:10],
                todays_food=[
                    {"item": r["item_name"], "quantity": r["quantity"], "unit": r["unit"], "meal": r["meal_slot"]}
                    for r in store.food_for_date(store._date(now))
                ],
            )
        except CapabilityError:
            store.add_message(now, "in", msg.kind, stored_text, msg.external_id)
            reply = "I can't process that kind of attachment with the current AI provider. Can you type it in a line?"
            store.log_decision(now, "capability_fallback", "Media not supported by provider; asked for text.")
            return [self._send(now, reply)]
        except LLMError as exc:
            store.log_decision(now, "extraction_failed", f"Extraction failed: {exc}")
            extraction = Extraction(dropped=["extraction failed"])

        # If the user is answering a question the coach just asked and the general extraction missed it,
        # run a small focused extraction for that one thing.
        asked = self._recent_onboarding_topic(now)
        if asked in FOCUSED_SCHEMAS and msg.text:
            have = extraction.profile_updates
            missing = (asked == "usual_meals" and "usual_meals" not in have) or (
                asked == "goal" and not ({"goal_text", "goal_weight_kg"} & set(have))
            )
            if missing:
                try:
                    extra = await focused_extract(self.llm, asked, msg.text)
                except LLMError:
                    extra = {}
                if extra:
                    extraction.profile_updates.update(extra)
                    store.log_decision(now, "focused_extract", f"recovered {asked} from an answer to the coach's question")

        outbound: list[OutboundMessage] = []
        # The message row (which carries the dedupe key) and everything extracted from it are committed
        # together: a crash leaves nothing behind, so a redelivered update is processed cleanly.
        with store.transaction():
            message_id = store.add_message(now, "in", msg.kind, stored_text, msg.external_id)
            store.mark_open_interventions_answered(now)
            guidance = self._apply_extraction(now, extraction, message_id, outbound)
        data_results = self.run_queries(now, extraction.data_needed) if extraction.data_needed else None

        context = self.build_context(now)
        reply, violations = await self.coach.reply(
            user_text=msg.text or f"(sent a {msg.kind})",
            context=context,
            extraction_summary=extraction.summary(),
            history=history,
            guidance=guidance,
            data_results=data_results,
        )
        if violations:
            store.log_decision(now, "safety_rewrite", "Reply needed rewrite or fallback.", {"violations": violations})
        store.log_decision(
            now,
            "reply",
            "; ".join(guidance) if guidance else "plain acknowledgement",
            {"extraction": extraction.summary()},
        )
        outbound.insert(0, self._send(now, reply))
        return outbound

    def _send(self, now: datetime, text: str, kind: str = "text", buttons=None, intervention_id=None) -> OutboundMessage:
        self.store.add_message(now, "out", kind, text)
        return OutboundMessage(text=text, buttons=buttons or [], intervention_id=intervention_id)

    def _apply_extraction(
        self, now: datetime, ex: Extraction, message_id: int, outbound: list[OutboundMessage]
    ) -> list[str]:
        """Persist validated extraction; return deterministic guidance for the reply."""
        store = self.store
        guidance: list[str] = []
        profile = store.get_profile()

        # --- food
        unknown_foods = []
        for food in ex.foods:
            if not food.eaten:
                continue
            est = estimate(
                self.food_table,
                food.name,
                food.quantity,
                food.unit,
                llm_kcal=food.est_kcal,
                llm_protein=food.est_protein,
                extraction_confidence=food.confidence,
            )
            store.add_food_event(
                now,
                self._meal_time(now, food.meal_slot),
                meal_slot=food.meal_slot,
                item_name=food.name,
                food_key=est.food_key,
                quantity=food.quantity,
                unit=food.unit,
                kcal_low=est.kcal_low,
                kcal_high=est.kcal_high,
                protein_low=est.protein_low,
                protein_high=est.protein_high,
                nutrition_source=est.nutrition_source,
                data_status="estimated" if est.kcal_low is not None else "unknown",
                confidence=est.confidence,
                source_message_id=message_id,
            )
            if est.kcal_low is None:
                unknown_foods.append(food.name)
        if unknown_foods:
            guidance.append(f"Could not estimate nutrition for: {', '.join(unknown_foods)}. Say so honestly; don't guess.")

        # --- activity
        for act in ex.activities:
            store.add_activity(
                now,
                now,
                kind=act["kind"],
                duration_min=act["duration_min"],
                steps=act["steps"],
                source="user_reported",
                confidence=act["confidence"],
                source_message_id=message_id,
            )

        # --- body metrics
        for metric in ex.body_metrics:
            store.add_body_metric(now, now, metric["metric"], metric["value"], "user_reported")
            if metric["metric"] == "weight_kg":
                guidance.append(
                    "Weight logged. Comment on the TREND (context.weight), not this single reading; "
                    "daily swings of 1-2 kg are water/food, not fat."
                )

        # --- facts
        for fact in ex.facts:
            store.upsert_fact(now, fact["category"], fact["key"], fact["value"], "user_stated", fact["confidence"], message_id)

        # --- context + triggered commitments
        tags_now = []
        for ctx in ex.context:
            cid_ = store.add_context_event(now, ctx["tag"], ctx["timing"], ctx["description"], message_id,
                                           for_tomorrow=ctx.get("day") == "tomorrow")
            if ctx.get("time_hint"):
                store.conn.execute("UPDATE context_events SET time_hint = ? WHERE id = ?", (ctx["time_hint"], cid_))
            if ctx["timing"] in {"now", "planned"} and ctx.get("day") != "tomorrow":
                tags_now.append(ctx["tag"])
        today = store._date(now)
        already = {(r["commitment_id"]) for r in store.interventions_on(today) if r["kind"] == "contextual"}
        for commitment in store.commitments_for_tags(tags_now):
            if commitment.id in already:
                continue
            guidance.append(
                f"The user is entering a situation covered by their own rule (commitment {commitment.id}): "
                f"'{commitment.user_words or commitment.action}'. Remind them of THEIR rule in one line, before the decision."
            )
            store.add_intervention(
                now,
                commitment_id=commitment.id,
                kind="contextual",
                level=2,
                slot=slot_of(local_time(now, store.tz)),
                style="own_rule_reminder",
                version_offered=commitment.versions[0],
                message_text=f"(in reply) reminder of rule: {commitment.action}",
                reason=f"context tag '{commitment.trigger_tag}' matched an active if-then commitment",
            )

        # --- new commitments
        for com in ex.commitments:
            strong = com["strong_requested"] and profile.get("strong_mode_authorized")
            cid = store.add_commitment(
                now,
                kind=com["kind"],
                title=com["title"],
                trigger_tag=com["trigger_tag"],
                action=com["action"],
                versions=com["versions"],
                schedule_days=com["schedule_days"],
                window_start=com["window_start"],
                window_end=com["window_end"],
                enforcement="strong" if strong else "normal",
                user_words=com["user_words"],
                source_message_id=message_id,
                activity_kind=com.get("activity_kind"),
            )
            note = f"Saved commitment {cid}: '{com['title']}'. Confirm it back in one line"
            if com["window_start"]:
                note += f" (check-ins between {com['window_start']}-{com['window_end']})"
            guidance.append(note + ".")
            if com["strong_requested"] and not strong:
                guidance.append(
                    "User asked for strong enforcement, but strong accountability mode is not enabled. "
                    "Tell them they can turn it on with /mode strong."
                )

        # --- commitment updates / nudge responses
        for upd in ex.commitment_updates:
            self._record_commitment_outcome(
                now, upd["commitment_id"], upd["outcome"], "user_reported", upd["version"], upd["reason_category"]
            )
            guidance.append(self._outcome_guidance(upd))

        # --- corrections to today's food
        for corr in ex.food_corrections:
            guidance.append(self._apply_food_correction(now, corr))

        # --- commitment edits
        for change in ex.commitment_changes:
            ch = dict(change)
            cid = ch.pop("commitment_id")
            store.update_commitment(now, cid, **ch)
            guidance.append(f"Commitment {cid} updated ({', '.join(ch)}). Confirm the change in one line.")

        # --- answers about inferred events
        for conf in ex.inferred_confirmations:
            row = store.get_inferred(conf["inferred_id"])
            if row is None or row["status"] != "pending":
                continue
            store.resolve_inferred(now, row["id"], "confirmed" if conf["is_food"] else "rejected")
            if row["payee_key"]:
                store.set_payee_label(now, row["payee_key"], conf["is_food"], conf.get("label"))
            if conf["is_food"] and not ex.foods:
                self._log_inferred_food(now, row)

        # --- profile
        if ex.profile_updates:
            updates = dict(ex.profile_updates)
            if "usual_meals" in updates:
                updates["usual_meals"] = {**(profile.get("usual_meals") or {}), **updates["usual_meals"]}
            store.set_profile(now, **updates)
            guidance.append(f"Saved to profile: {ex.profile_updates}.")

        # --- pattern feedback (the user is the authority on their own patterns)
        for fb in ex.pattern_feedback:
            exists = store.conn.execute("SELECT 1 FROM patterns WHERE id = ?", (fb["pattern_id"],)).fetchone()
            if exists and not fb["correct"]:
                store.set_pattern_status(now, fb["pattern_id"], "rejected")
                guidance.append("User said that observed pattern is wrong; it's removed. Acknowledge briefly.")

        # --- lapse protocol
        if ex.lapse:
            line = (
                "Lapse protocol: acknowledge without judgement, no compensation (no skipping meals or extra punishment), "
                "name the very next normal action."
            )
            if ex.all_or_nothing:
                line += " The user shows all-or-nothing thinking: explicitly counter it ('the day isn't ruined; next meal is normal')."
            guidance.append(line)
            store.log_decision(now, "lapse", ex.lapse_note or "lapse reported", {"all_or_nothing": ex.all_or_nothing})

        # --- settings requests
        if ex.pause_days:
            until = now + timedelta(days=ex.pause_days)
            store.set_profile(now, paused_until=to_iso(until))
            guidance.append(f"Coach paused until {store.fmt_local(until)} as requested. Confirm; mention /resume.")
        if ex.coaching_mode_request:
            if ex.coaching_mode_request == "strong":
                guidance.append("User wants strong accountability. Ask them to confirm with the button below.")
                outbound.append(
                    OutboundMessage(
                        text="Strong accountability: I will push for at least the minimum version and ask for a reason "
                        "before you drop a commitment. You can turn it off any time with /mode normal. Enable it?",
                        buttons=[("Yes, enable", "mode:strong:confirm"), ("No", "mode:strong:cancel")],
                    )
                )
            else:
                self.set_mode(now, ex.coaching_mode_request)
                guidance.append(f"Coaching mode set to {ex.coaching_mode_request}. Confirm in one line.")
        return guidance

    @staticmethod
    def _outcome_guidance(upd: dict) -> str:
        outcome, reason = upd["outcome"], upd.get("reason_category")
        if outcome in {"done", "smaller"}:
            return f"Commitment {upd['commitment_id']} {outcome}. Acknowledge briefly; a smaller version still counts."
        if outcome == "postponed":
            return "User postponed. Agree on a specific time or the smaller version now; keep it light."
        hint = {
            "cannot": "Genuinely unable: accept it and help reschedule. No pushing.",
            "forgot": "Forgot: suggest anchoring it to an existing routine or a better time.",
            "dont_want": "Doesn't want to: offer the smallest version; ask what makes it unappealing only if this keeps repeating.",
            "too_hard": "Too hard: offer a smaller version and consider shrinking the default.",
            "bad_timing": "Timing is the problem: propose a different time.",
        }.get(reason or "", "Reason unclear: ask one short question about what got in the way, only if useful.")
        return f"Commitment {upd['commitment_id']} skipped. {hint}"

    def _record_commitment_outcome(
        self,
        now: datetime,
        commitment_id: int,
        outcome: str,
        source: str,
        version: str | None = None,
        reason_category: str | None = None,
        local_date: str | None = None,
    ) -> None:
        self.store.log_commitment(now, commitment_id, outcome, source, version, reason_category, local_date)
        status = {"done": "acted", "smaller": "smaller", "skipped": "skipped", "postponed": "snoozed"}[outcome]
        for row in self.store.open_interventions():
            if row["commitment_id"] == commitment_id:
                self._resolve_intervention(now, row, status, reason_category)

    def _resolve_intervention(self, now: datetime, row, status: str, reason_category: str | None = None) -> None:
        self.store.update_intervention(now, row["id"], status, reason_category)
        if row["kind"] != "scheduled" or not row["slot"]:
            return
        if status in SUCCESS_STATUSES:
            self.learner.update(now, row["commitment_id"], row["slot"], 1.0)
        elif status in FAILURE_STATUSES:
            self.learner.update(now, row["commitment_id"], row["slot"], 0.0)

    # ======================================================= extraction helpers
    def _meal_time(self, now: datetime, slot: str | None) -> datetime:
        """Attribute food to its meal time when the user reports it later the same day."""
        if slot not in MEAL_SLOT_TIMES:
            return now
        local = now.astimezone(_tz(self.store.tz))
        if slot == "dinner" and local.hour < 4:  # "dinner me ..." sent after midnight belongs to the previous day
            return (local - timedelta(days=1)).replace(hour=21, minute=0, second=0, microsecond=0)
        meal = parse_hhmm(MEAL_SLOT_TIMES[slot])
        candidate = local.replace(hour=meal.hour, minute=meal.minute, second=0, microsecond=0)
        return candidate if candidate <= local else now

    def _apply_food_correction(self, now: datetime, corr: dict) -> str:
        store = self.store
        today = store._date(now)
        name = corr["item_name"].lower()
        row_key, _ = self.food_table.match(corr["item_name"])
        candidates = [
            r for r in reversed(store.food_for_date(today))
            if r["item_name"].lower() == name or (row_key and r["food_key"] == row_key.key)
        ]
        if not candidates:
            return f"User corrected '{corr['item_name']}' but it isn't in today's log; say so briefly."
        target = candidates[0]
        if corr["new_quantity"] == 0:
            store.delete_food_event(target["id"])
            return f"Removed '{target['item_name']}' from today's log as corrected."
        est = estimate(self.food_table, target["item_name"], corr["new_quantity"], target["unit"])
        values = {"quantity": corr["new_quantity"]}
        if est.kcal_low is not None and target["nutrition_source"] == "food_table":
            values |= {"kcal_low": est.kcal_low, "kcal_high": est.kcal_high, "protein_low": est.protein_low, "protein_high": est.protein_high}
        elif target["quantity"] and target["kcal_low"] is not None:
            factor = corr["new_quantity"] / target["quantity"]
            values |= {k: round(target[k] * factor, 1) for k in ("kcal_low", "kcal_high", "protein_low", "protein_high") if target[k] is not None}
        store.update_food_event(target["id"], **values)
        return f"Corrected '{target['item_name']}' to {corr['new_quantity']:g}. Confirm briefly."

    def _already_logged_near(self, row) -> bool:
        """True if the user already logged food that this passive signal probably describes."""
        occurred = parse_iso(row["occurred_at"])
        window = timedelta(minutes=90) if row["kind"] == "small_payment" else timedelta(hours=3)
        for f in self.store.food_for_date(row["local_date"]):
            if abs(parse_iso(f["occurred_at"]) - occurred) <= window:
                if row["kind"] == "small_payment" and f["meal_slot"] in ("snack", "drink", "unknown"):
                    return True
                if row["kind"] == "food_order" and f["meal_slot"] in ("lunch", "dinner", "unknown"):
                    return True
        return False

    def _log_inferred_food(self, now: datetime, row, description: str | None = None, source: str = "inferred_confirmed",
                           confidence: float = 0.4) -> bool:
        if self._already_logged_near(row):
            self.store.log_decision(now, "inferred_merged", f"inferred {row['kind']} {row['id']} matches food already logged; not double-counted")
            return False
        name = description or ("street snack" if row["kind"] == "small_payment" else "restaurant meal")
        est = estimate(self.food_table, name, 1, None)
        occurred = parse_iso(row["occurred_at"])
        hour = occurred.astimezone(_tz(self.store.tz)).hour
        slot = "snack" if row["kind"] == "small_payment" else ("lunch" if hour < 16 else "dinner")
        self.store.add_food_event(
            now, occurred, meal_slot=slot, item_name=name, food_key=est.food_key, quantity=1, unit=None,
            kcal_low=est.kcal_low, kcal_high=est.kcal_high, protein_low=est.protein_low, protein_high=est.protein_high,
            nutrition_source=est.nutrition_source,
            data_status=("inferred" if source != "inferred_confirmed" else "estimated") if est.kcal_low is not None else "unknown",
            confidence=confidence, source=source,
        )
        return True

    # ============================================================ data queries
    def run_queries(self, now: datetime, queries: list[dict]) -> dict:
        """Answer the LLM's data requests from the database (numbers computed here, not by the LLM)."""
        store = self.store
        today = store._date(now)
        results: dict = {}
        for q in queries:
            days = q["days"]
            end_day = days_ago(today, q.get("offset_days") or 0)
            since = days_ago(end_day, days - 1)
            today_saved, today = today, end_day
            name = q["query"]
            if name == "food_log":
                by_day: dict[str, dict] = {}
                for r in store.food_between(since, today):
                    d = by_day.setdefault(r["local_date"], {"items": [], "kcal_range": [0, 0], "protein_range": [0, 0], "unestimated": 0})
                    d["items"].append(f"{r['quantity'] or ''} {r['unit'] or ''} {r['item_name']} ({r['meal_slot']}, {r['source']})".replace("  ", " ").strip())
                    if r["kcal_low"] is None:
                        d["unestimated"] += 1
                    else:
                        d["kcal_range"][0] += round(r["kcal_low"]); d["kcal_range"][1] += round(r["kcal_high"])
                        d["protein_range"][0] += round(r["protein_low"] or 0); d["protein_range"][1] += round(r["protein_high"] or 0)
                for d, v in by_day.items():
                    v["meals_missing"] = day_coverage(store, d)["missing"]
                    v["estimate_incl_assumed_usual"] = estimated_day_intake(store, d, self.food_table)
                results[f"food_log_{days}d"] = by_day or "no food logged in this period"
            elif name == "weight":
                series = store.metric_series("weight_kg", since)
                trend = weight_trend(store.metric_series("weight_kg", days_ago(today, 60)), today)
                results["weight"] = {"readings": series, "trend_kg": trend.latest_trend, "weekly_rate_pct": trend.weekly_rate_pct, "status": trend.status}
            elif name in {"steps", "sleep"}:
                rows = {days_ago(today, i): store.health_daily_sum(name, days_ago(today, i)) for i in range(days)}
                known = {k: round(v, 1) for k, v in rows.items() if v is not None}
                results[name] = {"by_day": known, "days_without_data": days - len(known),
                                 "average": round(sum(known.values()) / len(known), 1) if known else "unknown"}
            elif name == "commitment_history":
                out = {}
                for c in store.active_commitments():
                    if q["commitment_id"] and c.id != q["commitment_id"]:
                        continue
                    out[c.title] = [{"date": r["local_date"], "outcome": r["outcome"], "reason": r["reason_category"]}
                                    for r in store.commitment_history(c.id, since)]
                results["commitment_history"] = out or "no commitments"
            elif name == "patterns":
                results["patterns"] = [{"id": p["id"], "claim": p["claim"], "status": p["status"]} for p in store.patterns(None)][:12]
            elif name == "search_messages" and q["term"]:
                results["messages"] = [{"at": store.fmt_local(parse_iso(m["created_at"])), "text": m["text"][:160]}
                                       for m in store.search_messages(q["term"], 8)]
            today = today_saved
        store.log_decision(now, "data_query", f"answered {[q['query'] for q in queries]}")
        return results

    # ================================================================ buttons
    async def handle_button(self, data: str, now: datetime) -> OutboundMessage | None:
        parts = data.split(":")
        if parts[0] in {"rc", "inf", "re", "pc"} and len(parts) == 3:
            return self._handle_v2_button(parts[0], parts[1], parts[2], now)
        if parts[0] == "mode" and len(parts) == 3:
            if parts[2] == "confirm":
                self.store.set_profile(now, strong_mode_authorized=True)
                self.set_mode(now, "strong")
                return self._send(now, "Strong accountability is on. /mode normal turns it off any time.", "system")
            return self._send(now, "Okay, keeping the current mode.", "system")
        if parts[0] != "iv" or len(parts) != 3:
            return None
        try:
            intervention_id = int(parts[1])
        except ValueError:
            return None
        row = self.store.get_intervention(intervention_id)
        if row is None:
            return None
        action = parts[2]
        commitment = self.store.get_commitment(row["commitment_id"]) if row["commitment_id"] else None
        if action in {"done", "smaller"} and row["status"] in SUCCESS_STATUSES:
            return self._send(now, "Already noted.", "system")  # double tap
        day = row["local_date"]  # outcomes belong to the day of the nudge, even if the tap is late
        if action in {"done", "smaller"}:
            # Completion is accepted even after the nudge expired ("ignored") - the user still did it.
            # Late presses do not update the slot learner: the nudge did not work at its own time.
            outcome = "done" if action == "done" else "smaller"
            version = row["version_offered"] if action == "done" or not commitment else commitment.versions[-1]
            if commitment:
                # Also resolves this intervention (and updates the learner) if it is still open.
                self._record_commitment_outcome(now, commitment.id, outcome, "button", version, local_date=day)
            elif row["status"] == "sent":
                self._resolve_intervention(now, row, "acted" if action == "done" else "smaller")
            elif row["status"] not in SUCCESS_STATUSES:
                self.store.update_intervention(now, row["id"], "acted" if action == "done" else "smaller")
            if action == "done":
                return self._send(now, "Logged. Nice.", "system")
            return self._send(now, f"Logged the smaller version: {version}. That still counts.", "system")
        if row["status"] != "sent":
            return self._send(now, "Already noted.", "system")
        if action == "later":
            self._resolve_intervention(now, row, "snoozed")
            if commitment:
                self.store.log_commitment(now, commitment.id, "postponed", "button", local_date=day)
            return self._send(now, f"Okay. I'll check once more in about {SNOOZE_MINUTES} minutes.", "system")
        if action == "skip":
            self._resolve_intervention(now, row, "skipped")
            if commitment:
                self.store.log_commitment(now, commitment.id, "skipped", "button", local_date=day)
            return OutboundMessage(
                text="Noted. What got in the way today?",
                buttons=[
                    ("Couldn't today", f"rs:{intervention_id}:cannot"),
                    ("Forgot", f"rs:{intervention_id}:forgot"),
                    ("Didn't feel like it", f"rs:{intervention_id}:dont_want"),
                    ("Bad time", f"rs:{intervention_id}:bad_timing"),
                ],
            )
        return None

    def _handle_v2_button(self, prefix: str, ident: str, action: str, now: datetime) -> OutboundMessage | None:
        store = self.store
        try:
            ref = int(ident)
        except ValueError:
            return None
        if prefix == "inf":
            row = store.get_inferred(ref)
            if row is None or row["status"] != "pending":
                return self._send(now, "Already noted.", "system")
            is_food = action == "yes"
            with store.transaction():
                store.resolve_inferred(now, ref, "confirmed" if is_food else "rejected")
                if row["payee_key"] and row["kind"] == "small_payment":
                    store.set_payee_label(now, row["payee_key"], is_food)
                logged = self._log_inferred_food(now, row) if is_food else False
            if is_food and not logged:
                return self._send(now, "Got it - that matches what you already told me, so I didn't count it twice.", "system")
            if is_food:
                return self._send(now, f"Logged {row['summary']} as food (rough estimate). Tell me what it was if you want it exact.", "system")
            return self._send(now, "Got it - I'll ignore payments like that from now on." if row["payee_key"] else "Got it.", "system")

        row = store.get_intervention(ref)
        if row is None:
            return None
        if row["status"] == "acted" or now - parse_iso(row["sent_at"]) > timedelta(hours=24):
            return self._send(now, "Already noted.", "system")  # one tap per prompt; stale prompts expire after a day
        store.update_intervention(now, ref, "acted")
        day = row["local_date"]
        if prefix == "rc":
            if action in {"usual", "usual_snack"}:
                weekend = date.fromisoformat(day).weekday() >= 5
                habits = habitual_meals(store, day, weekend=weekend) or habitual_meals(store, day)
                logged = []
                order_slots = set()
                for inf in store.pending_inferred(day):
                    if inf["kind"] == "food_order" and inf["local_date"] == day:
                        hour = parse_iso(inf["occurred_at"]).astimezone(_tz(store.tz)).hour
                        order_slots.add("lunch" if hour < 16 else "dinner")
                with store.transaction():
                    for slot in day_coverage(store, day)["missing"]:
                        meal = habits.get(slot)
                        if not meal or slot in order_slots:
                            continue
                        for item in meal.items:
                            est = estimate(self.food_table, item["name"], item["quantity"], item["unit"])
                            meal_time = parse_hhmm(MEAL_SLOT_TIMES[slot])
                            local = parse_iso(row["sent_at"]).astimezone(_tz(store.tz)).replace(hour=meal_time.hour, minute=meal_time.minute)
                            store.add_food_event(
                                now, local, meal_slot=slot, item_name=item["name"], food_key=est.food_key,
                                quantity=item["quantity"], unit=item["unit"], kcal_low=est.kcal_low, kcal_high=est.kcal_high,
                                protein_low=est.protein_low, protein_high=est.protein_high, nutrition_source=est.nutrition_source,
                                data_status="estimated" if est.kcal_low is not None else "unknown",
                                confidence=round(est.confidence * 0.7, 2), source="default_confirmed",
                            )
                        logged.append(f"{slot}: {meal.describe()}")
                    if action == "usual_snack":
                        est = estimate(self.food_table, "street snack", 1, None)
                        store.add_food_event(
                            now, parse_iso(row["sent_at"]), meal_slot="snack", item_name="outside snack", food_key=est.food_key,
                            quantity=1, unit=None, kcal_low=est.kcal_low, kcal_high=est.kcal_high, protein_low=est.protein_low,
                            protein_high=est.protein_high, nutrition_source=est.nutrition_source, data_status="estimated",
                            confidence=0.4, source="default_confirmed",
                        )
                        logged.append("snack: outside snack (rough)")
                text = ("Logged as usual - " + "; ".join(logged)) if logged else "Nothing to fill from your usual meals yet."
                return self._send(now, text + ". Different? Just tell me.", "system")
            if action == "offplan":
                store.log_decision(now, "lapse", "user marked the day off-plan via recap", {"source": "recap_button"})
                return self._send(now, "Okay, noted - one off day doesn't change the trend. Tomorrow starts normal. "
                                       "If you send one line on what it was, the log gets more accurate.", "system")
            return self._send(now, "Okay, skipped for today.", "system")
        if prefix == "re":
            if action == "pause":
                until = self.pause(now, 7)
                return self._send(now, f"Paused until {store.fmt_local(until)}. /resume any time.", "system")
            if action == "off":
                return self._send(now, "Thanks for being straight. No catch-up needed - want me to keep just one small "
                                       "thing for this week? Reply with what feels doable (e.g. 10 min walk).", "system")
            return self._send(now, "Good to hear. I'll keep it light.", "system")
        if prefix == "pc":
            if action == "yes":
                facts_tag = (row["intent"] or "").split(":", 1)[1] if ":" in (row["intent"] or "") else None
                rule = row["version_offered"] or "Keep it light"
                cid = store.add_commitment(
                    now, kind="precommitment", title=rule[:80], trigger_tag=facts_tag, action=rule,
                    versions=[rule], enforcement="normal", user_words=rule,
                )
                store.log_decision(now, "commitment_from_pattern", f"Created commitment {cid} for '{facts_tag}' from an observed pattern")
                return self._send(now, f"Saved: {rule}. I'll remind you when it's relevant.", "system")
            return self._send(now, "No problem.", "system")
        return None

    async def handle_reason(self, data: str, now: datetime) -> OutboundMessage | None:
        parts = data.split(":")
        if parts[0] != "rs" or len(parts) != 3:
            return None
        try:
            row = self.store.get_intervention(int(parts[1]))
        except ValueError:
            return None
        if row is None:
            return None
        reason = parts[2]
        self.store.update_intervention(now, row["id"], row["status"], reason)
        if row["commitment_id"] and not self.store.set_latest_skip_reason(row["commitment_id"], row["local_date"], reason):
            self.store.log_commitment(
                now, row["commitment_id"], "skipped", "button", reason_category=reason, local_date=row["local_date"]
            )
        follow = {
            "cannot": "Fair. We'll pick it up tomorrow.",
            "forgot": "Got it. I'll try a different time so it's easier to remember.",
            "dont_want": "Understood. Tomorrow I'll offer a smaller default so starting is easier.",
            "bad_timing": "Got it - I'll shift the timing.",
        }.get(reason, "Noted.")
        return self._send(now, follow, "system")

    # ============================================================== settings
    def set_mode(self, now: datetime, mode: str) -> None:
        if mode not in COACHING_MODES:
            raise ValueError(mode)
        profile = self.store.get_profile()
        if mode == "strong" and not profile.get("strong_mode_authorized"):
            raise PermissionError("Strong mode requires explicit confirmation")
        self.store.set_profile(now, coaching_mode=mode)
        if mode != "strong":
            self.store.set_profile(now, strong_mode_authorized=False)
        self.store.log_decision(now, "mode_change", f"coaching mode -> {mode}")

    def pause(self, now: datetime, days: float) -> datetime:
        until = now + timedelta(days=days)
        self.store.set_profile(now, paused_until=to_iso(until))
        self.store.log_decision(now, "pause", f"paused for {days} days")
        return until

    def resume(self, now: datetime) -> None:
        self.store.set_profile(now, paused_until=None)
        self.store.log_decision(now, "resume", "coach resumed")

    # ============================================================== scheduler
    async def tick(self, now: datetime) -> list[OutboundMessage]:
        """Scheduler entry point. Runs housekeeping, then lets the brain decide on at most one message."""
        store = self.store
        self._expire_interventions(now)
        self._daily_jobs(now)
        self._auto_complete_from_health(now)
        self._process_inferred(now)
        profile = store.get_profile()

        # Hard gates that apply to every proactive message.
        paused_until = profile.get("paused_until")
        if paused_until and parse_iso(paused_until) > now:
            return []
        t = local_time(now, store.tz)
        if in_window(t, parse_hhmm(profile["quiet_start"]), parse_hhmm(profile["quiet_end"])):
            return []
        mode_budget = effective_budget(profile)
        engagement = compute_engagement(store, now, mode_budget)
        today = store._date(now)
        sent_today = [r for r in store.interventions_on(today) if r["kind"] in PROACTIVE_KINDS]
        if len(sent_today) >= engagement.daily_budget:
            return []
        if engagement.min_days_between:
            for offset in range(1, engagement.min_days_between):
                if any(r["kind"] in PROACTIVE_KINDS for r in store.interventions_on(days_ago(today, offset))):
                    return []
        last = store.last_proactive_sent_at()
        if last and now - parse_iso(last) < timedelta(minutes=self.min_gap_minutes):
            return []

        def allowed(family: str) -> bool:
            return (engagement.allowed_intents is None or family in engagement.allowed_intents) and family not in engagement.retired_intents

        if allowed("commitment"):
            follow_up = await self._snooze_follow_up(now, today)
            if follow_up:
                return [follow_up]
        if allowed("weekly_review"):
            review = await self._maybe_weekly_review(now)
            if review:
                return [review]

        candidates = self.brain.candidates(now, engagement, profile)
        budget_left = engagement.daily_budget - len(sent_today)
        reserved = self.brain.reserved_slots(now, profile, engagement)
        candidates, dropped = self.brain.gate(candidates, engagement, now, budget_left, reserved)
        if dropped:
            store.log_decision(now, "gate", "; ".join(dropped)[:500])
        if not candidates:
            return []
        digest = self.digest(now, engagement)
        chosen, text, reason, proposed_rule = await self.brain.decide(candidates, digest, now)
        if chosen is None:
            store.log_decision(now, "brain_silence", reason, {"candidates": [c.intent for c in candidates[:3]]})
            return []
        return [self._emit(now, chosen, text, reason, proposed_rule)]

    def _emit(self, now: datetime, c, text: str, reason: str, proposed_rule: str | None) -> OutboundMessage:
        store = self.store
        kind = "scheduled" if c.family == "commitment" else "proactive"
        iid = store.add_intervention(
            now,
            commitment_id=c.commitment_id,
            kind=kind,
            intent=c.intent,
            level=c.level,
            slot=c.slot or slot_of(local_time(now, store.tz)),
            style=c.family,
            version_offered=proposed_rule or c.version,
            message_text=text,
            reason=f"{c.why} | chosen: {reason}"[:600],
        )
        store.log_decision(now, "proactive", f"{c.intent}: {reason}", {"why": c.why})
        return self._send(now, text, "nudge", self._buttons_for(c, iid), iid)

    def _buttons_for(self, c, iid: int) -> list[tuple[str, str]]:
        fam = c.family
        if fam == "commitment":
            return nudge_buttons(iid)
        if fam == "recap":
            buttons = []
            if c.facts.get("defaults"):
                buttons.append(("All usual", f"rc:{iid}:usual"))
                if not c.facts.get("pending_inferred"):
                    buttons.append(("Usual + outside snack", f"rc:{iid}:usual_snack"))
            buttons += [("Off-plan day", f"rc:{iid}:offplan"), ("Skip today", f"rc:{iid}:skip")]
            for item in c.facts.get("pending_inferred", [])[:3]:
                label = item["summary"][:22]
                buttons += [(f"Food: {label}", f"inf:{item['id']}:yes"), ("Not food", f"inf:{item['id']}:no")]
            return buttons
        if fam == "reengage":
            return [("All good", f"re:{iid}:ok"), ("Off track", f"re:{iid}:off"), ("Pause 1 week", f"re:{iid}:pause")]
        if fam == "propose_commitment":
            # One-tap acceptance only when the LLM wrote a concrete rule; otherwise the user replies in words.
            row = self.store.get_intervention(iid)
            if row and row["version_offered"]:
                return [("Yes, set it", f"pc:{iid}:yes"), ("No", f"pc:{iid}:no")]
            return []
        if fam == "inactivity":
            return [("Did 5 min", f"iv:{iid}:done"), ("Not now", f"iv:{iid}:later")]
        return []

    async def _snooze_follow_up(self, now: datetime, today: str) -> OutboundMessage | None:
        store = self.store
        todays = store.interventions_on(today)
        for c in store.active_commitments():
            mine = [r for r in todays if r["commitment_id"] == c.id and r["kind"] in {"scheduled", "follow_up"}]
            snoozed = [r for r in mine if r["status"] == "snoozed"]
            if not snoozed or any(r["kind"] == "follow_up" for r in mine) or any(r["status"] == "sent" for r in mine):
                continue
            if store.commitment_outcome_on(c.id, today) in {"done", "smaller", "skipped"}:
                continue
            snoozed_at = parse_iso(snoozed[-1]["responded_at"] or snoozed[-1]["sent_at"])
            if not (timedelta(minutes=SNOOZE_MINUTES) <= now - snoozed_at <= timedelta(minutes=SNOOZE_MINUTES + 120)):
                continue
            pattern = analyse_commitment(store, c, today)
            decision = decide_level(c, pattern, store.get_profile(), today)
            text, violations = await self.coach.nudge(
                c.as_context(), decision.level, decision.version, pattern, self.build_context(now)["today"],
                "follow-up the user asked for by pressing 'Later'",
            )
            iid = store.add_intervention(
                now, commitment_id=c.id, kind="follow_up", intent=f"commitment:{c.id}", level=decision.level,
                slot=slot_of(local_time(now, store.tz)), style="follow_up", version_offered=decision.version,
                message_text=text, reason="follow-up after 'Later'",
            )
            return self._send(now, text, "nudge", nudge_buttons(iid), iid)
        return None

    # ------------------------------------------------------------ daily jobs
    def _daily_jobs(self, now: datetime) -> None:
        store = self.store
        today = store._date(now)
        if store.job_done("daily", today):
            return
        with store.transaction():
            mined = mine_patterns(store, now, self.food_table)
            expired = store.expire_inferred(now, days_ago(today, 2))
            store.mark_job(now, "daily", today)
        active = [p["key"] for p in mined if p["status"] == "active"]
        store.log_decision(now, "daily_jobs", f"patterns mined: {len(mined)} ({len(active)} active); inferred expired: {expired}", {"active": active})

    def _process_inferred(self, now: datetime) -> None:
        """Turn strong passive evidence into (clearly labelled) data without asking.

        - Payments to a payee the user already confirmed as food -> logged immediately (inferred).
        - Food-delivery orders nobody confirmed within 18 h -> logged as an unconfirmed restaurant meal.
          An order is strong evidence a meal happened; dropping it would under-count more than a rough
          estimate over-counts. It stays labelled `inferred_unconfirmed` so it is never mistaken for a log.
        """
        store = self.store
        for row in store.pending_inferred():
            if row["kind"] == "small_payment" and row["payee_key"]:
                label = store.get_payee_label(row["payee_key"])
                if label is not None and label["is_food"]:
                    with store.transaction():
                        store.resolve_inferred(now, row["id"], "auto_confirmed")  # not user activity
                        self._log_inferred_food(now, row, label["label"] or None, source="inferred_known_payee", confidence=0.5)
                    continue
            if (row["kind"] == "small_payment" and row["payee_key"] and now - parse_iso(row["occurred_at"]) > timedelta(hours=18)
                    and self._recurring_snack_payee(row["payee_key"])):
                with store.transaction():
                    store.resolve_inferred(now, row["id"], "auto_logged")
                    self._log_inferred_food(now, row, source="inferred_unconfirmed", confidence=0.3)
                continue
            if row["kind"] == "food_order" and now - parse_iso(row["occurred_at"]) > timedelta(hours=18):
                with store.transaction():
                    store.resolve_inferred(now, row["id"], "auto_logged")
                    self._log_inferred_food(now, row, source="inferred_unconfirmed", confidence=0.35)

    def _recurring_snack_payee(self, payee_key: str) -> bool:
        """An active weekday pattern of small payments to this payee makes an unconfirmed payment a probable snack."""
        return any(
            json.loads(p["data_json"]).get("payee") and p["key"].startswith(f"weekday_payee:{payee_key}:")
            for p in self.store.patterns("active", "weekday_payee")
        )

    def _auto_complete_from_health(self, now: datetime) -> None:
        """A walk/workout recorded by the phone completes the matching commitment (observed, no message needed)."""
        store = self.store
        today = store._date(now)
        sessions = store.health_records_between("exercise", today, today)
        if not sessions:
            return
        used: set[int] = set()
        for c in store.active_commitments():
            if c.activity_kind not in {"walk", "workout"} or not c.window_start:
                continue
            if not is_scheduled_on(c, date.fromisoformat(today)):
                continue
            if store.commitment_outcome_on(c.id, today) in {"done", "smaller"}:
                continue
            minimum = _minutes_in(c.versions[-1]) or 5
            full = _minutes_in(c.versions[0]) or minimum
            for rec in sessions:
                if rec["id"] in used:
                    continue  # one session completes one commitment
                payload = json.loads(rec["payload_json"])
                is_walk = "WALK" in str(payload.get("type", "")).upper()
                if c.activity_kind == "walk" and not is_walk:
                    continue
                minutes = rec["value"] or 0
                if minutes >= minimum:
                    outcome = "done" if minutes >= full else "smaller"
                    used.add(rec["id"])
                    self._record_commitment_outcome(now, c.id, outcome, "observed", f"{minutes:.0f} min (Health Connect)")
                    store.log_decision(now, "auto_complete", f"Commitment {c.id} marked {outcome} from a {minutes:.0f} min session")
                    break

    # ------------------------------------------------------------- digest
    def digest(self, now: datetime, engagement=None) -> dict:
        store = self.store
        today = store._date(now)
        ctx = self.build_context(now)
        engagement = engagement or compute_engagement(store, now, effective_budget(store.get_profile()))
        return {
            "local_time": ctx["local_time"],
            "engagement": engagement.as_context(),
            "today": ctx["today"],
            "meal_coverage_today": day_coverage(store, today),
            "intake_yesterday": estimated_day_intake(store, days_ago(today, 1), self.food_table),
            "logging_last_14_days": coverage_stats(store, today),
            "weight": ctx["weight"],
            "active_patterns": [p["claim"] for p in store.patterns("active")][:8],
            "calendar_today": store.calendar_day(today),
            "recent_proactive": [
                {"intent": r["intent"], "status": r["status"], "at": store.fmt_local(parse_iso(r["sent_at"]), "%a %H:%M")}
                for r in store.interventions_since(days_ago(today, 3))
                if r["kind"] in PROACTIVE_KINDS
            ][-8:],
            "known_facts": ctx["known_facts"][:20],
            "goal": store.get_profile().get("goal_text"),
        }

    def _expire_interventions(self, now: datetime) -> None:
        for row in self.store.open_interventions():
            if row["kind"] == "contextual":
                if now - parse_iso(row["sent_at"]) > timedelta(hours=12):
                    self.store.update_intervention(now, row["id"], "expired")  # no buttons: neutral, not "ignored"
                continue
            if now - parse_iso(row["sent_at"]) > self.ignore_after:
                self._resolve_intervention(now, row, "ignored")

    async def _maybe_weekly_review(self, now: datetime) -> OutboundMessage | None:
        store = self.store
        local = now.astimezone(_tz(store.tz))
        if local.weekday() != REVIEW_WEEKDAY:
            return None
        if not in_window(local.time(), parse_hhmm(REVIEW_WINDOW[0]), parse_hhmm(REVIEW_WINDOW[1])):
            return None
        today = store._date(now)
        for offset in range(0, 6):
            if any(r["kind"] == "review" for r in store.interventions_on(days_ago(today, offset))):
                return None
        stats = self.weekly_stats(now)
        if not stats["has_enough_activity"]:
            store.log_decision(now, "review_skipped", "Too little data this week for a useful review.")
            return None
        text, violations = await self.coach.weekly_review(stats)
        if any(v.startswith("llm_error") for v in violations):
            store.log_decision(now, "review_deferred", "LLM unavailable; weekly review will retry next tick.")
            return None
        iid = store.add_intervention(
            now,
            commitment_id=None,
            kind="review",
            intent="weekly_review",
            level=1,
            slot=slot_of(local.time()),
            style="weekly_review",
            version_offered=None,
            message_text=text,
            reason="weekly review",
        )
        store.log_decision(now, "weekly_review", "sent weekly review", {"violations": violations})
        return self._send(now, text, "nudge", intervention_id=iid)

    def weekly_stats(self, now: datetime) -> dict:
        store = self.store
        today = store._date(now)
        commitments = []
        for c in store.active_commitments():
            pattern = analyse_commitment(store, c, today, lookback_days=7)
            commitments.append({"title": c.title, "action": c.action, **pattern})
        trend = weight_trend(store.metric_series("weight_kg", days_ago(today, 60)), today)
        food_days_7 = store.food_logged_days(today, 7)
        food_days_14 = store.food_logged_days(today, 14)
        allowed, why = can_adjust_targets(trend, food_days_14)
        steps = [store.health_daily_sum("steps", days_ago(today, i)) for i in range(7)]
        known_steps = [s for s in steps if s is not None]
        week = [store.interventions_on(days_ago(today, i)) for i in range(7)]
        flat = [r for day in week for r in day if r["kind"] == "scheduled"]
        responded = sum(1 for r in flat if r["status"] in SUCCESS_STATUSES)
        return {
            "commitments": commitments,
            "weight": {"trend_kg": trend.latest_trend, "weekly_rate_pct": trend.weekly_rate_pct, "status": trend.status},
            "food_logged_days_7": food_days_7,
            "avg_steps_7d": round(sum(known_steps) / len(known_steps)) if known_steps else "unknown",
            "step_days_with_data": len(known_steps),
            "nudges_sent": len(flat),
            "nudges_followed": responded,
            "frequent_contexts": store.context_tag_counts(days_ago(today, 7)),
            "target_change_allowed": allowed,
            "target_change_note": why,
            "has_enough_activity": bool(commitments or food_days_7 or known_steps or trend.weigh_ins),
        }

    # ============================================================= /status
    def status_text(self, now: datetime) -> str:
        ctx = self.build_context(now)
        food = ctx["today"]["food"]
        steps = ctx["today"]["steps"]
        lines = [f"Mode: {ctx['coaching_mode']}"]
        profile = self.store.get_profile()
        if profile.get("paused_until") and parse_iso(profile["paused_until"]) > now:
            lines.append(f"Paused until {self.store.fmt_local(parse_iso(profile['paused_until']))}")
        if "kcal_range" in food:
            lines.append(
                f"Food today (estimated): {food['kcal_range'][0]}-{food['kcal_range'][1]} kcal, "
                f"protein {food['protein_g_range'][0]}-{food['protein_g_range'][1]} g ({food['items_logged']} items)"
            )
        else:
            lines.append("Food today: nothing logged")
        lines.append(f"Steps: {steps.get('value', 'unknown')} ({steps.get('source', 'no data')})")
        w = ctx["weight"]
        if w["trend_kg"] is not None:
            lines.append(f"Weight trend: {w['trend_kg']} kg ({w['status']})")
        lines.append(f"Health Connect: {ctx['health_connect']}")
        usage = getattr(self.llm, "usage", None)
        if callable(usage):
            used = {m: u for m, u in usage().items() if not u.startswith("0/")}
            lines.append("AI quota today: " + (", ".join(f"{m} {u}" for m, u in used.items()) or "unused"))
        active = self.store.active_commitments()
        if active:
            lines.append("Commitments:")
            lines += [f"  #{c.id} {c.title}" for c in active]
        return "\n".join(lines)


def _minutes_in(text: str) -> int | None:
    m = re.search(r"(\d+)\s*(?:min|minute|mins|minutes)", text or "", re.IGNORECASE)
    return int(m.group(1)) if m else None


def _tz(name: str):
    from zoneinfo import ZoneInfo

    return ZoneInfo(name)


__all__ = ["CoachService", "InboundMessage", "OutboundMessage", "normalize_tag"]
