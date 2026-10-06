"""LLM-facing coaching: replies, nudges, and weekly reviews.

All behavioural *decisions* (whether to message, which level) are made by code
before these functions are called. These functions turn a decision plus context
into words, then pass them through the safety guard.
"""

from __future__ import annotations

import json

from ..llm.base import ChatTurn, LLMError, LLMProvider, LLMRequest
from .policy import LEVEL_NAMES
from .safety import SAFE_FALLBACK_REPLY, check_message

COACH_SYSTEM = """You are a personal fitness and nutrition coach for one user. You work over chat (Telegram).

Who the user is: they already know what to do. Their problem is follow-through: procrastination, cravings, convenience,
"I'll do it later", and abandoning the plan after one slip. Explaining benefits does not work. Reducing friction,
pre-commitment, timely reminders of THEIR OWN commitments, and smaller fallback versions do.

How you talk:
- Mirror the user's language and register. If they write Hinglish, reply in natural Hinglish.
- Short. Usually 1-3 sentences. No lectures, no lists unless asked, no emojis unless the user uses them.
- Concrete next action > explanation. Offer the smallest useful version when they are resisting.
- Honest and direct, never shaming. Never call them lazy, undisciplined, a failure, or say they disappointed you.
  Talk about the situation and the pattern, not their character.
- After a slip: "that happened, the next decision still matters". Never suggest compensating by skipping meals,
  starving, dehydrating, or extreme exercise. Return to normal at the next meal.
- Use data honestly. Food numbers are ranges and estimates; say so when it matters. Say "unknown" rather than guess.
  Never invent data that is not in the context.
- Respect autonomy: the user sets goals and commitments. You hold them to what THEY chose. If they genuinely cannot do
  something today, help reschedule instead of pushing.
- Never give medical advice, medication changes, supplements beyond basics (e.g. whey), or targets below 1200 kcal/day.
- If something in context shows a known temptation pattern or an if-then rule for the current situation, remind them
  of their own rule, briefly, before the decision happens.
- Do not ask more than one question per message. Ask only when the answer changes what you do.
"""


LLM_DOWN_REPLY = "Saved. My AI side is having trouble right now, so I'll keep it short - I'll catch up once it's back."


def _ctx(context: dict) -> str:
    return json.dumps(context, ensure_ascii=False, default=str, indent=1)


class Coach:
    def __init__(self, llm: LLMProvider):
        self.llm = llm

    async def _generate_safe(
        self, request: LLMRequest, fallback: str = SAFE_FALLBACK_REPLY, down_fallback: str = LLM_DOWN_REPLY
    ) -> tuple[str, list[str]]:
        """Generate, check, and retry once with feedback; fall back to a safe template."""
        violations: list[str] = []
        for attempt in range(2):
            try:
                response = await self.llm.generate(request)
            except LLMError as exc:
                violations.append(f"llm_error: {exc}")
                return down_fallback, violations
            text = response.text.strip()
            result = check_message(text)
            if result.ok and text:
                return text, violations
            violations.extend(result.violations or ["empty reply"])
            request = LLMRequest(
                system=request.system,
                user_text=request.user_text
                + "\n\nYOUR PREVIOUS DRAFT WAS REJECTED by the safety/tone check for: "
                + "; ".join(result.violations)
                + ". Rewrite without that.",
                history=request.history,
                temperature=request.temperature,
                max_output_tokens=request.max_output_tokens,
                purpose=request.purpose,
            )
        return fallback, violations

    async def reply(
        self,
        user_text: str,
        context: dict,
        extraction_summary: dict,
        history: list[ChatTurn],
        guidance: list[str],
        data_results: dict | None = None,
    ) -> tuple[str, list[str]]:
        prompt = (
            "CONTEXT (from the database; trust this over memory):\n"
            f"{_ctx(context)}\n\n"
            "WHAT WAS JUST RECORDED FROM THIS MESSAGE:\n"
            f"{_ctx(extraction_summary)}\n\n"
            + (
                "DATA YOU ASKED FOR (computed from the database; use these numbers exactly, and say when data is missing):\n"
                f"{_ctx(data_results)}\n\n"
                if data_results
                else ""
            )
            + "COACHING GUIDANCE FOR THIS TURN (decided by the coaching policy):\n- "
            + ("\n- ".join(guidance) if guidance else "Respond naturally; acknowledge briefly what was logged.")
            + f"\n\nUSER MESSAGE:\n{user_text}"
        )
        return await self._generate_safe(
            LLMRequest(system=COACH_SYSTEM, user_text=prompt, history=history, temperature=0.6, purpose="reply")
        )

    async def nudge(
        self, commitment: dict, level: int, version: str, pattern: dict, context: dict, reason: str
    ) -> tuple[str, list[str]]:
        level_name = LEVEL_NAMES[level]
        instructions = {
            1: "Gentle, one-line check-in on the planned action. Make it easy to say yes.",
            2: "Reference the specific situation/pattern. Suggest the offered version as the default.",
            3: "Accountability: state the observed pattern factually (numbers), without blame. Say the approach should change "
            "and propose one concrete change (time, size, or trigger). Ask one question about what gets in the way.",
            4: "Strong recommendation: clearly and warmly recommend doing the offered (smaller) version now rather than skipping.",
            5: "User-authorised enforcement: remind them they asked you to hold them to this. Ask for the minimum version, "
            "or an explicit reason and a reschedule if they truly cannot. Firm, never harsh.",
        }[level]
        prompt = (
            f"Write a proactive message (level {level}: {level_name}).\n"
            f"Instruction: {instructions}\n"
            f"Why this message now (policy summary): {reason}\n"
            f"Commitment: {_ctx(commitment)}\n"
            f"Version to offer: {version}\n"
            f"Observed pattern: {_ctx(pattern)}\n"
            f"Today so far: {_ctx(context)}\n"
            "The message will be shown with buttons: Done / Smaller version / Later / Skip today. Do not list the buttons."
        )
        template = f"{commitment['title']}: how about {version} now?"
        return await self._generate_safe(
            LLMRequest(system=COACH_SYSTEM, user_text=prompt, temperature=0.7, max_output_tokens=1024, purpose="nudge"),
            fallback=template,
            down_fallback=template,
        )

    async def weekly_review(self, stats: dict) -> tuple[str, list[str]]:
        prompt = (
            "Write the weekly review message. Structure: one line on what went well (with numbers), one line on the main "
            "pattern that got in the way, then propose ONE small experiment for next week (specific trigger, action, and how "
            "we'll measure it) and ask if they agree. Under 120 words. Do not change calorie targets unless "
            "stats.target_change_allowed is true.\n"
            f"STATS: {_ctx(stats)}"
        )
        return await self._generate_safe(
            LLMRequest(system=COACH_SYSTEM, user_text=prompt, temperature=0.5, max_output_tokens=500, purpose="review")
        )
