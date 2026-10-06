"""User journeys through CoachService with a scripted LLM.

These verify the deterministic plumbing around the LLM: what gets stored, what
guidance the reply prompt receives, and that safety and failure paths hold.
"""

import pytest

from coach.llm.base import LLMError
from coach.llm.fake import FakeProvider
from coach.llm.base import MediaPart
from coach.engine.service import CoachService

from .conftest import at, empty_extraction, msg

DAY = "2026-10-05"  # Monday


def _reply_prompt(llm: FakeProvider) -> str:
    return llm.requests_for("reply")[-1].user_text


@pytest.mark.asyncio
async def test_natural_meal_message_is_logged_with_ranges(service, llm, store):
    llm.queue(
        "extract",
        empty_extraction(
            food_items=[
                {"name": "roti", "quantity": 3, "unit": "piece", "meal_slot": "lunch", "eaten": "eaten", "confidence": 0.9},
                {"name": "chicken curry", "quantity": 1, "unit": "katori", "meal_slot": "lunch", "eaten": "eaten", "confidence": 0.8},
                {"name": "sabzi", "meal_slot": "lunch", "eaten": "eaten", "confidence": 0.8},
            ]
        ),
    )
    out = await service.handle_message(msg("3 roti, chicken aur sabzi.", at(DAY, "13:30"), "tg:1"))
    assert out[0].text == "Theek hai, noted."
    rows = store.food_for_date(DAY)
    assert [r["food_key"] for r in rows] == ["roti", "chicken_curry", "sabzi"]
    assert all(r["data_status"] == "estimated" and r["kcal_low"] < r["kcal_high"] for r in rows)
    ctx = service.build_context(at(DAY, "13:31"))
    assert ctx["today"]["food"]["items_logged"] == 3
    assert ctx["today"]["steps"] == {"status": "unknown"}  # never fabricated


@pytest.mark.asyncio
async def test_precommitment_then_context_trigger_reminds_own_rule(service, llm, store):
    # Calm moment: user sets a rule.
    llm.queue(
        "extract",
        empty_extraction(
            commitments=[
                {
                    "kind": "precommitment",
                    "title": "Chess: coffee only",
                    "trigger_tag": "chess",
                    "action": "At chess, have coffee only; skip snacks",
                    "user_words": "Chess pe sirf coffee",
                }
            ],
            context=[{"tag": "chess", "timing": "past", "description": "talking about chess routine"}],
        ),
    )
    await service.handle_message(msg("Chess pe sirf coffee, snacks nahi. Yeh rule rakhte hain.", at(DAY, "10:00")))
    assert "Saved commitment" in _reply_prompt(llm)
    commitment = store.active_commitments()[0]
    assert commitment.trigger_tag == "chess"

    # Later: user mentions going to chess. The extractor is given the known tag vocabulary.
    llm.queue("extract", empty_extraction(context=[{"tag": "chess", "timing": "planned"}]))
    await service.handle_message(msg("Bhai aaj chess ja raha hu.", at(DAY, "17:00")))
    extract_prompt = llm.requests_for("extract")[-1].user_text
    assert '"chess"' in extract_prompt  # known tags offered for reuse
    prompt = _reply_prompt(llm)
    assert "Chess pe sirf coffee" in prompt and "before the decision" in prompt
    contextual = [r for r in store.interventions_on(DAY) if r["kind"] == "contextual"]
    assert len(contextual) == 1

    # Mentioning chess again the same day does not repeat the reminder.
    llm.queue("extract", empty_extraction(context=[{"tag": "chess", "timing": "now"}]))
    await service.handle_message(msg("Pahunch gaya chess club", at(DAY, "17:40")))
    assert "before the decision" not in _reply_prompt(llm)
    assert len([r for r in store.interventions_on(DAY) if r["kind"] == "contextual"]) == 1


@pytest.mark.asyncio
async def test_lapse_with_all_or_nothing_thinking_gets_recovery_guidance(service, llm, store):
    llm.queue(
        "extract",
        empty_extraction(
            food_items=[
                {"name": "coffee", "quantity": 1, "unit": "cup", "eaten": "eaten", "confidence": 0.9},
                {
                    "name": "sev parmal",
                    "quantity": 1,
                    "unit": "plate",
                    "eaten": "eaten",
                    "est_kcal_low": 250,
                    "est_kcal_high": 350,
                    "est_protein_low": 4,
                    "est_protein_high": 7,
                    "confidence": 0.7,
                },
            ],
            lapse={"detected": True, "all_or_nothing_thinking": True, "note": "snack at chess, says day is ruined"},
        ),
    )
    await service.handle_message(msg("Coffee pi aur sev parmal kha liya. Aaj ka din toh gaya.", at(DAY, "18:15")))
    rows = {r["item_name"]: r for r in store.food_for_date(DAY)}
    assert rows["coffee"]["nutrition_source"] == "food_table"
    assert rows["sev parmal"]["nutrition_source"] == "llm_estimate"
    prompt = _reply_prompt(llm)
    assert "Lapse protocol" in prompt and "all-or-nothing" in prompt and "no compensation" in prompt.lower()
    assert any(d["kind"] == "lapse" for d in store.recent_decisions())


