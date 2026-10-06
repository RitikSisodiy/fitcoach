"""Turns a natural-language (or voice/photo) message into structured candidate events.

The LLM proposes; `validate_extraction` (deterministic) disposes. Nothing the
LLM returns is stored without passing validation.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any

from ..llm.base import LLMProvider, LLMRequest, MediaPart

MIN_CONFIDENCE = 0.35
ALLOWED_UNITS_HINT = "piece, roti, katori, bowl, cup, glass, plate, handful, tbsp, tsp, slice, scoop, serving, can, packet, g, ml"
REASON_CATEGORIES = ["cannot", "forgot", "dont_want", "too_hard", "bad_timing", "unknown"]
FACT_CATEGORIES = ["preference", "constraint", "routine", "goal", "pattern", "health", "other"]
COMMITMENT_KINDS = ["if_then", "habit", "precommitment", "boundary"]

EXTRACTION_SCHEMA: dict = {
    "type": "object",
    "properties": {
        "food_items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "Common dish name in English or romanised Hindi"},
                    "quote": {"type": "string"},
                    "quantity": {"type": "number"},
                    "unit": {"type": "string"},
                    "meal_slot": {"type": "string", "enum": ["breakfast", "lunch", "snack", "dinner", "drink", "unknown"]},
                    "eaten": {"type": "string", "enum": ["eaten", "planned"]},
                    "est_kcal_low": {"type": "number"},
                    "est_kcal_high": {"type": "number"},
                    "est_protein_low": {"type": "number"},
                    "est_protein_high": {"type": "number"},
                    "confidence": {"type": "number"},
                },
                "required": ["name", "eaten", "confidence"],
            },
        },
        "activities": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "kind": {"type": "string", "enum": ["walk", "workout", "sport", "steps", "other"]},
                    "description": {"type": "string"},
                    "duration_min": {"type": "number"},
                    "steps": {"type": "number"},
                    "status": {"type": "string", "enum": ["done", "planned"]},
                    "confidence": {"type": "number"},
                },
                "required": ["kind", "status", "confidence"],
            },
        },
        "body_metrics": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "metric": {"type": "string", "enum": ["weight_kg", "waist_cm"]},
                    "value": {"type": "number"},
                    "confidence": {"type": "number"},
                },
                "required": ["metric", "value", "confidence"],
            },
        },
        "context": {
            "type": "array",
            "description": "Situations, places, or routines the user is in or about to be in.",
            "items": {
                "type": "object",
                "properties": {
                    "tag": {"type": "string", "description": "Short snake_case tag. Reuse a known tag when it fits."},
                    "timing": {"type": "string", "enum": ["now", "planned", "past"]},
                    "time_hint": {"type": "string", "description": "HH:MM 24h if the user said when (e.g. '6 baje' -> 18:00)"},
                    "day": {"type": "string", "enum": ["today", "tomorrow"], "description": "for planned: 'kal' = tomorrow"},
                    "description": {"type": "string"},
                },
                "required": ["tag", "timing"],
            },
        },
        "commitments": {
            "type": "array",
            "description": "Only rules/plans the user explicitly agrees to or states as their own commitment.",
            "items": {
                "type": "object",
                "properties": {
                    "kind": {"type": "string", "enum": COMMITMENT_KINDS},
                    "quote": {"type": "string"},
                    "title": {"type": "string"},
                    "trigger_tag": {"type": "string"},
                    "action": {"type": "string"},
                    "fallback_versions": {"type": "array", "items": {"type": "string"}},
                    "schedule_days": {"type": "string", "description": "daily, weekdays, weekends, or mon,tue,..."},
                    "window_start": {"type": "string", "description": "HH:MM 24h local"},
                    "window_end": {"type": "string", "description": "HH:MM 24h local"},
                    "strong_enforcement_requested": {"type": "boolean"},
                    "activity_kind": {"type": "string", "enum": ["walk", "workout", "food", "other"]},
                    "user_words": {"type": "string"},
                },
                "required": ["kind", "title", "action"],
            },
        },
        "commitment_updates": {
            "type": "array",
            "description": "Progress on an existing commitment (by id) mentioned in this message.",
            "items": {
                "type": "object",
                "properties": {
                    "commitment_id": {"type": "integer"},
                    "outcome": {"type": "string", "enum": ["done", "smaller", "skipped", "postponed"]},
                    "version": {"type": "string"},
                    "reason_category": {"type": "string", "enum": REASON_CATEGORIES},
                },
                "required": ["commitment_id", "outcome"],
            },
        },
        "facts": {
            "type": "array",
            "description": "Durable facts worth remembering for weeks (not passing remarks).",
            "items": {
                "type": "object",
                "properties": {
                    "category": {"type": "string", "enum": FACT_CATEGORIES},
                    "key": {"type": "string"},
                    "value": {"type": "string"},
                    "confidence": {"type": "number"},
                },
                "required": ["category", "key", "value", "confidence"],
            },
        },
        "food_corrections": {
            "type": "array",
            "description": "User corrects something logged earlier TODAY (e.g. 'nahi 2 roti thi'). new_quantity 0 = remove it.",
            "items": {
                "type": "object",
                "properties": {"item_name": {"type": "string"}, "new_quantity": {"type": "number"}, "quote": {"type": "string"}},
                "required": ["item_name", "new_quantity"],
            },
        },
        "commitment_changes": {
            "type": "array",
            "description": "User changes an ACTIVE_COMMITMENTS item (time, days, size, pause, stop).",
            "items": {
                "type": "object",
                "properties": {
                    "commitment_id": {"type": "integer"},
                    "quote": {"type": "string"},
                    "window_start": {"type": "string"},
                    "window_end": {"type": "string"},
                    "schedule_days": {"type": "string"},
                    "fallback_versions": {"type": "array", "items": {"type": "string"}},
                    "status": {"type": "string", "enum": ["active", "paused", "retired"]},
                },
                "required": ["commitment_id"],
            },
        },
        "inferred_confirmations": {
            "type": "array",
            "description": "Answers about PENDING_INFERRED items (was that payment/order food?). Also list the food itself in food_items.",
            "items": {
                "type": "object",
                "properties": {"inferred_id": {"type": "integer"}, "is_food": {"type": "boolean"}, "label": {"type": "string"}, "quote": {"type": "string"}},
                "required": ["inferred_id", "is_food"],
            },
        },
        "goal_weight_kg": {"type": "number", "description": "target weight the user states, e.g. 'mujhe 76 kg tak aana hai' -> 76"},
        "goal_text": {"type": "string", "description": "the user's goal in a few words"},
        "goal_quote": {"type": "string", "description": "exact words of the goal from USER_MESSAGE"},
        "height_cm": {"type": "number"},
        "usual_meals": {
            "type": "array",
            "description": "What the user says they USUALLY eat ('lunch me mostly 3 roti dal sabzi hota hai'). Not a log for today.",
            "items": {
                "type": "object",
                "properties": {
                    "slot": {"type": "string", "enum": ["breakfast", "lunch", "dinner"]},
                    "quote": {"type": "string"},
                    "items": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {"name": {"type": "string"}, "quantity": {"type": "number"}, "unit": {"type": "string"}},
                            "required": ["name"],
                        },
                    },
                },
                "required": ["slot", "items"],
            },
        },
        "pattern_feedback": {
            "type": "array",
            "description": "User says an observed pattern in KNOWN_PATTERNS is wrong or right.",
            "items": {
                "type": "object",
                "properties": {"pattern_id": {"type": "integer"}, "correct": {"type": "boolean"}},
                "required": ["pattern_id", "correct"],
            },
        },
        "data_needed": {
            "type": "array",
            "description": "Only if answering the user needs history you were not given. Code will fetch it.",
            "items": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "enum": ["food_log", "weight", "steps", "sleep", "commitment_history", "patterns", "search_messages"]},
                    "days": {"type": "integer", "description": "how many days of data"},
                    "offset_days": {"type": "integer", "description": "days back the range ENDS: 0 = up to today, 1 = up to yesterday ('kal')"},
                    "term": {"type": "string"},
                    "commitment_id": {"type": "integer"},
                },
                "required": ["query"],
            },
        },
        "lapse": {
            "type": "object",
            "properties": {
                "detected": {"type": "boolean"},
                "all_or_nothing_thinking": {"type": "boolean"},
                "note": {"type": "string"},
            },
            "required": ["detected"],
        },
        "coaching_mode_request": {"type": "string", "enum": ["gentle", "normal", "accountability", "strong"],
                                  "description": "ONLY if the user explicitly asks to change how strict the coach is"},
        "pause_days": {"type": "number", "description": "ONLY if the user asks to pause/stop the coach for some days"},
        "settings_quote": {"type": "string"},
    },
    "required": ["food_items", "activities", "body_metrics", "context", "commitments", "commitment_updates", "facts", "lapse"],
}

EXTRACTION_SYSTEM = f"""You extract structured data from messages a user sends to their personal fitness coach.
The user often writes casual Hinglish (romanised Hindi + English). Messages may be text, a voice note, or a food photo.

