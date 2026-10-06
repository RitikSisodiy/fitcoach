"""Anthropic Claude provider. Supports text and images; no audio input.

Structured output is obtained by forcing a single tool call whose input schema
is the requested JSON schema.
"""

from __future__ import annotations

import base64
import json

from .base import LLMError, LLMProvider, LLMRequest, LLMResponse


class ClaudeProvider(LLMProvider):
    name = "claude"
    supports_audio = False
    supports_images = True

    def __init__(self, api_key: str, model: str, timeout_seconds: float = 60.0):
        super().__init__(model)
        if not api_key:
            raise LLMError("ANTHROPIC_API_KEY is not set")
        import anthropic

        self._client = anthropic.AsyncAnthropic(api_key=api_key, timeout=timeout_seconds)

    async def generate(self, request: LLMRequest) -> LLMResponse:
        self.check_capabilities(request)
        messages: list[dict] = [{"role": t.role, "content": t.text} for t in request.history]
        content: list[dict] = []
        for m in request.media:
            content.append(
                {
                    "type": "image",
                    "source": {
                        "type": "base64",
                        "media_type": m.mime_type,
                        "data": base64.b64encode(m.data).decode(),
                    },
                }
            )
        content.append({"type": "text", "text": request.user_text or "(no text)"})
        messages.append({"role": "user", "content": content})

        kwargs: dict = {
            "model": self.model,
            "system": request.system,
            "messages": messages,
            "max_tokens": request.max_output_tokens,
            "temperature": request.temperature,
        }
        if request.json_schema is not None:
            kwargs["tools"] = [
                {
                    "name": "emit_result",
                    "description": "Return the structured result.",
                    "input_schema": request.json_schema,
                }
            ]
            kwargs["tool_choice"] = {"type": "tool", "name": "emit_result"}
        try:
            result = await self._client.messages.create(**kwargs)
        except Exception as exc:
            raise LLMError(f"Claude call failed: {exc}") from exc

        text = ""
        for block in result.content:
            if request.json_schema is not None and block.type == "tool_use":
                text = json.dumps(block.input)
                break
            if block.type == "text":
                text += block.text
        if not text:
            raise LLMError("Claude returned an empty response")
        return LLMResponse(
            text=text,
            provider=self.name,
            model=self.model,
            input_tokens=getattr(result.usage, "input_tokens", None),
            output_tokens=getattr(result.usage, "output_tokens", None),
        )
