"""Build the configured LLM provider."""

from __future__ import annotations

from pathlib import Path

from ..config import Settings
from .base import LLMError, LLMProvider, FallbackProvider


def _build(provider: str, model: str, settings: Settings) -> LLMProvider:
    timeout = settings.llm_timeout_seconds
    if provider == "gemini":
        from .gemini import GeminiProvider

        return GeminiProvider(
            settings.gemini_api_key,
            model,
            timeout,
            settings.gemini_thinking_level,
            fallback_models=[m.strip() for m in settings.gemini_fallback_models.split(",") if m.strip()],
            rpm_per_model=settings.gemini_rpm_per_model,
            fast_models=[m.strip() for m in settings.gemini_fast_models.split(",") if m.strip()],
            smart_models=[m.strip() for m in settings.gemini_smart_models.split(",") if m.strip()],
            tier=settings.gemini_tier,
            usage_path=str(Path(settings.database_path).with_name("gemini_usage.json")),
        )
    if provider == "claude":
        from .claude import ClaudeProvider

        return ClaudeProvider(settings.anthropic_api_key, model, timeout)
    if provider == "openai":
        from .openai_compat import OpenAIProvider

        return OpenAIProvider(settings.openai_api_key, model, timeout)
    if provider == "openrouter":
        from .openai_compat import OpenRouterProvider

        return OpenRouterProvider(settings.openrouter_api_key, model, timeout)
    if provider == "fake":
        from .fake import FakeProvider

        return FakeProvider()
    raise LLMError(f"Unknown LLM provider '{provider}'")


def create_provider(settings: Settings) -> LLMProvider:
    primary = _build(settings.llm_provider, settings.llm_model, settings)
    if settings.llm_fallback_provider:
        fallback = _build(settings.llm_fallback_provider, settings.llm_fallback_model, settings)
        return FallbackProvider(primary, fallback)
    return primary