Rules:
- Extract only what the message supports. Never invent foods, quantities, or events. Empty arrays are fine.
- Food: one entry per distinct item. Use household units ({ALLOWED_UNITS_HINT}). If quantity is not said, omit it.
  Always give est_kcal_low/high and est_protein_low/high for the stated portion (best honest range; wide if unsure).
  For photos, say what is visible; lower confidence when portions or oil are unclear.
  "eaten" vs "planned": "kha liya" = eaten, "khaunga" = planned.
- Activities: "done" only if the user says it happened.
- Context: places/situations that matter for behaviour (e.g. going to a chess club, a party, travelling, ordering food,
  feeling stressed). Reuse a tag from KNOWN_TAGS when it refers to the same thing.
- Commitments: only when the user clearly sets or agrees to a rule/plan for themselves. Put the full version first in
  fallback_versions followed by smaller versions if mentioned or obviously implied (e.g. 30 -> 20 -> 10 -> 5 min).
- commitment_updates: when the message reports doing/skipping something in ACTIVE_COMMITMENTS or answers an OPEN_NUDGES item.
  reason_category: cannot (genuinely unable), forgot, dont_want, too_hard, bad_timing, unknown.
- Facts: durable things (work setup, routines, food preferences, constraints, injuries, goals). Skip one-off remarks.
- lapse.detected when the user reports going off-plan; all_or_nothing_thinking when they say the day/week is ruined or
  they will "start again Monday".
