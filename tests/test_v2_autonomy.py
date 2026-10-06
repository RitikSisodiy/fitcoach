"""v0.2: autonomy without user setup, passive signals, engagement adaptation, patterns."""

from datetime import date, timedelta

import pytest

from coach.analytics.habits import day_coverage, habitual_meals
from coach.analytics.patterns import mine_patterns, predicted_contexts, wilson_lower
from coach.engine.engagement import compute_engagement
from coach.ingest.calendar import ingest_ics, summarise_day
from coach.ingest.health_connect import ingest_payload
from coach.ingest.notifications import ingest_notification, parse_notification

from .conftest import at, empty_extraction, msg

MON = "2026-10-05"


def d(offset: int, base: str = MON) -> str:
    return (date.fromisoformat(base) + timedelta(days=offset)).isoformat()


def log_lunch(store, day: str, items=(("roti", 2, "piece"), ("dal", 1, "katori"), ("sabzi", 1, "katori"))):
    for name, q, unit in items:
        store.add_food_event(
            at(day, "14:00"), at(day, "13:30"), meal_slot="lunch", item_name=name, food_key=name, quantity=q, unit=unit,
            kcal_low=100, kcal_high=150, protein_low=3, protein_high=5, nutrition_source="food_table",
            data_status="estimated", confidence=0.8,
        )


# ------------------------------------------------------------------ notifications
@pytest.mark.parametrize(
    "package,title,text,kind",
    [
        ("in.swiggy.android", "Order delivered", "Your order from Burger Singh has been delivered.", "food_order"),
        ("com.phonepe.app", "Payment successful", "₹40 paid to SHARMA CHAAT CORNER", "small_payment"),
        ("sms", "VM-HDFCBK", "Sent Rs.30.00 From HDFC Bank A/C *1234 To RAMU TEA STALL On 05/10/26 Ref 612345678901", "small_payment"),
    ],
)
def test_food_signals_parsed(package, title, text, kind):
    sig = parse_notification(package, title, text, at(MON, "18:10"), "Asia/Kolkata")
    assert sig is not None and sig.kind == kind


@pytest.mark.parametrize(
    "package,title,text",
    [
        ("com.phonepe.app", "Payment", "₹15,000 paid to LANDLORD"),  # too large to be a snack
        ("sms", "VM-HDFCBK", "Rs.40 credited to your A/C from RAMU"),  # credit
        ("sms", "VM-HDFCBK", "Your OTP for payment of Rs.40 is 123456"),  # OTP
        ("in.swiggy.android", "Flat 60% off!", "Hungry? Order now"),  # promotion
        ("com.whatsapp", "Rahul", "paid to ramesh 40 rs"),  # not a forwarded source we use
    ],
)
def test_non_food_signals_ignored(package, title, text):
    assert parse_notification(package, title, text, at(MON, "18:10"), "Asia/Kolkata") is None


def test_order_status_notifications_deduplicate(store):
    now = at(MON, "20:30")
    for status in ("Order placed", "Out for delivery", "Order delivered"):
        ingest_notification(store, {"package": "in.swiggy.android", "title": status, "text": "Your order from Biryani Blues is on its way", "time": "2026-10-05T14:40:00Z"}, now)
    assert len(store.pending_inferred()) == 1


def test_payee_label_learning(store):
    now = at(MON, "18:20")
    r = ingest_notification(store, {"package": "com.phonepe.app", "title": "Paid", "text": "₹40 paid to SHARMA CHAAT", "time": "2026-10-05T12:40:00Z"}, now)
    row = store.get_inferred(r["id"])
    store.set_payee_label(now, row["payee_key"], False)
    again = ingest_notification(store, {"package": "com.phonepe.app", "title": "Paid", "text": "₹50 paid to SHARMA CHAAT", "time": "2026-10-06T12:40:00Z"}, now)
    assert again["status"] == "ignored"


# ------------------------------------------------------------------ calendar
def test_calendar_long_day_and_free_slots(store):
    ics = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:1
