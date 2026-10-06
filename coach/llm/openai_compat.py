"""OpenAI and OpenRouter providers (OpenAI-compatible Chat Completions API)."""

from __future__ import annotations

import base64

from .base import LLMError, LLMProvider, LLMRequest, LLMResponse

OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"


class OpenAIProvider(LLMProvider):
    name = "openai"
    # OpenAI reasoning models reject `max_tokens`; OpenRouter normalises `max_tokens` across vendors.
    token_param = "max_completion_tokens"
    supports_audio = False  # audio goes through a separate transcription model; not wired yet
    supports_images = True

    def __init__(
        self,
        api_key: str,
        model: str,
        timeout_seconds: float = 60.0,
        base_url: str | None = None,
    ):
        super().__init__(model)
        if not api_key:
            raise LLMError(f"API key for {self.name} is not set")
        import openai

        self._client = openai.AsyncOpenAI(api_key=api_key, base_url=base_url, timeout=timeout_seconds)

    async def generate(self, request: LLMRequest) -> LLMResponse:
        self.check_capabilities(request)
        messages: list[dict] = [{"role": "system", "content": request.system}]
        messages += [{"role": t.role, "content": t.text} for t in request.history]
        content: list[dict] = []
        for m in request.media:
            data_url = f"data:{m.mime_type};base64,{base64.b64encode(m.data).decode()}"
            content.append({"type": "image_url", "image_url": {"url": data_url}})
        content.append({"type": "text", "text": request.user_text or "(no text)"})
        messages.append({"role": "user", "content": content})

        kwargs: dict = {
            "model": self.model,
            "messages": messages,
            "temperature": request.temperature,
            self.token_param: request.max_output_tokens,
        }
        if request.json_schema is not None:
            kwargs["response_format"] = {
                "type": "json_schema",
                "json_schema": {"name": "result", "schema": request.json_schema},
            }
        try:
            try:
                result = await self._client.chat.completions.create(**kwargs)
            except Exception as exc:
                # Some models only accept the default temperature; retry once without it.
                if "temperature" not in str(exc).lower():
                    raise
                kwargs.pop("temperature", None)
                result = await self._client.chat.completions.create(**kwargs)
        except Exception as exc:
            raise LLMError(f"{self.name} call failed: {exc}") from exc
        text = (result.choices[0].message.content or "") if result.choices else ""
        if not text:
            raise LLMError(f"{self.name} returned an empty response")
        usage = getattr(result, "usage", None)
        return LLMResponse(
            text=text,
            provider=self.name,
            model=self.model,
            input_tokens=getattr(usage, "prompt_tokens", None),
            output_tokens=getattr(usage, "completion_tokens", None),
        )


class OpenRouterProvider(OpenAIProvider):
    name = "openrouter"
    token_param = "max_tokens"

    def __init__(self, api_key: str, model: str, timeout_seconds: float = 60.0):
        super().__init__(api_key, model, timeout_seconds, base_url=OPENROUTER_BASE_URL)