- food_corrections: only for fixing something already logged today ("3 nahi 2 roti thi"). Do NOT also add it to food_items.
- commitment_changes: "7 ki jagah 8 baje", "walk weekends pe nahi", "ye band karo" -> change/pause/retire that commitment.
- inferred_confirmations: when the user answers about a PENDING_INFERRED item ("haan wo chai-samosa tha", "wo rent tha").
- goal_weight_kg / goal_text / height_cm: goals and body stats the user states; usual_meals when they describe what they
  normally eat ("lunch me mostly 3 roti dal sabzi hota hai") - this is NOT a food log for today unless they also say they ate it.
- pattern_feedback: when the user says a KNOWN_PATTERNS claim is wrong/right.
- data_needed: when the user asks about the past ("kal kitna khaya", "is hafte walk kitni hui", "weight trend?").
- Meal slot: infer from words (nashta=breakfast, lunch, dinner/raat ka khana) or the local time if eaten "abhi".
- coaching_mode_request / pause_days only when the user explicitly asks to change coaching intensity or pause the coach.
- FOOD_LOGGED_TODAY and PENDING_INFERRED are context only. Log every food the user reports in THIS message, even if a
  similar item was logged earlier. Never create food from PENDING_INFERRED unless the user describes it in this message.
- Goals with a number (target weight, kg to lose) go to goal_weight_kg + goal_text + goal_quote, not facts.
- quote: for every food item, commitment, change, confirmation, setting and profile update, copy the exact words from
  USER_MESSAGE that support it. If you cannot quote the message, do not output that item.
- confidence is 0..1.

