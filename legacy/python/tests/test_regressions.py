"""Regression tests for bugs found in review (see docs/BUGS.md, BUG-002..BUG-015)."""

import pytest

from coach.channels.telegram_bot import TelegramChannel
from coach.engine.extractor import validate_extraction
from coach.engine.policy import analyse_commitment, decide_level
from coach.ingest.health_connect import ingest_payload
from coach.nutrition.estimator import FoodTable, estimate
from coach.timeutil import slot_of, slots_between, parse_hhmm

from .conftest import at, empty_extraction, msg
from .test_planner import add_walk, day

MON = "2026-10-05"


@pytest.mark.asyncio
async def test_bug002_late_done_counts_for_the_nudge_day(service, store):
    cid = add_walk(store, window_start="21:00", window_end="22:00")
    out = await service.tick(at(MON, "21:30"))
    await service.handle_button(f"iv:{out[0].intervention_id}:done", at("2026-10-06", "00:10"))
    assert store.commitment_outcome_on(cid, MON) == "done"
    assert store.commitment_outcome_on(cid, "2026-10-06") is None


@pytest.mark.asyncio
async def test_bug003_contextual_reminders_do_not_cause_back_off(service, store, llm):
    cid = store.add_commitment(
        at("2026-09-20", "09:00"),
        kind="if_then",
        title="Walk to chess",
        trigger_tag="chess",
        action="walk to chess",
        versions=["walk to chess"],
        schedule_days="daily",
        window_start="18:00",
        window_end="19:00",
        enforcement="normal",
    )
    for off in (-4, -3, -2, -1):
        llm.queue("extract", empty_extraction(context=[{"tag": "chess", "timing": "planned"}]))
        await service.handle_message(msg("chess ja raha", at(day(off), "10:00")))
        store.log_commitment(at(day(off), "18:30"), cid, "done", "user_reported")
        await service.tick(at(day(off), "23:50"))  # expires the contextual reminder
    statuses = {r["status"] for d in (-4, -3, -2, -1) for r in store.interventions_on(day(d))}
    assert statuses == {"expired"}
    pattern = analyse_commitment(store, store.get_commitment(cid), MON)
    assert pattern["ignored_streak"] == 0 and pattern["slot_success"] == {}


def test_bug004_unaligned_window_uses_same_slot_grid():
    slots = slots_between(parse_hhmm("07:45"), parse_hhmm("10:00"))
    assert slot_of(parse_hhmm("07:50")) in slots
    assert slots[0] == "07:30"


def test_bug005_gram_quantities_survive_validation():
    raw = empty_extraction(
        food_items=[
            {"name": "paneer", "quantity": 200, "unit": "g", "eaten": "eaten", "confidence": 0.9},
            {"name": "milk", "quantity": 300, "unit": "ml", "eaten": "eaten", "confidence": 0.9},
            {"name": "roti", "quantity": 200, "unit": "piece", "eaten": "eaten", "confidence": 0.9},
        ]
    )
    foods = validate_extraction(raw, set()).foods
    table = FoodTable.load()
    paneer = estimate(table, foods[0].name, foods[0].quantity, foods[0].unit)
    assert 400 < paneer.kcal_low < paneer.kcal_high < 800
    milk = estimate(table, foods[1].name, foods[1].quantity, foods[1].unit)
    assert milk.kcal_low > 100
    assert foods[2].quantity is None and foods[2].unit is None


@pytest.mark.asyncio
async def test_bug006_window_without_days_defaults_to_daily(service, store):
    raw = empty_extraction(
        commitments=[{"kind": "habit", "title": "Walk", "action": "walk", "window_start": "18:00", "window_end": "19:00"}]
    )
    assert validate_extraction(raw, set()).commitments[0]["schedule_days"] == "daily"
    store.add_commitment(at(MON, "09:00"), kind="habit", title="W", action="w", window_start="18:00", window_end="19:00", enforcement="normal")
    assert len(await service.tick(at(MON, "18:30"))) == 1


def _update(user_id: int, chat_type: str, edited: bool = False):
    from telegram import Chat, Message, Update, User
    from datetime import datetime, timezone

    user = User(id=user_id, first_name="u", is_bot=False)
    chat = Chat(id=user_id if chat_type == "private" else -100, type=chat_type)
    message = Message(message_id=1, date=datetime.now(timezone.utc), chat=chat, from_user=user, text="hi")
    if edited:
        return Update(update_id=1, edited_message=message)
    return Update(update_id=1, message=message)


