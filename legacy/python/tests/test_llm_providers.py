"""Provider abstraction tests. SDK network calls are replaced with stubs."""

import json
from types import SimpleNamespace

import pytest

from coach.config import Settings
from coach.llm.base import CapabilityError, FallbackProvider, LLMError, LLMRequest, MediaPart, parse_json_loose
from coach.llm.factory import create_provider
from coach.llm.fake import FakeProvider

SCHEMA = {"type": "object", "properties": {"a": {"type": "integer"}}, "required": ["a"]}


def test_parse_json_loose_handles_fences_and_prose():
    assert parse_json_loose('```json\n{"a": 1}\n```') == {"a": 1}
    assert parse_json_loose('Here you go: {"a": 2} hope that helps') == {"a": 2}
    with pytest.raises(LLMError):
        parse_json_loose("no json here")


def test_factory_rejects_unknown_and_missing_config():
    with pytest.raises(LLMError):
        create_provider(Settings(llm_provider="nope", llm_model="x"))
    with pytest.raises(LLMError):
        create_provider(Settings(llm_provider="gemini", llm_model="m", gemini_api_key=""))
    with pytest.raises(LLMError):
        create_provider(Settings(llm_provider="gemini", llm_model="", gemini_api_key="k"))


def test_factory_builds_each_provider_without_network():
    s = Settings(gemini_api_key="g", anthropic_api_key="a", openai_api_key="o", openrouter_api_key="r", llm_model="m")
    for name in ("gemini", "claude", "openai", "openrouter"):
        s.llm_provider = name
        assert create_provider(s).name == name


def test_settings_redacts_secrets():
    s = Settings(gemini_api_key="secret-key", telegram_bot_token="tok")
    red = s.redacted()
    assert red["gemini_api_key"] == "***" and red["telegram_bot_token"] == "***"
    assert "secret-key" not in json.dumps(red)


@pytest.mark.asyncio
async def test_gemini_request_mapping():
    from coach.llm.gemini import GeminiProvider

    p = GeminiProvider("key", "gemini-test")
    captured = {}

    async def fake_generate(model, contents, config):
        captured.update(model=model, contents=contents, config=config)
        return SimpleNamespace(text='{"a": 1}', usage_metadata=SimpleNamespace(prompt_token_count=10, candidates_token_count=3))

    p._client = SimpleNamespace(aio=SimpleNamespace(models=SimpleNamespace(generate_content=fake_generate)))
    req = LLMRequest(system="sys", user_text="hi", media=[MediaPart(b"ogg", "audio/ogg")], json_schema=SCHEMA)
    res = await p.generate(req)
    assert res.json() == {"a": 1} and res.input_tokens == 10
    assert captured["config"].response_mime_type == "application/json"
    assert captured["config"].response_json_schema == SCHEMA
    assert captured["config"].system_instruction == "sys"
    assert len(captured["contents"][-1].parts) == 2  # audio + text in one call


@pytest.mark.asyncio
async def test_claude_structured_output_via_forced_tool():
    from coach.llm.claude import ClaudeProvider

    p = ClaudeProvider("key", "claude-test")
    captured = {}

    async def fake_create(**kwargs):
        captured.update(kwargs)
        return SimpleNamespace(
            content=[SimpleNamespace(type="tool_use", input={"a": 5})],
            usage=SimpleNamespace(input_tokens=1, output_tokens=1),
        )

    p._client = SimpleNamespace(messages=SimpleNamespace(create=fake_create))
    res = await p.generate(LLMRequest(system="s", user_text="u", json_schema=SCHEMA))
    assert res.json() == {"a": 5}
    assert captured["tool_choice"] == {"type": "tool", "name": "emit_result"}
    with pytest.raises(CapabilityError):
        await p.generate(LLMRequest(system="s", user_text="u", media=[MediaPart(b"x", "audio/ogg")]))


@pytest.mark.asyncio
async def test_openai_compatible_mapping_and_openrouter_base_url():
    from coach.llm.openai_compat import OPENROUTER_BASE_URL, OpenAIProvider, OpenRouterProvider

    p = OpenAIProvider("key", "gpt-test")
    captured = {}

    async def fake_create(**kwargs):
        captured.update(kwargs)
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content='{"a": 3}'))], usage=None)

    p._client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    res = await p.generate(LLMRequest(system="s", user_text="u", media=[MediaPart(b"img", "image/jpeg")], json_schema=SCHEMA))
    assert res.json() == {"a": 3}
    assert captured["messages"][0] == {"role": "system", "content": "s"}
    assert captured["response_format"]["type"] == "json_schema"
    assert captured["messages"][-1]["content"][0]["type"] == "image_url"
    assert str(OpenRouterProvider("k", "m")._client.base_url).rstrip("/") == OPENROUTER_BASE_URL


@pytest.mark.asyncio
async def test_fallback_provider_switches_on_error_and_capability():
    primary, fallback = FakeProvider("p"), FakeProvider("f")
    primary.queue("x", LLMError("down"))
    fallback.queue("x", "from fallback")
    fp = FallbackProvider(primary, fallback)
    assert (await fp.generate(LLMRequest(system="", user_text="", purpose="x"))).text == "from fallback"

    class NoAudio(FakeProvider):
        supports_audio = False

    fp2 = FallbackProvider(NoAudio("p"), fallback)
    fallback.queue("y", "audio handled")
    res = await fp2.generate(LLMRequest(system="", user_text="", media=[MediaPart(b"", "audio/ogg")], purpose="y"))
    assert res.text == "audio handled"


