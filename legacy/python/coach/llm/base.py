"""Provider-agnostic LLM interface.

Nothing outside `coach.llm` may import a vendor SDK. Callers build an
`LLMRequest` and receive an `LLMResponse`.
"""

from __future__ import annotations

import json
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any


class LLMError(RuntimeError):
    """Raised when a provider call fails or returns unusable output."""


class CapabilityError(LLMError):
    """Raised when a request needs a modality the provider does not support."""


@dataclass
class MediaPart:
    data: bytes
    mime_type: str  # e.g. "image/jpeg", "audio/ogg"

    @property
    def kind(self) -> str:
        return self.mime_type.split("/", 1)[0]


@dataclass
class ChatTurn:
    role: str  # "user" | "assistant"
    text: str


@dataclass
class LLMRequest:
    system: str
    user_text: str
    history: list[ChatTurn] = field(default_factory=list)
    media: list[MediaPart] = field(default_factory=list)
    json_schema: dict | None = None
    temperature: float = 0.4
    max_output_tokens: int = 4096  # thinking tokens count toward this on Gemini 3.x
    purpose: str = "generic"  # used for logging/observability and fakes


@dataclass
class LLMResponse:
    text: str
    provider: str
    model: str
    input_tokens: int | None = None
    output_tokens: int | None = None

    def json(self) -> Any:
        return parse_json_loose(self.text)


def parse_json_loose(text: str) -> Any:
    """Parse JSON that may be wrapped in markdown fences or prose."""
    cleaned = text.strip()
    fence = re.search(r"```(?:json)?\s*(.*?)```", cleaned, re.DOTALL)
    if fence:
        cleaned = fence.group(1).strip()
    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        start = min((i for i in (cleaned.find("{"), cleaned.find("[")) if i >= 0), default=-1)
        if start < 0:
            raise LLMError(f"Response is not JSON: {text[:200]!r}")
        end = max(cleaned.rfind("}"), cleaned.rfind("]"))
        try:
            return json.loads(cleaned[start : end + 1])
        except json.JSONDecodeError as exc:
            raise LLMError(f"Response is not valid JSON: {text[:200]!r}") from exc


class LLMProvider(ABC):
    name: str = "base"
    supports_audio: bool = False
    supports_images: bool = False

    def __init__(self, model: str):
        if not model:
            raise LLMError(f"No model configured for provider '{self.name}'. Set LLM_MODEL.")
        self.model = model

    def check_capabilities(self, request: LLMRequest) -> None:
        for part in request.media:
            if part.kind == "audio" and not self.supports_audio:
                raise CapabilityError(f"{self.name} does not support audio input")
            if part.kind == "image" and not self.supports_images:
                raise CapabilityError(f"{self.name} does not support image input")

    @abstractmethod
    async def generate(self, request: LLMRequest) -> LLMResponse:
        """Run one completion. Must raise LLMError on failure."""


class FallbackProvider(LLMProvider):
    """Tries the primary provider, then the fallback on LLMError (not on capability errors)."""

    def __init__(self, primary: LLMProvider, fallback: LLMProvider):
        self.primary = primary
        self.fallback = fallback
        self.name = f"{primary.name}+{fallback.name}"
        self.model = primary.model
        self.supports_audio = primary.supports_audio or fallback.supports_audio
        self.supports_images = primary.supports_images or fallback.supports_images

    async def generate(self, request: LLMRequest) -> LLMResponse:
        try:
            self.primary.check_capabilities(request)
            return await self.primary.generate(request)
        except LLMError:
            self.fallback.check_capabilities(request)
            return await self.fallback.generate(request)
