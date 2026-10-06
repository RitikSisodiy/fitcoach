"""Deterministic provider for tests and offline development.

Responses are scripted per `purpose` ("extract", "reply", "nudge", ...). Each
script entry is either a string, a dict (serialised to JSON), or a callable
receiving the request.
"""

from __future__ import annotations

import json
from collections import defaultdict, deque
from typing import Any, Callable

from .base import LLMError, LLMProvider, LLMRequest, LLMResponse

Scripted = str | dict | Callable[[LLMRequest], Any]


class FakeProvider(LLMProvider):
    name = "fake"
    supports_audio = True
    supports_images = True

    def __init__(self, model: str = "fake-model"):
        super().__init__(model)
        self.queues: dict[str, deque[Scripted]] = defaultdict(deque)
        self.defaults: dict[str, Scripted] = {}
        self.requests: list[LLMRequest] = []
        self.auto_quote = True

    def queue(self, purpose: str, *responses: Scripted) -> None:
        self.queues[purpose].extend(responses)

    def set_default(self, purpose: str, response: Scripted) -> None:
        self.defaults[purpose] = response

    def requests_for(self, purpose: str) -> list[LLMRequest]:
        return [r for r in self.requests if r.purpose == purpose]

    async def generate(self, request: LLMRequest) -> LLMResponse:
        self.check_capabilities(request)
        self.requests.append(request)
        if self.queues[request.purpose]:
            item = self.queues[request.purpose].popleft()
        elif request.purpose in self.defaults:
            item = self.defaults[request.purpose]
        else:
            raise LLMError(f"FakeProvider has no scripted response for purpose '{request.purpose}'")
        if callable(item):
            item = item(request)
        if isinstance(item, Exception):
            raise item
        if request.purpose == "extract" and isinstance(item, dict) and self.auto_quote:
            item = _with_quotes(item, request.user_text)
        text = item if isinstance(item, str) else json.dumps(item)
        return LLMResponse(text=text, provider=self.name, model=self.model)


QUOTED_LISTS = ("food_items", "commitments", "food_corrections", "commitment_changes", "inferred_confirmations")
QUOTED_OBJECTS = ("profile_updates", "settings_request")


def _with_quotes(item: dict, prompt: str) -> dict:
    """Scripted extractions represent what a model *should* return, so they quote the user's message."""
    message = prompt.split("USER_MESSAGE:", 1)[-1].strip()
    out = dict(item)
    for key in QUOTED_LISTS:
        out[key] = [dict(x, quote=x.get("quote", message)) if isinstance(x, dict) else x for x in (item.get(key) or [])]
    for key in QUOTED_OBJECTS:
        if isinstance(item.get(key), dict) and item[key]:
            out[key] = dict(item[key], quote=item[key].get("quote", message))
    return out