DTSTART:20261005T033000Z
DTEND:20261005T073000Z
SUMMARY:secret
END:VEVENT
BEGIN:VEVENT
UID:2
DTSTART:20261005T080000Z
DTEND:20261005T133000Z
SUMMARY:secret
END:VEVENT
END:VCALENDAR"""
    ingest_ics(store, ics, at(MON, "08:00"), days_ahead=0)
    day = store.calendar_day(MON)
    assert day["meeting_hours"] == 9.5
    assert day["first_start"] == "09:00" and day["last_end"] == "19:00"
    assert ["13:00", "13:30"] in day["free_slots"]
    assert "secret" not in str(day)  # titles never stored


def test_summarise_empty_day():
    assert summarise_day([], date(2026, 10, 5), "Asia/Kolkata")["free_slots"] == []


# ------------------------------------------------------------------ habits + recap
def test_habitual_lunch_and_coverage(store):
    for off in (-5, -4, -3, -2):
        log_lunch(store, d(off))
    habits = habitual_meals(store, MON)
    assert habits["lunch"].occurrences == 4
    assert "roti" in habits["lunch"].describe()
    assert day_coverage(store, MON)["missing"] == ["breakfast", "lunch", "dinner"]


@pytest.mark.asyncio
async def test_lazy_user_gets_recap_without_any_setup_and_one_tap_fills_it(service, store, llm):
    for off in (-5, -4, -3, -2):
        log_lunch(store, d(off))
    out = await service.tick(at(MON, "21:10"))
    assert len(out) == 1 and out[0].text.startswith("[recap]")
    labels = [b[0] for b in out[0].buttons]
    assert "All usual" in labels
    reply = await service.handle_button(f"rc:{out[0].intervention_id}:usual", at(MON, "21:12"))
    assert "lunch" in reply.text
    lunch = [r for r in store.food_for_date(MON) if r["meal_slot"] == "lunch"]
    assert len(lunch) == 3 and all(r["source"] == "default_confirmed" for r in lunch)
    assert all(r["confidence"] < 0.8 for r in lunch)  # defaults are lower-confidence data


@pytest.mark.asyncio
async def test_recap_includes_inferred_payment_and_confirmation_logs_snack(service, store):
    ingest_notification(store, {"package": "com.phonepe.app", "title": "Paid", "text": "₹40 paid to SHARMA CHAAT", "time": "2026-10-05T12:40:00Z"}, at(MON, "18:11"))
    out = await service.tick(at(MON, "21:05"))
    inf_buttons = [b for b in out[0].buttons if b[1].startswith("inf:")]
    assert len(inf_buttons) == 2
    reply = await service.handle_button(inf_buttons[0][1], at(MON, "21:06"))
    assert "Logged" in reply.text
    snack = store.food_for_date(MON)[0]
    assert snack["source"] == "inferred_confirmed" and snack["food_key"] == "street_snack"
    assert store.get_payee_label(store.get_inferred(1)["payee_key"])["is_food"] == 1


# ------------------------------------------------------------------ patterns
async def _chess_days(service, llm, store, weeks=4, snack_on=(0, 1, 2)):
    """User mentions chess on Tuesdays and Saturdays; buys sev parmal on some of them."""
    days = []
    for w in range(weeks):
        for wd in (1, 5):  # Tue, Sat
            day = d(-28 + w * 7 + wd)
            days.append(day)
            llm.queue("extract", empty_extraction(context=[{"tag": "chess", "timing": "planned", "time_hint": "18:00"}]))
            await service.handle_message(msg("aaj chess ja raha", at(day, "17:00")))
            if len(days) % 2 == 1 or w in snack_on:
                llm.queue("extract", empty_extraction(food_items=[
                    {"name": "sev parmal", "quantity": 1, "unit": "plate", "meal_slot": "snack", "eaten": "eaten",
                     "est_kcal_low": 250, "est_kcal_high": 350, "confidence": 0.8}]))
                await service.handle_message(msg("sev parmal kha liya", at(day, "19:00")))
    return days


@pytest.mark.asyncio
async def test_patterns_learned_from_conversation_alone(service, llm, store):
    await _chess_days(service, llm, store)
    mined = {p["key"]: p for p in mine_patterns(store, at(MON, "03:00"))}
    tue = mined["weekday_context:chess:1"]
    assert tue["status"] == "active" and tue["support"] == 4 and tue["data"]["typical_time"] == "18:00"
    lapse = mined["context_lapse:chess"]
    assert lapse["status"] == "active" and "sev parmal" in lapse["claim"]
    assert predicted_contexts(store, d(1))[0]["tag"] == "chess"  # Tuesday


@pytest.mark.asyncio
async def test_predicted_chess_day_triggers_pre_emptive_message_and_rule_proposal(service, llm, store):
    await _chess_days(service, llm, store)
    store.mark_job(at(d(1), "03:00"), "daily", d(1))  # we mine explicitly below
    mine_patterns(store, at(d(1), "03:00"))
    # Tuesday 11:00 - no rule exists yet, so the coach proposes one from the observed pattern.
    llm.queue("decide", {"choice": 0, "message": "Chess days pe sev parmal common hai - default 'sirf coffee' rakhein?",
                         "proposed_rule": "Chess pe sirf coffee", "reason": "pattern"})
    out = await service.tick(at(d(1), "11:00"))
    assert out and "sev parmal" in out[0].text
    assert store.get_intervention(out[0].intervention_id)["intent"] == "propose_commitment:chess"
    assert ("Yes, set it", f"pc:{out[0].intervention_id}:yes") in out[0].buttons
    await service.handle_button(f"pc:{out[0].intervention_id}:yes", at(d(1), "11:05"))
    rule = store.commitments_with_trigger("chess")
    assert rule and rule[0].action == "Chess pe sirf coffee"
    # 16:30 on the same Tuesday, before ~18:00: pre-emptive reminder of THEIR rule without them mentioning chess.
    later = await service.tick(at(d(1), "16:30"))
    assert later and later[0].text.startswith("[predicted_context:chess]")


@pytest.mark.asyncio
async def test_user_can_reject_a_pattern(service, llm, store):
    await _chess_days(service, llm, store)
    mine_patterns(store, at(MON, "03:00"))
    pid = [p for p in store.patterns("active") if p["kind"] == "context_lapse"][0]["id"]
    llm.queue("extract", empty_extraction(pattern_feedback=[{"pattern_id": pid, "correct": False}]))
    await service.handle_message(msg("galat hai, chess pe kuch nahi khata", at(MON, "10:00")))
    mine_patterns(store, at(MON, "10:01"))
    assert store.conn.execute("SELECT status FROM patterns WHERE id = ?", (pid,)).fetchone()[0] == "rejected"


def test_wilson_is_conservative():
    assert wilson_lower(3, 3) < 0.7
    assert wilson_lower(8, 10) > wilson_lower(4, 5)


# ------------------------------------------------------------------ engagement
@pytest.mark.asyncio
async def test_silence_shrinks_budget_and_switches_to_reengagement(service, store, llm):
    await service.handle_message(msg("hi", at(d(-12), "10:00")))
    eng = compute_engagement(store, at(MON, "12:00"), 3)
    assert eng.state == "dormant" and eng.daily_budget == 1 and eng.allowed_intents == {"reengage"}
    out = await service.tick(at(MON, "12:00"))
    assert out and out[0].text.startswith("[reengage]")
    assert [b[0] for b in out[0].buttons] == ["All good", "Off track", "Pause 1 week"]
    assert await service.tick(at(MON, "15:00")) == []  # budget 1
    assert await service.tick(at(d(1), "12:00")) == []  # dormant: spaced out


@pytest.mark.asyncio
async def test_dead_message_types_are_retired(service, store):
    for i in range(6):
        store.add_intervention(at(d(-6 + i), "10:00"), commitment_id=None, kind="proactive", intent="morning_plan",
                               level=1, slot="10:00", style="x", version_offered=None, message_text="plan", reason="t")
    for row in store.open_interventions():
        store.update_intervention(at(MON, "08:00"), row["id"], "ignored")
    await service.handle_message(msg("hi", at(MON, "08:00")))
    eng = compute_engagement(store, at(MON, "09:00"), 3)
    assert "morning_plan" in eng.retired_intents


@pytest.mark.asyncio
async def test_llm_can_choose_silence_and_is_not_asked_again_immediately(service, store, llm):
    for off in (-5, -4, -3, -2):
        log_lunch(store, d(off))
    llm.queue("decide", {"choice": -1, "reason": "user said they're at a wedding tonight"})
    assert await service.tick(at(MON, "21:00")) == []
    n = len(llm.requests_for("decide"))
    assert await service.tick(at(MON, "21:15")) == []
    assert len(llm.requests_for("decide")) == n  # suppressed for this 2h bucket


# ------------------------------------------------------------------ health connect
@pytest.mark.asyncio
async def test_inactivity_nudge_uses_real_steps_and_calendar(service, store):
    now = at(MON, "16:00")
    for i in range(1, 8):
        day = d(-i)
        ingest_payload(store, {"steps": [{"count": 7000, "start_time": f"{day}T04:00:00Z", "end_time": f"{day}T05:00:00Z"}]}, now)
    ingest_payload(store, {"steps": [{"count": 600, "start_time": f"{MON}T04:00:00Z", "end_time": f"{MON}T05:00:00Z"}]}, now)
    out = await service.tick(now)
    assert out and out[0].text.startswith("[inactivity]")
    req = service.llm.requests_for("decide")[-1].user_text
    assert '"steps_so_far": 600' in req and '"typical_daily_steps": 7000' in req


@pytest.mark.asyncio
async def test_walk_detected_by_phone_completes_commitment_silently(service, store):
    cid = store.add_commitment(at(d(-3), "09:00"), kind="habit", title="Evening walk", action="walk",
                               versions=["30 min walk", "10 min walk"], schedule_days="daily",
                               window_start="18:00", window_end="20:00", enforcement="normal", activity_kind="walk")
    ingest_payload(store, {"exercise": [{"type": "WALKING", "start_time": f"{MON}T12:30:00Z",
                                         "end_time": f"{MON}T12:55:00Z", "duration_seconds": 1500}]}, at(MON, "18:40"))
    out = await service.tick(at(MON, "18:45"))
    assert store.commitment_outcome_on(cid, MON) == "smaller"  # 25 min < 30 full, >= 10 minimum
    assert not any(m.text.startswith("[commitment") for m in out)


# ------------------------------------------------------------------ conversation upgrades
@pytest.mark.asyncio
async def test_history_question_gets_real_numbers(service, store, llm):
    for off in (-3, -2, -1):
        log_lunch(store, d(off))
    llm.queue("extract", empty_extraction(data_needed=[{"query": "food_log", "days": 3}]))
    await service.handle_message(msg("pichle 3 din me kya khaya?", at(MON, "10:00")))
    prompt = llm.requests_for("reply")[-1].user_text
    assert "DATA YOU ASKED FOR" in prompt and d(-1) in prompt and '"meals_missing"' in prompt


@pytest.mark.asyncio
async def test_food_correction_replaces_instead_of_duplicating(service, store, llm):
    llm.queue("extract", empty_extraction(food_items=[{"name": "roti", "quantity": 3, "unit": "piece", "meal_slot": "lunch", "eaten": "eaten", "confidence": 0.9}]))
    await service.handle_message(msg("3 roti khayi", at(MON, "13:40")))
    llm.queue("extract", empty_extraction(food_corrections=[{"item_name": "roti", "new_quantity": 2}]))
    await service.handle_message(msg("nahi 2 roti thi", at(MON, "13:45")))
    rows = store.food_for_date(MON)
    assert len(rows) == 1 and rows[0]["quantity"] == 2 and rows[0]["kcal_high"] == 240


@pytest.mark.asyncio
async def test_commitment_can_be_edited_by_conversation(service, store, llm):
    cid = store.add_commitment(at(d(-3), "09:00"), kind="habit", title="Walk", action="walk", versions=["20 min"],
                               schedule_days="daily", window_start="19:00", window_end="20:00", enforcement="normal")
    llm.queue("extract", empty_extraction(commitment_changes=[{"commitment_id": cid, "window_start": "20:00", "window_end": "21:00"}]))
    await service.handle_message(msg("7 nahi, 8 baje remind karna", at(MON, "10:00")))
    c = store.get_commitment(cid)
    assert (c.window_start, c.window_end) == ("20:00", "21:00")


@pytest.mark.asyncio
async def test_late_meal_report_attributed_to_meal_time(service, store, llm):
    llm.queue("extract", empty_extraction(food_items=[{"name": "poha", "quantity": 1, "unit": "katori", "meal_slot": "breakfast", "eaten": "eaten", "confidence": 0.9}]))
    await service.handle_message(msg("subah poha khaya tha", at(MON, "18:00")))
    row = store.food_for_date(MON)[0]
    assert row["occurred_at"].startswith(f"{MON}T03:30")  # 09:00 IST


@pytest.mark.asyncio
async def test_any_reply_counts_as_answer_to_open_prompt(service, store):
    await service.handle_message(msg("hi", at(d(-5), "10:00")))
    out = await service.tick(at(MON, "12:00"))  # re-engagement
    await service.handle_message(msg("haan sab theek", at(MON, "12:30")))
    assert store.get_intervention(out[0].intervention_id)["status"] == "answered"


# ------------------------------------------------------------------ v0.2.1 additions
@pytest.mark.asyncio
async def test_declared_usual_meals_enable_one_tap_recap_from_day_one(store, llm):
    import random as _r
    from coach.engine.policy import SlotLearner
    from coach.engine.service import CoachService

    service = CoachService(store, llm, learner=SlotLearner(store, _r.Random(1)))
    llm.queue("extract", empty_extraction(profile_updates={"usual_meals": [
        {"slot": "lunch", "items": [{"name": "roti", "quantity": 3, "unit": "piece"}, {"name": "dal", "quantity": 1, "unit": "katori"}]}]}))
    await service.handle_message(msg("lunch me mostly 3 roti dal hota hai", at(MON, "12:00")))
    assert store.food_for_date(MON) == []  # describing a habit is not a food log
    out = await service.tick(at(MON, "21:00"))
    recap = [o for o in out if o.text.startswith("[recap]")]
    assert recap and ("All usual", f"rc:{recap[0].intervention_id}:usual") in recap[0].buttons


@pytest.mark.asyncio
async def test_ignored_recaps_become_less_frequent_not_retired(service, store):
    for off in range(-8, 0):
        store.add_intervention(at(d(off), "21:00"), commitment_id=None, kind="proactive", intent="recap", level=1,
                               slot="21:00", style="recap", version_offered=None, message_text="recap?", reason="t")
    for row in store.open_interventions():
        store.update_intervention(at(MON, "08:00"), row["id"], "ignored")
    eng = compute_engagement(store, at(MON, "21:00"), 3)
    assert "recap" not in eng.retired_intents and eng.recap_every_days == 3


@pytest.mark.asyncio
async def test_orders_auto_logged_as_unconfirmed_and_known_payees_logged(service, store):
    ingest_notification(store, {"package": "in.swiggy.android", "title": "Order delivered",
                                "text": "Your order from Behrouz Biryani has been delivered.", "time": "2026-10-04T15:45:00Z"}, at(d(-1), "21:15"))
    store.set_payee_label(at(d(-5), "10:00"), "ramu tea stall", True, "chai stall")
    ingest_notification(store, {"package": "sms", "title": "VM-HDFCBK",
                                "text": "Sent Rs.20.00 From HDFC Bank A/C *1234 To RAMU TEA STALL On 05/10/26 Ref 612345678901",
                                "time": "2026-10-05T05:30:00Z"}, at(MON, "11:00"))
    await service.tick(at(MON, "16:00"))
    sources = {r["item_name"]: r["source"] for r in store.food_between(d(-1), MON)}
    assert sources.get("restaurant meal") == "inferred_unconfirmed"
    assert sources.get("chai stall") == "inferred_known_payee"


@pytest.mark.asyncio
async def test_recurring_payee_becomes_snack_stop_pattern(service, store):
    for w in range(4):
        day = d(-28 + w * 7 + 1)  # Tuesdays
        ingest_notification(store, {"package": "com.phonepe.app", "title": "Paid", "text": "₹40 paid to SHARMA CHAAT",
                                    "time": f"{day}T13:10:00Z"}, at(day, "18:41"))
    mined = {p["key"]: p for p in mine_patterns(store, at(MON, "03:00"))}
    pat = [p for k, p in mined.items() if k.startswith("weekday_payee:")][0]
    assert pat["status"] == "active" and pat["data"]["typical_time"] == "18:40"
    assert predicted_contexts(store, d(1))[0]["kind"] == "weekday_payee"


def test_intake_estimate_separates_recorded_and_assumed(store):
    from coach.analytics.habits import estimated_day_intake
    from .conftest import onboard

    onboard(store)
    log_lunch(store, MON)
    est = estimated_day_intake(store, MON)
    assert est["assumed_usual_slots"] == ["breakfast"]
    assert est["unknown_slots"] == ["dinner"] and est["total_kcal_range"] is None


# ------------------------------------------------------------------ found in the live 7-day run
@pytest.mark.asyncio
async def test_onboarding_topic_asked_at_most_twice_and_not_daily(store, llm):
    import random as _r
    from coach.engine.policy import SlotLearner
    from coach.engine.service import CoachService

    service = CoachService(store, llm, learner=SlotLearner(store, _r.Random(1)))
    asked = []
    for i in range(8):
        day = d(-8 + i)
        for o in await service.tick(at(day, "12:00")):
            asked.append((day, store.get_intervention(o.intervention_id)["intent"]))
    usual = [a for a in asked if a[1] == "onboarding:usual_meals"]
    assert len(usual) <= 2
    assert len({a[1] for a in asked}) >= 2  # moves on to other topics instead of repeating


@pytest.mark.asyncio
async def test_low_value_messages_dont_eat_the_budget_for_evening_commitment(service, store):
    store.add_commitment(at(d(-3), "09:00"), kind="habit", title="Evening walk", action="walk", versions=["30 min", "10 min"],
                         schedule_days="daily", window_start="19:00", window_end="19:30", enforcement="normal")
    store.set_profile(at(MON, "07:00"), daily_message_budget=2)
    store.set_calendar_day(at(MON, "07:00"), MON, 9.0, "09:00", "19:00", {"free": [], "busy": [["09:00", "19:00"]]})
    early = await service.tick(at(MON, "08:45"))  # morning plan would be allowed by budget alone
    assert early == []  # 2 slots: one reserved for the walk, one for the recap
    later = await service.tick(at(MON, "19:15"))
    assert later and later[0].text.startswith("[commitment:")


@pytest.mark.asyncio
async def test_focused_extraction_recovers_missed_answer(service, store, llm):
    store.set_profile(at(MON, "09:00"), usual_meals={})
    store.add_intervention(at(MON, "12:00"), commitment_id=None, kind="proactive", intent="onboarding:usual_meals", level=1,
                           slot="12:00", style="onboarding", version_offered=None, message_text="usually kya khate ho?", reason="t")
    llm.queue("extract", empty_extraction())  # the general extraction misses it (seen live)
    llm.queue("extract", {"meals": [{"slot": "lunch", "quote": "lunch me 3 roti dal",
                                     "items": [{"name": "roti", "quantity": 3, "unit": "piece"}, {"name": "dal", "quantity": 1, "unit": "katori"}]}]})
    await service.handle_message(msg("usually lunch me 3 roti dal", at(MON, "12:20")))
    assert "lunch" in store.get_profile()["usual_meals"]
    assert any(dd["kind"] == "focused_extract" for dd in store.recent_decisions())
