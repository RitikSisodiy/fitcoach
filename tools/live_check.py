"""Live checks against the real LLM provider and Telegram (uses .env).

    python tools/live_check.py llm        # extraction accuracy on Hinglish samples + decide + reply
    python tools/live_check.py telegram   # bot identity + a test message to the allowed user
    python tools/live_check.py all

Prints a pass/fail table; exit code 1 on any failure.
"""

from __future__ import annotations

import asyncio
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from coach.config import Settings  # noqa: E402
from coach.engine.brain import DECIDE_INSTRUCTIONS, DECIDE_SCHEMA  # noqa: E402
from coach.engine.coach import COACH_SYSTEM, Coach  # noqa: E402
from coach.engine.extractor import Extractor  # noqa: E402
from coach.engine.safety import check_message  # noqa: E402
from coach.llm.base import LLMRequest  # noqa: E402
from coach.llm.factory import create_provider  # noqa: E402

# (message, check) - check receives the validated Extraction and returns True/False.
SAMPLES = [
    ("3 roti, chicken aur sabzi khayi lunch me", lambda e: {"roti", "chicken"} <= {f.name.lower().split()[0] for f in e.foods} and any(f.quantity == 3 for f in e.foods)),
    ("Bhai aaj chess ja raha hu 6 baje", lambda e: any("chess" in c["tag"] for c in e.context) and any(c["time_hint"] == "18:00" for c in e.context)),
    ("coffee pi aur sev parmal kha liya", lambda e: len(e.foods) == 2),
    ("samosa kha liya, aaj ka din toh gaya", lambda e: e.lapse and e.all_or_nothing),
    ("aaj weight 82.4 aaya", lambda e: e.body_metrics == [{"metric": "weight_kg", "value": 82.4}]),
    ("kal pizza khaunga party me", lambda e: all(not f.eaten for f in e.foods)),
    ("20 min walk kiya dinner ke baad", lambda e: any(a["kind"] == "walk" and a["duration_min"] == 20 for a in e.activities)),
    ("roz shaam 7 se 8 ke beech 30 min walk karunga, nahi ho paya toh 10 min", lambda e: e.commitments and e.commitments[0]["window_start"] == "19:00" and len(e.commitments[0]["versions"]) >= 2),
    ("chess pe sirf coffee lunga, snacks nahi - yeh rule rakho", lambda e: e.commitments and "chess" in (e.commitments[0]["trigger_tag"] or "")),
    ("main WFH karta hu, 10 se 7 job hai", lambda e: any(f["category"] == "routine" for f in e.facts)),
    # History questions are answered from the 7-day summary that every reply gets; data_needed is only
    # required for longer ranges. The extraction must simply not invent anything (e.g. a mode change).
    ("kal kitna khaya tha?", lambda e: not e.foods and e.coaching_mode_request is None and e.pause_days is None),
    ("nahi 3 nahi 2 roti thi", lambda e: e.food_corrections and e.food_corrections[0]["new_quantity"] == 2),
    ("lunch me mostly 3 roti dal sabzi hota hai", lambda e: "usual_meals" in e.profile_updates and not any(f.eaten for f in e.foods)),
    ("ek hafte ke liye coach band karo", lambda e: e.pause_days and 6 <= e.pause_days <= 8),
    ("mujhe 76 kg tak aana hai", lambda e: e.profile_updates.get("goal_weight_kg") == 76),
    ("2 plate pani puri", lambda e: e.foods and e.foods[0].quantity == 2),
    ("nashta skip kiya, sirf chai", lambda e: any("chai" in f.name.lower() or "tea" in f.name.lower() for f in e.foods)),
    ("aaj bahut stress hai office me, kuch bhi kha raha hu", lambda e: True),  # robustness only
    ("ok", lambda e: not e.foods and not e.commitments),
    ("haan wo 40 rupay wala chai samosa tha", lambda e: e.inferred_confirmations and e.inferred_confirmations[0]["is_food"]),
]