def test_bug007_edited_messages_not_handled(service):
    channel = TelegramChannel(service, "123456:TEST-token", 42, 15)
    text_handlers = [h for g in channel.app.handlers.values() for h in g if getattr(h, "callback", None) == channel._text]
    assert text_handlers[0].check_update(_update(42, "private"))
    assert not text_handlers[0].check_update(_update(42, "private", edited=True))


def test_bug008_group_chats_rejected(service):
    channel = TelegramChannel(service, "123456:TEST-token", 42, 15)
    assert channel._allowed(_update(42, "private"))
    assert not channel._allowed(_update(42, "group"))
    assert not channel._allowed(_update(7, "private"))


def test_bug009_equivalent_timestamps_do_not_double_count(store):
    now = at(MON, "20:00")
    ingest_payload(store, {"steps": [{"count": 100, "start_time": "2026-10-05T03:00:00Z", "end_time": "2026-10-05T04:00:00Z"}]}, now)
    ingest_payload(store, {"steps": [{"count": 100, "start_time": "2026-10-05T03:00:00.000Z", "end_time": "2026-10-05T04:00:00.000Z"}]}, now)
    assert store.health_daily_sum("steps", MON) == 100


def test_bug010_cannot_skips_never_escalate(store):
    cid = add_walk(store)
    for off in (-2, -1):
        store.log_commitment(at(day(off), "20:00"), cid, "skipped", "button", reason_category="cannot")
    c = store.get_commitment(cid)
    d = decide_level(c, analyse_commitment(store, c, MON), store.get_profile() | {"coaching_mode": "accountability"}, MON)
    assert d.level == 1 and d.version == "30 min walk"


@pytest.mark.asyncio
async def test_bug013_failed_processing_leaves_no_partial_state(service, llm, store, monkeypatch):
    original = store.add_activity

    def boom(*a, **k):
        raise RuntimeError("disk full")

    raw_extract = empty_extraction(
        food_items=[{"name": "roti", "quantity": 2, "eaten": "eaten", "confidence": 0.9}],
        activities=[{"kind": "walk", "status": "done", "duration_min": 10, "confidence": 0.9}],
    )
    llm.queue("extract", raw_extract, raw_extract)  # first attempt fails mid-write, redelivery succeeds
    monkeypatch.setattr(store, "add_activity", boom)
    with pytest.raises(RuntimeError):
        await service.handle_message(msg("2 roti, 10 min walk", at(MON, "13:00"), "tg:9"))
    assert store.food_for_date(MON) == []
    assert not store.message_exists("tg:9")  # redelivery will be processed
    monkeypatch.setattr(store, "add_activity", original)
    await service.handle_message(msg("2 roti, 10 min walk", at(MON, "13:00"), "tg:9"))
    assert len(store.food_for_date(MON)) == 1


@pytest.mark.asyncio
async def test_bug015_double_tap_done_is_idempotent(service, store):
    cid = add_walk(store)
    out = await service.tick(at(MON, "18:30"))
    iid = out[0].intervention_id
    await service.handle_button(f"iv:{iid}:done", at(MON, "18:40"))
    again = await service.handle_button(f"iv:{iid}:done", at(MON, "18:41"))
    assert again.text == "Already noted."
    assert len(store.commitment_history(cid, MON)) == 1


def test_bug014_status_shows_local_pause_time(service, store):
    service.pause(at(MON, "10:00"), 1)
    assert "06 Oct 10:00" in service.status_text(at(MON, "10:01"))


# ---------------------------------------------------------------- v0.2 review (BUG-025..039)
from datetime import date, timedelta
from datetime import datetime as _dt
from zoneinfo import ZoneInfo as _Z

from coach.ingest.notifications import ingest_notification as _ingest, parse_notification as _parse, parse_time as _ptime

_W = _dt(2026, 10, 6, 18, 10, tzinfo=_Z("Asia/Kolkata"))


@pytest.mark.parametrize(
    "pkg,title,text,amount,payee",
    [
        ("sms", "SBI", "Dear UPI user A/C X1234 debited by 60.0 on date 06Oct26 trf to RAJU CHAAT Refno 612345678901. If not u? call 1800111109. -SBI", 60, "raju chaat"),
        ("sms", "HDFC", "Sent Rs.30.00 From HDFC Bank A/C *1234 To RAMU TEA STALL On 06/10/26 Ref 612345678901 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808", 30, "ramu tea stall"),
        ("sms", "ICICI", "ICICI Bank Acct XX123 debited for Rs 45.00 on 06-Oct-26; raju.chaat@okaxis credited. UPI:612345678901. SMS BLOCK 123 to 9215676766", 45, "raju.chaat@okaxis"),
        ("sms", "Axis", "INR 50.00 debited\nA/c no. XX1234\nUPI/P2M/612345678901/SHARMA CHAAT CORNER\nNot you? SMS BLOCKUPI", 50, "sharma chaat corner"),
        ("sms", "Kotak", "Sent Rs.40.00 from Kotak Bank AC X1234 to raju.dairy@ybl on 06-10-26.UPI Ref 612345678901.", 40, "raju.dairy@ybl"),
        ("com.phonepe.app", "Payment successful", "₹40 paid to Raju Chaat Corner", 40, "raju chaat corner"),
    ],
)
def test_bug025_indian_payment_formats(pkg, title, text, amount, payee):
    sig = _parse(pkg, title, text, _W, "Asia/Kolkata")
    assert sig and sig.amount == amount and sig.payee_key == payee


