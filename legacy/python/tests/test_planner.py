"""Proactive coaching: gating, escalation, adaptation, and button outcomes."""

import random
from datetime import timedelta

import pytest

from coach.engine.policy import SlotLearner, analyse_commitment, decide_level

from .conftest import at

MON = "2026-10-05"


def add_walk(store, now=None, **kw):
    values = dict(
        kind="habit",
        title="Evening walk",
        action="Walk 30 minutes",
        versions=["30 min walk", "20 min walk", "10 min walk", "5 min walk"],
        schedule_days="daily",
        window_start="18:00",
        window_end="19:00",
        enforcement="normal",
        user_words="Roz shaam ko walk",
    )
    values.update(kw)
    return store.add_commitment(now or at("2026-09-20", "09:00"), **values)


def day(offset: int) -> str:
    from datetime import date

    return (date.fromisoformat(MON) + timedelta(days=offset)).isoformat()


@pytest.mark.asyncio
async def test_nudge_only_inside_window_and_once(service, store):
    add_walk(store)
    assert await service.tick(at(MON, "16:00")) == []
    out = await service.tick(at(MON, "18:30"))  # last slot in window -> always sends
    assert len(out) == 1
    assert [b[0] for b in out[0].buttons] == ["Done", "Smaller version", "Later", "Skip today"]
    assert await service.tick(at(MON, "18:45")) == []  # open nudge, no repeat
    iv = store.interventions_on(MON)[0]
    assert iv["level"] == 1 and "level 1" in iv["reason"] and iv["intent"] == "commitment:1"


@pytest.mark.asyncio
async def test_no_nudge_when_already_done(service, store, llm):
    cid = add_walk(store)
    store.log_commitment(at(MON, "17:00"), cid, "done", "user_reported")
    assert await service.tick(at(MON, "18:30")) == []


@pytest.mark.asyncio
async def test_quiet_hours_pause_and_budget(service, store):
    add_walk(store, window_start="23:00", window_end="23:59")
    assert await service.tick(at(MON, "23:30")) == []  # quiet hours (22:30-07:30)

    store.conn.execute("DELETE FROM commitments")
    add_walk(store)
    service.pause(at(MON, "10:00"), 1)
    assert await service.tick(at(MON, "18:30")) == []
    service.resume(at(MON, "18:31"))
    store.set_profile(at(MON, "18:31"), daily_message_budget=0)
    assert await service.tick(at(MON, "18:31")) == []
    store.set_profile(at(MON, "18:32"), daily_message_budget=3)
    assert len(await service.tick(at(MON, "18:32"))) == 1


@pytest.mark.asyncio
async def test_gentle_mode_budget_is_one(service, store):
    add_walk(store, window_start="08:00", window_end="09:00")
    add_walk(store, title="Protein breakfast", window_start="10:00", window_end="11:00")
    service.set_mode(at(MON, "07:00"), "gentle")
    assert len(await service.tick(at(MON, "08:30"))) == 1
    assert await service.tick(at(MON, "10:30")) == []


@pytest.mark.asyncio
async def test_ignored_nudge_expires_and_teaches_learner(service, store):
    cid = add_walk(store)
    await service.tick(at(MON, "18:30"))
    await service.tick(at(MON, "20:31"))  # > 120 min later
    iv = store.interventions_on(MON)[0]
    assert iv["status"] == "ignored"
    alpha, beta = store.get_slot_stats(cid)["18:30"]
    assert beta > alpha


@pytest.mark.asyncio
async def test_done_button_records_outcome_and_rewards_slot(service, store):
    cid = add_walk(store)
    out = await service.tick(at(MON, "18:30"))
    iid = out[0].intervention_id
    reply = await service.handle_button(f"iv:{iid}:done", at(MON, "18:50"))
    assert "Logged" in reply.text
    assert store.commitment_outcome_on(cid, MON) == "done"
    assert store.get_intervention(iid)["status"] == "acted"
    alpha, beta = store.get_slot_stats(cid)["18:30"]
    assert alpha > beta


