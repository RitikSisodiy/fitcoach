"""Application configuration loaded from environment variables.

Secrets are never hardcoded; see `.env.example` for the full list.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _load_dotenv(path: Path) -> None:
    """Minimal .env loader (KEY=VALUE lines). Existing env vars take precedence."""
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def _int(name: str, default: int) -> int:
    value = os.environ.get(name)
    return int(value) if value not in (None, "") else default


def _float(name: str, default: float) -> float:
    value = os.environ.get(name)
    return float(value) if value not in (None, "") else default


@dataclass
class Settings:
    # LLM
    llm_provider: str = "gemini"
    llm_model: str = ""
    llm_fallback_provider: str = ""
    llm_fallback_model: str = ""
    gemini_api_key: str = ""
    anthropic_api_key: str = ""
    openai_api_key: str = ""
    openrouter_api_key: str = ""
    llm_timeout_seconds: float = 60.0
    gemini_thinking_level: str = "low"
    gemini_fallback_models: str = ""  # legacy: extra models after LLM_MODEL
    gemini_rpm_per_model: int = 0  # optional extra cap on requests/minute per model
    gemini_fast_models: str = "gemini-3.5-flash-lite,gemini-3.1-flash-lite"
    gemini_smart_models: str = "gemini-3.8-flash,gemini-3.7-flash,gemini-3.6-flash,gemini-3.5-flash"
    gemini_tier: str = "free"  # free = pace by AI Studio free-tier limits; paid = no local limits

    # Storage
    database_path: str = "data/coach.db"

    # Telegram
    telegram_bot_token: str = ""
    telegram_allowed_user_id: int = 0

    # Health Connect ingest
    ingest_host: str = "127.0.0.1"
    ingest_port: int = 8787
    ingest_secret: str = ""

    # Calendar (private ICS URL; optional)
    calendar_ics_url: str = ""

    # Behaviour defaults (user can change at runtime)
    timezone: str = "Asia/Kolkata"
    planner_interval_minutes: int = 15
    ignore_after_minutes: int = 120
    default_daily_message_budget: int = 3
    min_minutes_between_proactive: int = 90
    default_quiet_start: str = "22:30"
    default_quiet_end: str = "07:30"

    extra: dict = field(default_factory=dict)

    @classmethod
    def from_env(cls, dotenv_path: str | None = ".env") -> "Settings":
        if dotenv_path:
            _load_dotenv(Path(dotenv_path))
        env = os.environ.get
        return cls(
            llm_provider=env("LLM_PROVIDER", "gemini").lower(),
            llm_model=env("LLM_MODEL", ""),
            llm_fallback_provider=env("LLM_FALLBACK_PROVIDER", "").lower(),
            llm_fallback_model=env("LLM_FALLBACK_MODEL", ""),
            gemini_api_key=env("GEMINI_API_KEY", ""),
            anthropic_api_key=env("ANTHROPIC_API_KEY", ""),
            openai_api_key=env("OPENAI_API_KEY", ""),
            openrouter_api_key=env("OPENROUTER_API_KEY", ""),
            llm_timeout_seconds=_float("LLM_TIMEOUT_SECONDS", 60.0),
            gemini_thinking_level=env("GEMINI_THINKING_LEVEL", "low"),
            gemini_fallback_models=env("GEMINI_FALLBACK_MODELS", ""),
            gemini_rpm_per_model=_int("GEMINI_RPM_PER_MODEL", 0),
            gemini_fast_models=env("GEMINI_FAST_MODELS", "gemini-3.5-flash-lite,gemini-3.1-flash-lite"),
            gemini_smart_models=env("GEMINI_SMART_MODELS", "gemini-3.8-flash,gemini-3.7-flash,gemini-3.6-flash,gemini-3.5-flash"),
            gemini_tier=env("GEMINI_TIER", "free").lower(),
            database_path=env("DATABASE_PATH", "data/coach.db"),
            telegram_bot_token=env("TELEGRAM_BOT_TOKEN", ""),
            telegram_allowed_user_id=_int("TELEGRAM_ALLOWED_USER_ID", 0),
            ingest_host=env("INGEST_HOST", "127.0.0.1"),
            ingest_port=_int("INGEST_PORT", 8787),
            ingest_secret=env("INGEST_SECRET", ""),
            timezone=env("COACH_TIMEZONE", "Asia/Kolkata"),
            calendar_ics_url=env("CALENDAR_ICS_URL", ""),
            planner_interval_minutes=_int("PLANNER_INTERVAL_MINUTES", 15),
            ignore_after_minutes=_int("IGNORE_AFTER_MINUTES", 120),
            default_daily_message_budget=_int("DAILY_MESSAGE_BUDGET", 3),
            min_minutes_between_proactive=_int("MIN_MINUTES_BETWEEN_PROACTIVE", 90),
            default_quiet_start=env("QUIET_START", "22:30"),
            default_quiet_end=env("QUIET_END", "07:30"),
        )

    def redacted(self) -> dict:
        """Settings safe for logging (no secrets)."""
        data = dict(self.__dict__)
        for key in list(data):
            if key.endswith("_api_key") or key in {"telegram_bot_token", "ingest_secret", "calendar_ics_url"}:
                data[key] = "***" if data[key] else ""
        return data