Examples (USER_MESSAGE -> key fields):
- "3 roti, chicken aur sabzi khayi lunch me" -> food_items: roti q3 piece lunch, chicken curry q1 katori lunch, sabzi lunch
- "kal kitna khaya tha?" -> data_needed: [{{query: food_log, days: 1, offset_days: 1}}]
- "mujhe 76 kg tak aana hai" -> goal_weight_kg: 76, goal_text: "reach 76 kg", goal_quote: "76 kg tak aana hai"
- "lunch me mostly 3 roti dal sabzi hota hai" -> usual_meals: [{{slot: lunch, items: [roti 3 piece, dal 1 katori, sabzi 1 katori], quote: "lunch me mostly 3 roti dal sabzi"}}], no food_items
- "ok" / "hmm" / "haan" (no content) -> nothing (empty arrays), unless it clearly answers an OPEN_NUDGES item
- "kal chess jaunga 6 baje" -> context: chess, planned, day tomorrow, time_hint 18:00
"""


@dataclass
class ExtractedFood:
    name: str
    quantity: float | None
    unit: str | None
    meal_slot: str
    eaten: bool
    est_kcal: tuple[float | None, float | None]
    est_protein: tuple[float | None, float | None]
    confidence: float


@dataclass
class Extraction:
    foods: list[ExtractedFood] = field(default_factory=list)
    activities: list[dict] = field(default_factory=list)
    body_metrics: list[dict] = field(default_factory=list)
    context: list[dict] = field(default_factory=list)
    commitments: list[dict] = field(default_factory=list)
    commitment_updates: list[dict] = field(default_factory=list)
    facts: list[dict] = field(default_factory=list)
    lapse: bool = False
    all_or_nothing: bool = False
    lapse_note: str = ""
    food_corrections: list[dict] = field(default_factory=list)
    commitment_changes: list[dict] = field(default_factory=list)
    inferred_confirmations: list[dict] = field(default_factory=list)
    profile_updates: dict = field(default_factory=dict)
    pattern_feedback: list[dict] = field(default_factory=list)
    data_needed: list[dict] = field(default_factory=list)
    coaching_mode_request: str | None = None
    pause_days: float | None = None
    dropped: list[str] = field(default_factory=list)  # validation notes for observability

    def summary(self) -> dict:
        return {
            "foods": [f.name for f in self.foods],
            "activities": [a["kind"] for a in self.activities],
            "body_metrics": [(m["metric"], m["value"]) for m in self.body_metrics],
            "context": [c["tag"] for c in self.context],
            "commitments": [c["title"] for c in self.commitments],
            "commitment_updates": self.commitment_updates,
            "facts": [f"{f['category']}:{f['key']}" for f in self.facts],
            "lapse": self.lapse,
            "food_corrections": self.food_corrections,
            "commitment_changes": [c["commitment_id"] for c in self.commitment_changes],
            "inferred_confirmations": self.inferred_confirmations,
            "profile_updates": self.profile_updates,
            "dropped": self.dropped,
        }


def _num(value: Any) -> float | None:
    try:
        if value is None or value == "":
            return None
        return float(value)
    except (TypeError, ValueError):
        return None


def _conf(value: Any, default: float = 0.6) -> float:
    v = _num(value)
    return max(0.0, min(1.0, v if v is not None else default))


def _hhmm(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    parts = value.strip().split(":")
    if len(parts) != 2 or not all(p.isdigit() for p in parts):
        return None
    h, m = int(parts[0]), int(parts[1])
    if 0 <= h < 24 and 0 <= m < 60:
        return f"{h:02d}:{m:02d}"
    return None


def _tokens(text: str) -> list[str]:
    return re.findall(r"[a-z0-9]+(?:\.[0-9]+)?", (text or "").lower())


def grounded(quote: Any, message: str | None) -> bool:
    """Is the claimed quote actually (mostly) in the user's message? None message = media input, can't check."""
    if message is None:
        return True
    q = _tokens(str(quote or ""))
    if not q:
        return False
    msg = set(_tokens(message))
    return sum(1 for t in q if t in msg) / len(q) >= 0.6


def food_mentioned(name: str, message: str | None, aliases: list[str] | None = None) -> bool:
    """The food (or one of its known aliases, e.g. curd/dahi) must appear in the user's words."""
    if message is None:
        return True
    msg = set(_tokens(message))
    candidates = [name] + list(aliases or [])
    return any(any(t in msg for t in _tokens(c) if len(t) > 2) for c in candidates)