@pytest.mark.asyncio
async def test_openai_retries_without_temperature_and_uses_completion_tokens():
    from coach.llm.openai_compat import OpenAIProvider

    p = OpenAIProvider("key", "reasoning-model")
    calls = []

    async def fake_create(**kwargs):
        calls.append(dict(kwargs))
        if "temperature" in kwargs:
            raise RuntimeError("Unsupported value: 'temperature' does not support 0.4")
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content="hi"))], usage=None)

    p._client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=fake_create)))
    res = await p.generate(LLMRequest(system="s", user_text="u"))
    assert res.text == "hi" and len(calls) == 2
    assert "max_completion_tokens" in calls[0] and "max_tokens" not in calls[0]


@pytest.mark.asyncio
async def test_gemini_falls_through_models_on_quota_and_overload():
    from coach.llm.gemini import GeminiProvider

    p = GeminiProvider("key", "m1", fallback_models=["m2", "m3"], tier="paid")
    calls = []

    async def fake_generate(model, contents, config):
        calls.append(model)
        if model == "m1":
            raise RuntimeError("429 RESOURCE_EXHAUSTED quota")
        if model == "m2":
            raise RuntimeError("503 UNAVAILABLE high demand")
        return SimpleNamespace(text="ok", usage_metadata=None)

    p._client = SimpleNamespace(aio=SimpleNamespace(models=SimpleNamespace(generate_content=fake_generate)))
    res = await p.generate(LLMRequest(system="s", user_text="u"))
    assert res.text == "ok" and res.model == "m3" and calls == ["m1", "m2", "m3"]


@pytest.mark.asyncio
async def test_gemini_non_retryable_error_raises_immediately():
    from coach.llm.gemini import GeminiProvider

    p = GeminiProvider("key", "m1", fallback_models=["m2"])

    async def fake_generate(model, contents, config):
        raise RuntimeError("400 INVALID_ARGUMENT bad schema")

    p._client = SimpleNamespace(aio=SimpleNamespace(models=SimpleNamespace(generate_content=fake_generate)))
    with pytest.raises(LLMError):
        await p.generate(LLMRequest(system="s", user_text="u"))


@pytest.mark.asyncio
async def test_routes_by_purpose_and_respects_daily_quota(tmp_path):
    from coach.llm.gemini import GeminiProvider, free_tier_limits

    assert free_tier_limits("gemini-3.8-flash") == (5, 20)
    assert free_tier_limits("gemini-3.5-flash-lite") == (15, 500)
    usage = tmp_path / "u.json"
    p = GeminiProvider("key", "x", fast_models=["lite"], smart_models=["flash"], usage_path=str(usage))
    used = []

    async def fake_generate(model, contents, config):
        used.append(model)
        return SimpleNamespace(text="ok", usage_metadata=None)

    p._client = SimpleNamespace(aio=SimpleNamespace(models=SimpleNamespace(generate_content=fake_generate)))
    await p.generate(LLMRequest(system="s", user_text="u", purpose="decide"))
    await p.generate(LLMRequest(system="s", user_text="u", purpose="review"))
    assert used == ["lite", "flash"]
    p.budgets["flash"].day_count = 20  # daily quota used up
    await p.generate(LLMRequest(system="s", user_text="u", purpose="review"))
    assert used[-1] == "lite"
    # persisted: a new process continues the count instead of starting from zero
    p2 = GeminiProvider("key", "x", fast_models=["lite"], smart_models=["flash"], usage_path=str(usage))
    assert p2.budgets["lite"].day_count == 2


@pytest.mark.asyncio
async def test_daily_quota_error_blocks_model_for_the_day():
    from coach.llm.gemini import GeminiProvider

    p = GeminiProvider("key", "x", fast_models=["a", "b"], smart_models=[])
    used = []

    async def fake_generate(model, contents, config):
        used.append(model)
        if model == "a":
            raise RuntimeError("429 RESOURCE_EXHAUSTED GenerateRequestsPerDayPerProjectPerModel-FreeTier")
        return SimpleNamespace(text="ok", usage_metadata=None)

    p._client = SimpleNamespace(aio=SimpleNamespace(models=SimpleNamespace(generate_content=fake_generate)))
    await p.generate(LLMRequest(system="s", user_text="u", purpose="decide"))
    await p.generate(LLMRequest(system="s", user_text="u", purpose="decide"))
    assert used == ["a", "b", "b"]


@pytest.mark.asyncio
async def test_slow_model_is_abandoned_and_cooled_down(monkeypatch):
    import asyncio as _a

    from coach.llm import gemini as g

    monkeypatch.setattr(g, "CALL_TIMEOUT_SECONDS", 0.05)
    p = g.GeminiProvider("key", "x", fast_models=["slow", "quick"], smart_models=[])
    used = []

    async def fake_generate(model, contents, config):
        used.append(model)
        if model == "slow":
            await _a.sleep(1)
        return SimpleNamespace(text="ok", usage_metadata=None)

    p._client = SimpleNamespace(aio=SimpleNamespace(models=SimpleNamespace(generate_content=fake_generate)))
    res = await p.generate(LLMRequest(system="s", user_text="u", purpose="reply"))
    assert res.model == "quick"
    await p.generate(LLMRequest(system="s", user_text="u", purpose="reply"))
    assert used == ["slow", "quick", "quick"]  # slow one skipped while cooling down
