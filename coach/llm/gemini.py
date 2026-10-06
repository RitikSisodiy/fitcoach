"""Google Gemini provider (google-genai SDK). Supports text, image and audio input.

Built around the free-tier limits seen in live testing (AI Studio, Oct 2026):

    model family           RPM   RPD
    3.x Flash               5     20     (3, 3.5, 3.6, 3.7, 3.8 - each model has its own quota)
    3.x Flash-Lite         15    500     (3.1, 3.5)
    2.5 Flash-Lite         10     20

So the provider:
1. Routes by purpose. High-volume structured calls (extraction, decisions) go to the
   Flash-Lite models (1,000 requests/day combined). User-facing replies and media
   (photos, voice) go to Flash models first (~80/day combined), then fall back to Lite.
2. Paces each model locally (RPM) and counts requests per Pacific day (RPD), persisted
   to disk so restarts don't reset the count.
3. On 429/503 from the server, skips that model (for a minute, or for the rest of the
   day when the daily quota is hit) and tries the next one.
Set GEMINI_TIER=paid to disable local pacing.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from collections import deque
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from .base import LLMError, LLMProvider, LLMRequest, LLMResponse

log = logging.getLogger(__name__)

RETRYABLE = ("429", "RESOURCE_EXHAUSTED", "503", "UNAVAILABLE", "500", "INTERNAL", "DEADLINE_EXCEEDED")
QUOTA_TZ = ZoneInfo("America/Los_Angeles")  # Gemini daily quotas reset at midnight Pacific
MAX_WAIT_SECONDS = 20.0

DEFAULT_FAST = ["gemini-3.5-flash-lite", "gemini-3.1-flash-lite"]
DEFAULT_SMART = ["gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash"]
# Live measurements (2026-10-06): Flash-Lite answered in ~6 s and matched Flash on the extraction
# check (16/20 both), while the Flash pool was frequently 503/504 and took up to 30 s to fail.
# So text work goes to Lite first; Flash is tried first only for photos/voice and the weekly review.
FAST_PURPOSES = {"decide", "nudge", "sim_user", "generic", "extract", "reply"}
CALL_TIMEOUT_SECONDS = 20.0  # a reply that takes longer is worse than a reply from the next model
SLOW_MODEL_COOLDOWN = 300.0


def free_tier_limits(model: str) -> tuple[int, int]:
    """(rpm, rpd) for the free tier, from the AI Studio rate-limit table."""
    m = model.lower()
    if "lite" in m:
        return (10, 20) if m.startswith("gemini-2.5") else (15, 500)
    return (5, 20)


class _ModelBudget:
    def __init__(self, rpm: int, rpd: int):
        self.rpm, self.rpd = rpm, rpd
        self.minute: deque[float] = deque()
        self.day_key = ""
        self.day_count = 0
        self.blocked_until = 0.0  # monotonic; set after a server 429/503
        self.blocked_day = ""  # Pacific date on which the daily quota was exhausted

    @staticmethod
    def today() -> str:
        return datetime.now(QUOTA_TZ).date().isoformat()

    def _roll(self) -> None:
        if self.day_key != self.today():
            self.day_key, self.day_count = self.today(), 0
        now = time.monotonic()
        while self.minute and now - self.minute[0] >= 60:
            self.minute.popleft()

    def wait_time(self) -> float | None:
        """Seconds until a call is allowed; None if not possible today."""
        self._roll()
        if self.blocked_day == self.today() or (self.rpd and self.day_count >= self.rpd):
            return None
        wait = max(0.0, self.blocked_until - time.monotonic())
        if self.rpm and len(self.minute) >= self.rpm:
            wait = max(wait, 60 - (time.monotonic() - self.minute[0]) + 0.05)
        return wait

    def record(self) -> None:
        self._roll()
        self.minute.append(time.monotonic())
        self.day_count += 1


def _retry_delay(message: str) -> float | None:
    m = re.search(r"retryDelay['\"]?\s*[:=]\s*['\"]?(\d+(?:\.\d+)?)s", message)
    return float(m.group(1)) if m else None


class GeminiProvider(LLMProvider):
    name = "gemini"
    supports_audio = True
    supports_images = True

    def __init__(
        self,
        api_key: str,
        model: str,
        timeout_seconds: float = 60.0,
        thinking_level: str = "",
        fallback_models: list[str] | None = None,
        rpm_per_model: int = 0,
        fast_models: list[str] | None = None,
        smart_models: list[str] | None = None,
        tier: str = "free",
        usage_path: str | None = None,
    ):
        super().__init__(model)
        self.thinking_level = thinking_level
        self.tier = tier
        primary = [model] + [m for m in (fallback_models or []) if m and m != model]
        self.smart = list(dict.fromkeys(smart_models or primary))
        self.fast = list(dict.fromkeys(fast_models or primary))
        self.usage_path = Path(usage_path) if usage_path else None
        self.budgets: dict[str, _ModelBudget] = {}
        for m in dict.fromkeys(self.smart + self.fast):
            rpm, rpd = (0, 0) if tier == "paid" else free_tier_limits(m)
            if rpm_per_model and tier != "paid":
                rpm = min(rpm, rpm_per_model)
            self.budgets[m] = _ModelBudget(rpm, rpd)
        self._lock = asyncio.Lock()
        self._load_usage()
        if not api_key:
            raise LLMError("GEMINI_API_KEY is not set")
        from google import genai  # lazy import keeps the SDK optional

        self._client = genai.Client(api_key=api_key, http_options={"timeout": int(timeout_seconds * 1000)})

    # ------------------------------------------------------------- persistence
    def _load_usage(self) -> None:
        if not self.usage_path or not self.usage_path.exists():
            return
        try:
            data = json.loads(self.usage_path.read_text())
        except (OSError, ValueError):
            return
        today = _ModelBudget.today()
        for m, b in self.budgets.items():
            entry = data.get(m, {})
            if entry.get("day") == today:
                b.day_key, b.day_count = today, int(entry.get("count", 0))
                if entry.get("exhausted"):
                    b.blocked_day = today

    def _save_usage(self) -> None:
        if not self.usage_path:
            return
        data = {m: {"day": b.day_key, "count": b.day_count, "exhausted": b.blocked_day == b.day_key} for m, b in self.budgets.items()}
        try:
            self.usage_path.parent.mkdir(parents=True, exist_ok=True)
            self.usage_path.write_text(json.dumps(data))
        except OSError:
            pass

    def usage(self) -> dict:
        """Requests used today per model (for /status and logs)."""
        out = {}
        for m, b in self.budgets.items():
            b._roll()
            out[m] = f"{b.day_count}/{b.rpd or '∞'}" + (" (exhausted)" if b.blocked_day == b.today() else "")
        return out

    # --------------------------------------------------------------- routing
    def route(self, request: LLMRequest) -> list[str]:
        if request.media or request.purpose not in FAST_PURPOSES:
            return list(dict.fromkeys(self.smart + self.fast))  # quality first, then volume
        return list(dict.fromkeys(self.fast + self.smart))

    def _config(self, request: LLMRequest, model: str):
        from google.genai import types

        config_kwargs: dict = {
            "system_instruction": request.system,
            "temperature": request.temperature,
            "max_output_tokens": request.max_output_tokens,
            "automatic_function_calling": types.AutomaticFunctionCallingConfig(disable=True),
        }
        if self.thinking_level and not model.startswith("gemini-2"):
            # Gemini 3.x: "minimal" | "low" | "medium" | "high". Low keeps latency/cost down for chat.
            config_kwargs["thinking_config"] = types.ThinkingConfig(thinking_level=self.thinking_level)
        if request.json_schema is not None:
            config_kwargs["response_mime_type"] = "application/json"
            config_kwargs["response_json_schema"] = request.json_schema
        return types.GenerateContentConfig(**config_kwargs)

    async def _pick(self, models: list[str]) -> str | None:
        """First model usable now; otherwise wait (bounded) for the soonest one."""
        async with self._lock:
            waits = {m: self.budgets[m].wait_time() for m in models}
            ready = [m for m in models if waits[m] == 0]
            if ready:
                self.budgets[ready[0]].record()
                self._save_usage()
                return ready[0]
            possible = [(w, m) for m, w in waits.items() if w is not None and w <= MAX_WAIT_SECONDS]
            if not possible:
                return None
            wait, model = min(possible)
            await asyncio.sleep(wait)
            self.budgets[model].record()
            self._save_usage()
            return model

    async def generate(self, request: LLMRequest) -> LLMResponse:
        from google.genai import types

        self.check_capabilities(request)
        contents: list = []
        for turn in request.history:
            role = "model" if turn.role == "assistant" else "user"
            contents.append(types.Content(role=role, parts=[types.Part.from_text(text=turn.text)]))
        parts = [types.Part.from_bytes(data=m.data, mime_type=m.mime_type) for m in request.media]
        parts.append(types.Part.from_text(text=request.user_text or "(no text)"))
        contents.append(types.Content(role="user", parts=parts))

        candidates = self.route(request)
        errors: list[str] = []
        for _ in range(len(candidates) + 2):
            model = await self._pick(candidates)
            if model is None:
                break
            try:
                result = await asyncio.wait_for(
                    self._client.aio.models.generate_content(model=model, contents=contents, config=self._config(request, model)),
                    timeout=CALL_TIMEOUT_SECONDS,
                )
            except asyncio.TimeoutError:
                errors.append(f"{model}: timeout")
                self.budgets[model].blocked_until = time.monotonic() + SLOW_MODEL_COOLDOWN
                log.warning("Gemini %s too slow; cooling down %ss", model, SLOW_MODEL_COOLDOWN)
                continue
            except Exception as exc:  # SDK raises several error types
                msg = str(exc)
                errors.append(f"{model}: {msg[:100]}")
                if not any(code in msg for code in RETRYABLE):
                    raise LLMError(f"Gemini call failed: {msg}") from exc
                budget = self.budgets[model]
                if "PerDay" in msg or "per day" in msg.lower():
                    budget.blocked_day = budget.today()
                    self._save_usage()
                elif "504" in msg or "DEADLINE" in msg:
                    budget.blocked_until = time.monotonic() + SLOW_MODEL_COOLDOWN
                else:
                    budget.blocked_until = time.monotonic() + max(_retry_delay(msg) or 0, 30 if "429" in msg else 60)
                log.warning("Gemini %s unavailable (%s); trying another model", model, msg[:50])
                continue
            text = result.text or ""
            if not text:
                errors.append(f"{model}: empty response")
                continue
            usage = getattr(result, "usage_metadata", None)
            return LLMResponse(
                text=text,
                provider=self.name,
                model=model,
                input_tokens=getattr(usage, "prompt_token_count", None),
                output_tokens=getattr(usage, "candidates_token_count", None),
            )
        raise LLMError("Gemini unavailable on all models (quota/overload): " + " | ".join(errors[-4:]))