def validate_extraction(
    raw: Any, active_commitment_ids: set[int], message: str | None = None, aliases_for=None
) -> Extraction:
    """Deterministic validation and clamping of LLM output.

    `message` is the user's text. Items that must come from the user's own words (food, commitments,
    settings, profile, corrections, confirmations) are dropped if their quote isn't found in it -
    a deterministic guard against the model inventing data from context (e.g. a pending payment).
    """
    out = Extraction()
    if not isinstance(raw, dict):
        out.dropped.append("extraction was not an object")
        return out

    def check(item: dict, what: str) -> bool:
        if grounded(item.get("quote"), message):
            return True
        out.dropped.append(f"{what}: not grounded in the message (quote={str(item.get('quote'))[:40]!r})")
        return False

    for item in raw.get("food_items") or []:
        if not isinstance(item, dict) or not str(item.get("name", "")).strip():
            continue
        if not check(item, f"food '{item.get('name')}'"):
            continue
        if not food_mentioned(str(item["name"]), message, aliases_for(str(item["name"])) if aliases_for else None):
            out.dropped.append(f"food '{item.get('name')}': not mentioned in the message")
            continue
        conf = _conf(item.get("confidence"))
        if conf < MIN_CONFIDENCE:
            out.dropped.append(f"food '{item.get('name')}' below confidence")
            continue
        qty = _num(item.get("quantity"))
        unit = str(item.get("unit")).strip()[:20] if item.get("unit") else None
        max_qty = 3000 if (unit or "").lower() in {"g", "gm", "gms", "gram", "grams", "ml"} else 50
        if qty is not None and not (0 < qty <= max_qty):
            out.dropped.append(f"food '{item.get('name')}' quantity {qty} {unit} out of range; ignored quantity and unit")
            qty, unit = None, None
        kl, kh = _num(item.get("est_kcal_low")), _num(item.get("est_kcal_high"))
        if kl is not None and kh is not None and kl > kh:
            kl, kh = kh, kl
        pl, ph = _num(item.get("est_protein_low")), _num(item.get("est_protein_high"))
        if pl is not None and ph is not None and pl > ph:
            pl, ph = ph, pl
        out.foods.append(
            ExtractedFood(
                name=str(item["name"]).strip()[:80],
                quantity=qty,
                unit=unit,
                meal_slot=item.get("meal_slot") or "unknown",
                eaten=item.get("eaten", "eaten") != "planned",
                est_kcal=(kl, kh),
                est_protein=(pl, ph),
                confidence=conf,
            )
        )

    for act in raw.get("activities") or []:
        if not isinstance(act, dict) or act.get("status") != "done":
            continue
        conf = _conf(act.get("confidence"))
        if conf < MIN_CONFIDENCE:
            continue
        duration = _num(act.get("duration_min"))
        if duration is not None and not (0 < duration <= 600):
            out.dropped.append(f"activity duration {duration} out of range")
            duration = None
        steps = _num(act.get("steps"))
        if steps is not None and not (0 < steps <= 80000):
            out.dropped.append(f"steps {steps} out of range")
            steps = None
        out.activities.append(
            {
                "kind": act.get("kind") if act.get("kind") in {"walk", "workout", "sport", "steps", "other"} else "other",
                "description": str(act.get("description") or "")[:120],
                "duration_min": duration,
                "steps": int(steps) if steps else None,
                "confidence": conf,
            }
        )

    for metric in raw.get("body_metrics") or []:
        if not isinstance(metric, dict):
            continue
        name, value = metric.get("metric"), _num(metric.get("value"))
        bounds = {"weight_kg": (30, 300), "waist_cm": (40, 200)}
        if name not in bounds or value is None:
            continue
        lo, hi = bounds[name]
        if not (lo <= value <= hi):
            out.dropped.append(f"{name}={value} out of plausible range")
            continue
        if _conf(metric.get("confidence")) < 0.6:
            out.dropped.append(f"{name}={value} low confidence")
            continue
        out.body_metrics.append({"metric": name, "value": round(value, 2)})

    for ctx in raw.get("context") or []:
        if isinstance(ctx, dict) and str(ctx.get("tag", "")).strip():
            out.context.append(
                {
                    "tag": str(ctx["tag"]).strip(),
                    "timing": ctx.get("timing") if ctx.get("timing") in {"now", "planned", "past"} else "now",
                    "time_hint": _hhmm(ctx.get("time_hint")),
                    "day": "tomorrow" if ctx.get("day") == "tomorrow" and ctx.get("timing") == "planned" else "today",
                    "description": str(ctx.get("description") or "")[:200],
                }
            )

    for com in raw.get("commitments") or []:
        if not isinstance(com, dict) or not com.get("action") or not com.get("title"):
            continue
        if not check(com, f"commitment '{com.get('title')}'"):
            continue
        kind = com.get("kind") if com.get("kind") in COMMITMENT_KINDS else "habit"
        versions = [str(v)[:120] for v in (com.get("fallback_versions") or []) if str(v).strip()]
        start, end = _hhmm(com.get("window_start")), _hhmm(com.get("window_end"))
        if (start is None) != (end is None):
            out.dropped.append(f"commitment '{com.get('title')}' had a half-specified window")
            start = end = None
        schedule = str(com.get("schedule_days") or "").strip().lower() or None
        if start and not schedule:
            schedule = "daily"
        out.commitments.append(
            {
                "kind": kind,
                "title": str(com["title"])[:80],
                "trigger_tag": str(com.get("trigger_tag") or "").strip() or None,
                "action": str(com["action"])[:200],
                "versions": versions or [str(com["action"])[:120]],
                "schedule_days": schedule,
                "window_start": start,
                "window_end": end,
                "strong_requested": bool(com.get("strong_enforcement_requested")),
                "activity_kind": com.get("activity_kind") if com.get("activity_kind") in {"walk", "workout", "food", "other"} else None,
                "user_words": str(com.get("user_words") or "")[:300] or None,
            }
        )

    # Live runs often omitted activity_kind, which disables auto-completion from phone exercise data.
    for com in out.commitments:
        if not com.get("activity_kind"):
            words = set(_tokens(f"{com['title']} {com['action']}"))
            if words & {"walk", "walking", "chalna", "chal", "chalunga", "steps"}:
                com["activity_kind"] = "walk"
            elif words & {"workout", "gym", "exercise", "yoga", "run", "running", "pushups", "cycling"}:
                com["activity_kind"] = "workout"

    # A rule like "chess pe sirf coffee" sometimes comes back without its trigger; if the message
    # names exactly one situation, that situation is the trigger.
    situations = {c["tag"] for c in out.context}
    for com in out.commitments:
        if com["kind"] in {"if_then", "precommitment"} and not com["trigger_tag"] and len(situations) == 1:
            com["trigger_tag"] = next(iter(situations))

    for upd in raw.get("commitment_updates") or []:
        if not isinstance(upd, dict):
            continue
        try:
            cid = int(upd.get("commitment_id"))
        except (TypeError, ValueError):
            continue
        if cid not in active_commitment_ids:
            out.dropped.append(f"update for unknown commitment {cid}")
            continue
        if upd.get("outcome") not in {"done", "smaller", "skipped", "postponed"}:
            continue
        reason = upd.get("reason_category")
        out.commitment_updates.append(
            {
                "commitment_id": cid,
                "outcome": upd["outcome"],
                "version": str(upd.get("version") or "")[:120] or None,
                "reason_category": reason if reason in REASON_CATEGORIES else None,
            }
        )

    for fact in raw.get("facts") or []:
        if not isinstance(fact, dict) or not fact.get("key") or not fact.get("value"):
            continue
        conf = _conf(fact.get("confidence"))
        if conf < 0.5:
            continue
        category = fact.get("category") if fact.get("category") in FACT_CATEGORIES else "other"
        out.facts.append(
            {"category": category, "key": str(fact["key"])[:48], "value": str(fact["value"])[:300], "confidence": conf}
        )

    for corr in raw.get("food_corrections") or []:
        if not isinstance(corr, dict) or not str(corr.get("item_name", "")).strip() or not check(corr, "food correction"):
            continue
        q = _num(corr.get("new_quantity"))
        if q is None or q < 0 or q > 50:
            out.dropped.append(f"correction for '{corr.get('item_name')}' had invalid quantity")
            continue
        out.food_corrections.append({"item_name": str(corr["item_name"]).strip()[:80], "new_quantity": q})

    for ch in raw.get("commitment_changes") or []:
        if not isinstance(ch, dict) or not check(ch, "commitment change"):
            continue
        try:
            cid = int(ch.get("commitment_id"))
        except (TypeError, ValueError):
            continue
        if cid not in active_commitment_ids:
            out.dropped.append(f"change for unknown commitment {cid}")
            continue
        change: dict = {"commitment_id": cid}
        start, end = _hhmm(ch.get("window_start")), _hhmm(ch.get("window_end"))
        if start and end:
            change["window_start"], change["window_end"] = start, end
        elif start or end:
            out.dropped.append(f"change for commitment {cid} had a half-specified window")
        if ch.get("schedule_days"):
            change["schedule_days"] = str(ch["schedule_days"]).strip().lower()[:60]
        versions = [str(v)[:120] for v in (ch.get("fallback_versions") or []) if str(v).strip()]
        if versions:
            change["versions"] = versions
        if ch.get("status") in {"active", "paused", "retired"}:
            change["status"] = ch["status"]
        if len(change) > 1:
            out.commitment_changes.append(change)

    for conf in raw.get("inferred_confirmations") or []:
        if not isinstance(conf, dict) or not check(conf, "inferred confirmation"):
            continue
        try:
            iid = int(conf.get("inferred_id"))
        except (TypeError, ValueError):
            continue
        out.inferred_confirmations.append(
            {"inferred_id": iid, "is_food": bool(conf.get("is_food")), "label": str(conf.get("label") or "")[:60] or None}
        )

    prof = dict(raw.get("profile_updates") or {}) if isinstance(raw.get("profile_updates"), dict) else {}
    for key in ("goal_weight_kg", "goal_text", "height_cm"):
        if raw.get(key) not in (None, ""):
            prof[key] = raw[key]
            prof.setdefault("quote", raw.get("goal_quote") or raw.get("quote_goal"))
    if raw.get("usual_meals"):
        meals = [m for m in raw["usual_meals"] if isinstance(m, dict) and grounded(m.get("quote"), message)]
        if meals:
            prof["usual_meals"] = meals
            prof.setdefault("quote", meals[0].get("quote"))
    if isinstance(prof, dict) and prof and not check(prof, "profile update"):
        prof = {}
    if isinstance(prof, dict):
        if str(prof.get("goal_text") or "").strip():
            out.profile_updates["goal_text"] = str(prof["goal_text"]).strip()[:200]
        gw = _num(prof.get("goal_weight_kg"))
        if gw is not None and 35 <= gw <= 250:
            out.profile_updates["goal_weight_kg"] = round(gw, 1)
        h = _num(prof.get("height_cm"))
        if h is not None and 120 <= h <= 230:
            out.profile_updates["height_cm"] = round(h)
        usual = {}
        for meal in prof.get("usual_meals") or []:
            if not isinstance(meal, dict) or meal.get("slot") not in {"breakfast", "lunch", "dinner"}:
                continue
            items = []
            for it in meal.get("items") or []:
                if isinstance(it, dict) and str(it.get("name", "")).strip():
                    q = _num(it.get("quantity"))
                    items.append({"name": str(it["name"]).strip()[:60], "quantity": q if q and 0 < q <= 20 else None,
                                  "unit": str(it.get("unit")).strip()[:20] if it.get("unit") else None})
            if items:
                usual[meal["slot"]] = items[:8]
        if usual:
            out.profile_updates["usual_meals"] = usual

    for fb in raw.get("pattern_feedback") or []:
        if isinstance(fb, dict) and isinstance(fb.get("pattern_id"), (int, float)):
            out.pattern_feedback.append({"pattern_id": int(fb["pattern_id"]), "correct": bool(fb.get("correct"))})

    allowed_queries = {"food_log", "weight", "steps", "sleep", "commitment_history", "patterns", "search_messages"}
    for q in (raw.get("data_needed") or [])[:3]:
        if isinstance(q, dict) and q.get("query") in allowed_queries:
            days = _num(q.get("days"))
            out.data_needed.append(
                {
                    "query": q["query"],
                    "days": int(max(1, min(days or 7, 90))),
                    "offset_days": int(max(0, min(_num(q.get("offset_days")) or 0, 90))),
                    "term": str(q.get("term") or "")[:60] or None,
                    "commitment_id": int(q["commitment_id"]) if isinstance(q.get("commitment_id"), (int, float)) else None,
                }
            )

    lapse = raw.get("lapse") or {}
    if isinstance(lapse, dict):
        out.lapse = bool(lapse.get("detected"))
        out.all_or_nothing = bool(lapse.get("all_or_nothing_thinking"))
        out.lapse_note = str(lapse.get("note") or "")[:200]

    settings = dict(raw.get("settings_request") or {}) if isinstance(raw.get("settings_request"), dict) else {}
    if raw.get("coaching_mode_request"):
        settings["coaching_mode"] = raw["coaching_mode_request"]
    if raw.get("pause_days") not in (None, "", 0):
        settings["pause_days"] = raw["pause_days"]
    if settings and raw.get("settings_quote"):
        settings.setdefault("quote", raw["settings_quote"])
    if isinstance(settings, dict) and settings and not check(settings, "settings request"):
        settings = {}
    if isinstance(settings, dict):
        mode = settings.get("coaching_mode")
        if mode in {"gentle", "normal", "accountability", "strong"}:
            out.coaching_mode_request = mode
        pause = _num(settings.get("pause_days"))
        if pause is not None and 0 < pause <= 30:
            out.pause_days = pause
    return out