@pytest.mark.asyncio
async def test_late_done_after_expiry_still_counts(service, store):
    cid = add_walk(store)
    out = await service.tick(at(MON, "18:30"))
    await service.tick(at(MON, "21:00"))  # expired -> ignored
    await service.handle_button(f"iv:{out[0].intervention_id}:done", at(MON, "21:30"))
    assert store.commitment_outcome_on(cid, MON) == "done"


@pytest.mark.asyncio
async def test_skip_asks_reason_and_stores_it(service, store):
    cid = add_walk(store)
    out = await service.tick(at(MON, "18:30"))
    iid = out[0].intervention_id
    ask = await service.handle_button(f"iv:{iid}:skip", at(MON, "18:35"))
    assert len(ask.buttons) == 4
    await service.handle_reason(f"rs:{iid}:bad_timing", at(MON, "18:36"))
    rows = store.commitment_history(cid, MON)
    assert len(rows) == 1 and rows[0]["reason_category"] == "bad_timing"


@pytest.mark.asyncio
async def test_later_gives_exactly_one_follow_up(service, store):
    add_walk(store, window_start="18:00", window_end="20:00")
    service.learner = SlotLearner(store, random.Random(0))
    first = None
    for minute in range(0, 120, 15):
        t = at(MON, "18:00") + timedelta(minutes=minute)
        out = await service.tick(t)
        if out:
            first = (out[0], t)
            break
    assert first is not None
    msg0, t0 = first
    await service.handle_button(f"iv:{msg0.intervention_id}:later", t0 + timedelta(minutes=5))
    assert await service.tick(t0 + timedelta(minutes=30)) == []  # snooze not over
    store.set_profile(t0, daily_message_budget=4)
    service.min_gap_minutes = 0
    follow = await service.tick(t0 + timedelta(minutes=70))
    assert len(follow) == 1
    await service.handle_button(f"iv:{follow[0].intervention_id}:later", t0 + timedelta(minutes=72))
    assert await service.tick(t0 + timedelta(minutes=140)) == []  # no third nudge


def _miss_days(store, cid, offsets):
    for off in offsets:
        store.log_commitment(at(day(off), "20:00"), cid, "skipped", "button", reason_category="dont_want")


def test_escalation_ladder_levels_and_versions(store):
    cid = add_walk(store)
    c = store.get_commitment(cid)
    profile = store.get_profile()

    pattern = analyse_commitment(store, c, MON)
    assert decide_level(c, pattern, profile, MON).level == 1  # missed days before creation don't exist

    _miss_days(store, cid, [-1])
    d = decide_level(c, analyse_commitment(store, c, MON), profile, MON)
    assert d.level == 2 and d.version == "20 min walk"  # never miss twice -> smaller version

    _miss_days(store, cid, [-2, -3])
    p = analyse_commitment(store, c, MON)
    assert p["consecutive_misses"] == 3 and p["dominant_skip_reason"] == "dont_want"
    normal = decide_level(c, p, profile, MON)
    assert normal.level == 3 and "capped" in normal.reason  # normal caps at accountability
    acc = decide_level(c, p, profile | {"coaching_mode": "accountability"}, MON)
    assert acc.level == 4 and acc.version == "5 min walk"
    gentle = decide_level(c, p, profile | {"coaching_mode": "gentle"}, MON)
    assert gentle.level == 1


def test_strong_enforcement_requires_all_three_conditions(store):
    cid = add_walk(store, enforcement="strong")
    c = store.get_commitment(cid)
    _miss_days(store, cid, [-1])
    p = analyse_commitment(store, c, MON)
    base = store.get_profile()
    assert decide_level(c, p, base | {"coaching_mode": "strong"}, MON).level < 5  # not authorised
    assert decide_level(c, p, base | {"coaching_mode": "strong", "strong_mode_authorized": True}, MON).level == 5
    normal_commitment = store.get_commitment(add_walk(store))
    store.log_commitment(at(day(-1), "20:00"), normal_commitment.id, "skipped", "button")
    p2 = analyse_commitment(store, normal_commitment, MON)
    assert decide_level(normal_commitment, p2, base | {"coaching_mode": "strong", "strong_mode_authorized": True}, MON).level < 5