ACTIVE_COMMITMENTS = [{"id": 1, "kind": "habit", "title": "Evening walk", "action": "walk 30 min", "fallback_versions": ["30 min", "10 min"],
                       "schedule": "daily", "window": "19:00-20:00", "trigger": None, "enforcement": "normal", "user_words": None}]
TODAYS_FOOD = [{"item": "roti", "quantity": 3, "unit": "piece", "meal": "lunch"}]
PENDING = [{"inferred_id": 7, "summary": "₹40 paid to SHARMA CHAAT", "when": "Tue 18:40"}]


async def check_llm(settings: Settings) -> list[tuple[str, bool, str]]:
    llm = create_provider(settings)
    results = []
    ex = Extractor(llm)
    t0 = time.time()
    passed = 0
    for text, check in SAMPLES:
        try:
            extraction, raw = await ex.extract(text, [], ["chess"], ACTIVE_COMMITMENTS, [], "Tue 20:15", {1},
                                               pending_inferred=PENDING, todays_food=TODAYS_FOOD)
            ok = bool(check(extraction))
            summary = extraction.summary()
            detail = json.dumps({k: v for k, v in summary.items() if v not in ([], {}, None, False)}, ensure_ascii=False)[:220]
        except Exception as exc:
            ok, detail = False, f"{exc.__class__.__name__}: {exc}"[:160]
        passed += ok
        results.append((f"extract: {text[:45]}", ok, detail))
    results.append((f"extraction accuracy {passed}/{len(SAMPLES)} ({(time.time() - t0) / len(SAMPLES):.1f}s/msg)", passed >= 0.85 * len(SAMPLES), ""))

    digest = {"engagement": {"state": "engaged"}, "today": {"steps": {"value": 900, "source": "health_connect"}}}
    cands = [{"id": 0, "intent": "inactivity", "why": "Low movement (900 steps vs ~6500 typical)", "facts": {"steps_so_far": 900, "typical_daily_steps": 6500}}]
    try:
        raw = (await llm.generate(LLMRequest(system=COACH_SYSTEM, user_text=DECIDE_INSTRUCTIONS + "\nDIGEST:\n" + json.dumps(digest)
                                             + "\n\nCANDIDATES:\n" + json.dumps(cands), json_schema=DECIDE_SCHEMA, purpose="decide"))).json()
        msg = raw.get("message", "")
        ok = raw.get("choice") in (0, -1) and (raw.get("choice") == -1 or (msg and check_message(msg).ok))
        results.append(("decide: schema + safe message", ok, json.dumps(raw, ensure_ascii=False)[:200]))
    except Exception as exc:
        results.append(("decide: schema + safe message", False, str(exc)[:200]))

    coach = Coach(llm)
    reply, violations = await coach.reply("samosa kha liya yaar, aaj ka din kharab", {"today": {"food": {"status": "unknown"}}},
                                          {"foods": ["samosa"], "lapse": True}, [], ["Lapse protocol: no judgement, no compensation, next action."])
    results.append(("reply: Hinglish lapse reply passes safety", not violations, reply[:200]))
    return results


async def check_telegram(settings: Settings) -> list[tuple[str, bool, str]]:
    from telegram import Bot

    results = []
    bot = Bot(settings.telegram_bot_token)
    async with bot:
        me = await bot.get_me()
        results.append(("telegram: getMe", True, f"@{me.username}"))
        try:
            m = await bot.send_message(settings.telegram_allowed_user_id, "Live check from your coach: if you see this, delivery works.")
            results.append(("telegram: send to allowed user", True, f"message_id={m.message_id}"))
        except Exception as exc:
            results.append(("telegram: send to allowed user", False, f"{exc} (did you press /start on the bot first?)"))
    return results


def main() -> None:
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    settings = Settings.from_env()
    results = []
    if what in ("llm", "all"):
        results += asyncio.run(check_llm(settings))
    if what in ("telegram", "all"):
        results += asyncio.run(check_telegram(settings))
    width = max(len(r[0]) for r in results)
    for name, ok, detail in results:
        print(f"{'PASS' if ok else 'FAIL'}  {name.ljust(width)}  {detail}")
    sys.exit(0 if all(r[1] for r in results) else 1)


if __name__ == "__main__":
    main()