@pytest.mark.parametrize("pkg,title,text", [
    ("com.google.android.apps.nbu.paisa.user", "Amit requested ₹200", "Pay now"),
    ("sms", "x", "Rs 30 debited, Refund initiated"),
    ("sms", "x", "Rs.199 debited for Netflix autopay mandate"),
    ("com.zeptoconsumerapp", "Order delivered", "Your milk and atta have been delivered"),
])
def test_bug026_requests_refunds_autopay_grocery_ignored(pkg, title, text):
    assert _parse(pkg, title, text, _W, "Asia/Kolkata") is None


def test_bug027_person_payee_names_not_exposed():
    sig = _parse("com.phonepe.app", "Payment successful", "₹200 paid to Rahul Sharma", _W, "Asia/Kolkata")
    assert "Rahul" not in sig.summary and "text" not in sig.payload


def test_bug028_same_payment_via_sms_and_app_counted_once(store):
    now = _W
    _ingest(store, {"package": "com.phonepe.app", "title": "Paid", "text": "₹40 paid to Raju Chaat Corner", "time": "2026-10-06T12:40:00Z"}, now)
    r = _ingest(store, {"package": "sms", "title": "HDFC", "text": "Sent Rs.40.00 From HDFC Bank A/C *1234 To RAJU CHAAT CORNER On 06/10/26 Ref 612345678901", "time": "2026-10-06T12:41:30Z"}, now)
    assert r["status"] == "duplicate" and len(store.pending_inferred()) == 1


def test_bug029_order_status_without_restaurant_merged(store):
    for t, txt in [("10:20:00", "Order confirmed from Biryani Blues"), ("11:05:00", "Ramesh is on the way"), ("11:30:00", "Order delivered. Enjoy your meal!")]:
        _ingest(store, {"package": "in.swiggy.android", "title": "Swiggy", "text": txt, "time": f"2026-10-06T{t}Z"}, _W)
    assert len(store.pending_inferred()) == 1


def test_bug030_naive_and_epoch_times(store):
    assert _ptime("2026-10-06 20:15:00", "Asia/Kolkata", _W).utcoffset().total_seconds() == 19800
    assert _ptime(1791300000000, "Asia/Kolkata", _W).year == 2026
    assert _ingest(store, {"package": "sms", "text": "x", "time": "garbage"}, _W)["status"] == "error"


@pytest.mark.asyncio
async def test_bug031_inferred_not_double_counted_with_typed_food(service, store, llm):
    llm.queue("extract", empty_extraction(food_items=[{"name": "pani puri", "quantity": 6, "unit": "piece", "meal_slot": "snack",
                                                        "eaten": "eaten", "est_kcal_low": 150, "est_kcal_high": 250, "confidence": 0.9}]))
    await service.handle_message(msg("6 pani puri khaye", at("2026-10-06", "18:20")))
    r = _ingest(store, {"package": "com.phonepe.app", "title": "Paid", "text": "₹40 paid to Raju Chaat Corner", "time": "2026-10-06T12:40:00Z"}, _W)
    await service.handle_button(f"inf:{r['id']}:yes", at("2026-10-06", "21:10"))
    assert len(store.food_for_date("2026-10-06")) == 1


@pytest.mark.asyncio
async def test_bug032_recap_buttons_work_after_expiry_but_only_once(service, store):
    out = await service.tick(at("2026-10-06", "21:05"))
    iid = [o for o in out if o.text.startswith("[recap]")][0].intervention_id
    await service.tick(at("2026-10-06", "23:20"))  # expires to ignored
    first = await service.handle_button(f"rc:{iid}:usual", at("2026-10-06", "23:25"))
    assert "Logged as usual" in first.text
    again = await service.handle_button(f"rc:{iid}:usual_snack", at("2026-10-06", "23:26"))
    assert again.text == "Already noted."