def test_done_resets_the_ladder(store):
    cid = add_walk(store)
    c = store.get_commitment(cid)
    _miss_days(store, cid, [-3, -2])
    store.log_commitment(at(day(-1), "18:40"), cid, "smaller", "button", "10 min walk")
    d = decide_level(c, analyse_commitment(store, c, MON), store.get_profile(), MON)
    assert d.version == "30 min walk" and d.level <= 2


@pytest.mark.asyncio
async def test_repeatedly_ignored_nudges_back_off_instead_of_spamming(service, store):
    cid = add_walk(store)
    # Four ignored scheduled nudges on previous days, including yesterday.
    for off in (-4, -3, -2, -1):
        store.add_intervention(
            at(day(off), "18:30"),
            commitment_id=cid,
            kind="scheduled",
            level=1,
            slot="18:30",
            style="level1",
            version_offered="30 min walk",
            message_text="walk?",
            reason="test",
        )
    for row in store.open_interventions():
        store.update_intervention(at(MON, "08:00"), row["id"], "ignored")
    assert await service.tick(at(MON, "18:30")) == []
    assert any(d["kind"] == "back_off" for d in store.recent_decisions())


def test_slot_learner_prefers_slot_that_works(store):
    cid = add_walk(store)
    learner = SlotLearner(store, random.Random(1))
    now = at(MON, "12:00")
    for _ in range(6):
        learner.update(now, cid, "18:00", 0.0)
        learner.update(now, cid, "18:30", 1.0)
    picks = sum(learner.should_send_now(cid, "18:00", ["18:00", "18:30"])[0] for _ in range(200))
    assert picks < 60  # mostly waits for the better 18:30 slot
    assert learner.should_send_now(cid, "18:30", ["18:30"])[0]  # last slot always sends


def test_learner_forgets_old_evidence(store):
    cid = add_walk(store)
    learner = SlotLearner(store, random.Random(1))
    now = at(MON, "12:00")
    for _ in range(30):
        learner.update(now, cid, "18:00", 1.0)
    alpha, beta = store.get_slot_stats(cid)["18:00"]
    assert alpha < 12  # bounded by the decay, so habits can be re-learned


@pytest.mark.asyncio
async def test_weekly_review_sunday_evening_once(service, store, llm):
    cid = add_walk(store)
    store.log_commitment(at("2026-10-10", "18:30"), cid, "done", "button")
    sunday = "2026-10-11"
    assert await service.tick(at("2026-10-10", "19:30")) == []  # Saturday, outside walk window
    out = await service.tick(at(sunday, "19:30"))
    assert len(out) == 1 and "Good week" in out[0].text
    stats_prompt = llm.requests_for("review")[-1].user_text
    assert "target_change_allowed" in stats_prompt
    later = await service.tick(at(sunday, "21:00"))
    assert all("Good week" not in m.text for m in later)  # no second review


@pytest.mark.asyncio
async def test_llm_outage_uses_template_nudge_and_defers_review(service, store, llm):
    from coach.llm.base import LLMError

    add_walk(store)
    llm.queue("decide", LLMError("503"))
    out = await service.tick(at(MON, "18:30"))
    assert out[0].text == "Evening walk: how about 30 min walk now?"

    llm.queue("review", LLMError("503"))
    sunday = "2026-10-11"
    store.log_commitment(at("2026-10-10", "18:30"), 1, "done", "button")
    assert await service.tick(at(sunday, "19:30")) == []
    assert any(d["kind"] == "review_deferred" for d in store.recent_decisions())
    assert len(await service.tick(at(sunday, "21:15"))) == 1  # retried once the LLM is back