class Extractor:
    def __init__(self, llm: LLMProvider, food_table=None):
        self.llm = llm
        self.food_table = food_table

    def _aliases(self, name: str) -> list[str]:
        if self.food_table is None:
            return []
        row, kind = self.food_table.match(name)
        return row.aliases if row is not None and kind in {"exact", "close", "partial"} else []

    async def extract(
        self,
        text: str,
        media: list[MediaPart],
        known_tags: list[str],
        active_commitments: list[dict],
        open_nudges: list[dict],
        local_time_str: str,
        active_commitment_ids: set[int],
        pending_inferred: list[dict] | None = None,
        known_patterns: list[dict] | None = None,
        todays_food: list[dict] | None = None,
    ) -> tuple[Extraction, Any]:
        prompt = (
            f"LOCAL_TIME: {local_time_str}\n"
            f"KNOWN_TAGS: {json.dumps(known_tags)}\n"
            f"ACTIVE_COMMITMENTS: {json.dumps(active_commitments, ensure_ascii=False)}\n"
            f"OPEN_NUDGES: {json.dumps(open_nudges, ensure_ascii=False)}\n"
            f"PENDING_INFERRED: {json.dumps(pending_inferred or [], ensure_ascii=False)}\n"
            f"KNOWN_PATTERNS: {json.dumps(known_patterns or [], ensure_ascii=False)}\n"
            f"FOOD_LOGGED_TODAY: {json.dumps(todays_food or [], ensure_ascii=False)}\n\n"
            f"USER_MESSAGE: {text or '(see attached media)'}"
        )
        request = LLMRequest(
            system=EXTRACTION_SYSTEM,
            user_text=prompt,
            media=media,
            json_schema=EXTRACTION_SCHEMA,
            temperature=0.0,
            purpose="extract",
        )
        response = await self.llm.generate(request)
        raw = response.json()
        message = None if media else (text or "")
        return validate_extraction(raw, active_commitment_ids, message, self._aliases), raw