@pytest.mark.asyncio
async def test_bug033_stale_patterns_demoted(service, store, llm):
    from coach.analytics.patterns import mine_patterns

    for w in range(4):
        day = (date(2026, 9, 1) + timedelta(days=7 * w)).isoformat()  # Tuesdays in September
        llm.queue("extract", empty_extraction(context=[{"tag": "office", "timing": "now"}]))
        await service.handle_message(msg("office", at(day, "10:00")))
    mine_patterns(store, at("2026-09-30", "03:00"))
    assert store.patterns("active", "weekday_context")
    mine_patterns(store, at("2026-12-20", "03:00"))
    assert not store.patterns("active", "weekday_context")


@pytest.mark.asyncio
async def test_bug034_context_lapse_requires_lift_over_base_rate(service, store, llm):
    from coach.analytics.patterns import mine_patterns

    for i in range(21):
        day = (date(2026, 9, 10) + timedelta(days=i)).isoformat()
        if i % 2 == 0:
            llm.queue("extract", empty_extraction(context=[{"tag": "office", "timing": "now"}]))
            await service.handle_message(msg("office", at(day, "09:00")))
        else:
            await service.handle_message(msg("hi", at(day, "09:00")))
        store.add_food_event(at(day, "18:00"), at(day, "18:00"), meal_slot="snack", item_name="samosa", food_key="samosa",
                             quantity=1, unit="piece", kcal_low=230, kcal_high=320, protein_low=4, protein_high=6,
                             nutrition_source="food_table", data_status="estimated", confidence=0.9)
    pats = {p["key"]: p for p in mine_patterns(store, at("2026-10-02", "03:00"))}
    assert pats["context_lapse:office"]["status"] == "candidate"  # samosa every day: office isn't the cause


@pytest.mark.asyncio
async def test_bug035_planned_tomorrow_dated_tomorrow(service, store, llm):
    llm.queue("extract", empty_extraction(context=[{"tag": "chess", "timing": "planned", "day": "tomorrow"}]))
    await service.handle_message(msg("kal chess jaunga", at("2026-10-05", "22:00")))
    assert store.context_events_since("2026-10-01")[0]["local_date"] == "2026-10-06"


@pytest.mark.asyncio
async def test_bug036_no_inactivity_nudge_during_back_to_back_meetings(service, store):
    now = at("2026-10-06", "16:00")
    for i in range(1, 8):
        day = (date(2026, 10, 6) - timedelta(days=i)).isoformat()
        ingest_payload(store, {"steps": [{"count": 7000, "start_time": f"{day}T04:00:00Z", "end_time": f"{day}T05:00:00Z"}]}, now)
    ingest_payload(store, {"steps": [{"count": 500, "start_time": "2026-10-06T04:00:00Z", "end_time": "2026-10-06T05:00:00Z"}]}, now)
    store.set_calendar_day(now, "2026-10-06", 12.0, "08:00", "21:00", {"free": [], "busy": [["08:00", "21:00"]]})
    assert not any(o.text.startswith("[inactivity]") for o in await service.tick(now))


@pytest.mark.asyncio
async def test_bug037_dormant_user_gets_no_weekly_review(service, store):
    await service.handle_message(msg("hi", at("2026-09-20", "10:00")))
    out = await service.tick(at("2026-10-11", "19:30"))  # Sunday
    assert all("Good week" not in o.text for o in out)


@pytest.mark.asyncio
async def test_bug038_button_only_user_is_not_treated_as_silent(service, store):
    from coach.engine.engagement import compute_engagement

    await service.handle_message(msg("hi", at("2026-09-20", "10:00")))
    out = await service.tick(at("2026-10-05", "12:00"))  # dormant -> one-tap re-entry
    assert out[0].text.startswith("[reengage]")
    await service.handle_button(f"re:{out[0].intervention_id}:ok", at("2026-10-05", "12:10"))
    assert compute_engagement(store, at("2026-10-06", "12:00"), 3).state == "engaged"


def test_bug039_default_confirmed_does_not_become_habit(store):
    from coach.analytics.habits import habitual_meals

    for i in range(4):
        day = (date(2026, 10, 1) + timedelta(days=i)).isoformat()
        store.add_food_event(at(day, "21:00"), at(day, "13:30"), meal_slot="lunch", item_name="roti", food_key="roti", quantity=2,
                             unit="piece", kcal_low=180, kcal_high=240, protein_low=5, protein_high=7, nutrition_source="food_table",
                             data_status="estimated", confidence=0.5, source="default_confirmed")
    assert "lunch" not in habitual_meals(store, "2026-10-06")