@pytest.mark.asyncio
async def test_shaming_reply_is_rewritten_then_falls_back(service, llm, store):
    llm.queue("reply", "You're so lazy.", "Chill, next meal normal rakho.")
    out = await service.handle_message(msg("skip kar diya walk", at(DAY, "20:00")))
    assert out[0].text == "Chill, next meal normal rakho."
    assert "REJECTED" in llm.requests_for("reply")[-1].user_text

    llm.queue("reply", "You failed again.", "Starve tomorrow to fix it.")
    out = await service.handle_message(msg("aaj phir skip", at(DAY, "21:00")))
    assert "next decision" in out[0].text  # safe fallback
    assert any(d["kind"] == "safety_rewrite" for d in store.recent_decisions())


@pytest.mark.asyncio
async def test_duplicate_delivery_is_ignored(service, llm, store):
    await service.handle_message(msg("hello", at(DAY, "09:00"), "tg:42"))
    assert await service.handle_message(msg("hello", at(DAY, "09:00"), "tg:42")) == []
    assert len(llm.requests_for("extract")) == 1


@pytest.mark.asyncio
async def test_extraction_failure_still_replies_and_keeps_message(service, llm, store):
    llm.queue("extract", LLMError("timeout"))
    out = await service.handle_message(msg("2 anda khaye", at(DAY, "08:30")))
    assert out and out[0].text
    assert store.search_messages("anda")
    assert any(d["kind"] == "extraction_failed" for d in store.recent_decisions())


@pytest.mark.asyncio
async def test_voice_on_provider_without_audio_asks_for_text(store):
    class NoAudio(FakeProvider):
        supports_audio = False

    llm = NoAudio()
    llm.set_default("reply", "ok")
    service = CoachService(store, llm)
    out = await service.handle_message(
        msg("", at(DAY, "12:00"), kind="voice", media=[MediaPart(b"OggS...", "audio/ogg")])
    )
    assert "type it" in out[0].text


@pytest.mark.asyncio
async def test_strong_mode_requires_explicit_confirmation(service, llm, store):
    llm.queue("extract", empty_extraction(settings_request={"coaching_mode": "strong"}))
    out = await service.handle_message(msg("Mujhe strong accountability chahiye", at(DAY, "09:00")))
    assert store.get_profile()["coaching_mode"] == "normal"  # not changed by the LLM
    confirm = out[-1]
    assert ("Yes, enable", "mode:strong:confirm") in confirm.buttons
    with pytest.raises(PermissionError):
        service.set_mode(at(DAY, "09:01"), "strong")
    await service.handle_button("mode:strong:confirm", at(DAY, "09:02"))
    profile = store.get_profile()
    assert profile["coaching_mode"] == "strong" and profile["strong_mode_authorized"]
    service.set_mode(at(DAY, "09:03"), "normal")
    assert store.get_profile()["strong_mode_authorized"] is False


@pytest.mark.asyncio
async def test_weight_reply_guidance_focuses_on_trend(service, llm, store):
    llm.queue("extract", empty_extraction(body_metrics=[{"metric": "weight_kg", "value": 82.4, "confidence": 0.95}]))
    await service.handle_message(msg("Aaj weight 82.4", at(DAY, "07:45")))
    assert store.metric_series("weight_kg", "2026-10-01") == [(DAY, 82.4)]
    assert "TREND" in _reply_prompt(llm)


@pytest.mark.asyncio
async def test_facts_are_superseded_not_duplicated(service, llm, store):
    llm.queue("extract", empty_extraction(facts=[{"category": "routine", "key": "work_mode", "value": "WFH", "confidence": 0.9}]))
    await service.handle_message(msg("Main WFH karta hu", at(DAY, "09:00")))
    llm.queue("extract", empty_extraction(facts=[{"category": "routine", "key": "work mode", "value": "office 3 days", "confidence": 0.9}]))
    await service.handle_message(msg("Ab 3 din office jaana hai", at(DAY, "09:10")))
    active = [f for f in store.active_facts() if f["key"] == "work_mode"]
    assert len(active) == 1 and active[0]["value"] == "office 3 days"
    total = store.conn.execute("SELECT COUNT(*) FROM facts WHERE key='work_mode'").fetchone()[0]
    assert total == 2  # history kept
