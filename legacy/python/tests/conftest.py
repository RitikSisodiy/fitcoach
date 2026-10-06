from __future__ import annotations

import random
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from coach.engine.policy import SlotLearner
from coach.engine.service import CoachService, InboundMessage
from coach.llm.fake import FakeProvider
from coach.memory.db import connect
from coach.memory.store import Store

TZ = "Asia/Kolkata"


def at(day: str, hhmm: str) -> datetime:
    """Local (IST) wall-clock time -> aware datetime."""
    h, m = map(int, hhmm.split(":"))
    y, mo, d = map(int, day.split("-"))
    return datetime(y, mo, d, h, m, tzinfo=ZoneInfo(TZ))


def empty_extraction(**overrides) -> dict:
    base = {
        "food_items": [],
        "activities": [],
        "body_metrics": [],
        "context": [],
        "commitments": [],
        "commitment_updates": [],
        "facts": [],
        "lapse": {"detected": False},
    }
    base.update(overrides)
    return base


@pytest.fixture
def store() -> Store:
    return Store(connect(":memory:"), TZ)


@pytest.fixture
def llm() -> FakeProvider:
    fake = FakeProvider()
    fake.set_default("reply", "Theek hai, noted.")
    fake.set_default("nudge", "Planned walk abhi karein? 10 min bhi chalega.")
    fake.set_default("review", "Good week: 4/7 walks. Next week try walking right after lunch?")
    fake.set_default("extract", empty_extraction())
    fake.set_default("decide", decide_first)
    return fake


def decide_first(request) -> dict:
    """Default 'decide' behaviour for tests: pick the top candidate and echo its intent."""
    import json as _json

    cands = _json.loads(request.user_text.split("CANDIDATES:\n", 1)[1])
    return {"choice": 0, "message": f"[{cands[0]['intent']}] ok?", "reason": "test: top candidate"}


def onboard(store: Store, when: datetime | None = None) -> None:
    """Fill the profile basics so onboarding questions don't compete with what a test checks."""
    when = when or datetime(2026, 9, 1, 9, 0, tzinfo=ZoneInfo(TZ))
    store.set_profile(when, goal_text="lose 6 kg", usual_meals={
        "breakfast": [{"name": "poha", "quantity": 1, "unit": "katori"}],
        "lunch": [{"name": "roti", "quantity": 2, "unit": "piece"}, {"name": "dal", "quantity": 1, "unit": "katori"}],
    })
    store.add_body_metric(when, when, "weight_kg", 82.0, "user_reported")
    store.upsert_fact(when, "routine", "work_mode", "WFH", "user_stated", 0.9)


@pytest.fixture
def service(store: Store, llm: FakeProvider) -> CoachService:
    onboard(store)
    return CoachService(store, llm, learner=SlotLearner(store, random.Random(7)))


def msg(text: str, when: datetime, external_id: str | None = None, **kw) -> InboundMessage:
    return InboundMessage(text=text, received_at=when, external_id=external_id, **kw)