FOCUSED_SCHEMAS = {
    "usual_meals": {
        "type": "object",
        "properties": {
            "meals": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "slot": {"type": "string", "enum": ["breakfast", "lunch", "dinner"]},
                        "quote": {"type": "string"},
                        "items": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "properties": {"name": {"type": "string"}, "quantity": {"type": "number"}, "unit": {"type": "string"}},
                                "required": ["name"],
                            },
                        },
                    },
                    "required": ["slot", "items", "quote"],
                },
            }
        },
        "required": ["meals"],
    },
    "goal": {
        "type": "object",
        "properties": {
            "goal_text": {"type": "string"},
            "goal_weight_kg": {"type": "number"},
            "goal_quote": {"type": "string"},
        },
    },
}

FOCUSED_QUESTIONS = {
    "usual_meals": "The coach asked what the user USUALLY eats for breakfast/lunch/dinner. Extract the usual meals they describe "
    "(household units). Only include meals they actually describe; quote their exact words for each.",
    "goal": "The coach asked about the user's goal. Extract the goal in a few words and the target weight in kg if stated; "
    "quote their exact words.",
}


async def focused_extract(llm: LLMProvider, topic: str, text: str) -> dict:
    """Small, single-purpose extraction used when the user answers a question the coach just asked.

    Live testing showed the big general schema sometimes misses these answers; a tiny schema is more reliable.
    """
    request = LLMRequest(
        system="You extract one specific thing from a user's chat reply (often Hinglish). Never invent content.",
        user_text=f"{FOCUSED_QUESTIONS[topic]}\n\nUSER_MESSAGE: {text}",
        json_schema=FOCUSED_SCHEMAS[topic],
        temperature=0.0,
        purpose="extract",
    )
    raw = (await llm.generate(request)).json()
    if not isinstance(raw, dict):
        return {}
    if topic == "usual_meals":
        return validate_extraction({"usual_meals": raw.get("meals") or []}, set(), text).profile_updates
    return validate_extraction(raw, set(), text).profile_updates
