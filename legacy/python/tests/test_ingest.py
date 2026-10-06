from fastapi.testclient import TestClient

from coach.ingest.api import create_app
from coach.ingest.health_connect import ingest_payload

from .conftest import at

PAYLOAD = {
    "timestamp": "2026-10-05T14:00:00.123Z",
    "app_version": "1.9.14",
    "steps": [
        {"count": 3200, "start_time": "2026-10-05T03:00:00Z", "end_time": "2026-10-05T04:00:00Z"},
        {"count": 2100, "start_time": "2026-10-05T10:00:00Z", "end_time": "2026-10-05T11:00:00Z"},
    ],
    "sleep": [{"session_end_time": "2026-10-05T01:30:00Z", "duration_seconds": 25200, "stages": []}],
    "weight": [{"kilograms": 82.35, "time": "2026-10-05T02:00:00Z"}],
    "exercise": [
        {"type": "WALKING", "start_time": "2026-10-05T12:30:00Z", "end_time": "2026-10-05T12:55:00Z", "duration_seconds": 1500}
    ],
    "heart_rate": [{"bpm": 72, "time": "2026-10-05T08:15:00Z"}],
}

NOW = at("2026-10-05", "19:40")


def test_ingest_stores_known_types_and_ignores_others(store):
    result = ingest_payload(store, PAYLOAD, NOW)
    assert result.stored == {"steps": 2, "sleep": 1, "weight": 1, "exercise": 1}
    assert result.ignored_types == ["heart_rate"]
    assert store.health_daily_sum("steps", "2026-10-05") == 5300
    assert store.health_daily_sum("sleep", "2026-10-05") == 7.0
    assert store.health_daily_sum("exercise", "2026-10-05") == 25
    assert store.metric_series("weight_kg", "2026-10-01") == [("2026-10-05", 82.35)]


def test_ingest_is_idempotent_and_handles_updates(store):
    ingest_payload(store, PAYLOAD, NOW)
    again = ingest_payload(store, PAYLOAD, NOW)
    assert again.stored == {} and again.unchanged == 5
    assert store.health_daily_sum("steps", "2026-10-05") == 5300
    updated = dict(PAYLOAD, steps=[{"count": 3500, "start_time": "2026-10-05T03:00:00Z", "end_time": "2026-10-05T04:00:00Z"}])
    ingest_payload(store, updated, NOW)
    assert store.health_daily_sum("steps", "2026-10-05") == 5600
    assert len(store.metric_series("weight_kg", "2026-10-01")) == 1


def test_bad_records_reported_not_crashing(store):
    result = ingest_payload(
        store,
        {"steps": [{"count": "abc", "start_time": "x", "end_time": "y"}], "weight": [{"kilograms": 5, "time": "2026-10-05T02:00:00Z"}]},
        NOW,
    )
    assert len(result.errors) == 2 and result.stored == {}


def test_local_date_uses_user_timezone(store):
    # 20:00Z on Oct 4 is 01:30 IST on Oct 5.
    ingest_payload(store, {"steps": [{"count": 100, "start_time": "2026-10-04T20:00:00Z", "end_time": "2026-10-04T21:00:00Z"}]}, NOW)
    assert store.health_daily_sum("steps", "2026-10-05") == 100
    assert store.health_daily_sum("steps", "2026-10-04") is None


def test_api_requires_secret(store):
    client = TestClient(create_app(store, "s3cret", clock=lambda: NOW))
    assert client.post("/ingest/health-connect", json=PAYLOAD).status_code == 401
    assert client.post("/ingest/health-connect", json=PAYLOAD, headers={"x-api-key": "wrong"}).status_code == 401
    ok = client.post("/ingest/health-connect", json=PAYLOAD, headers={"x-api-key": "s3cret"})
    assert ok.status_code == 200 and ok.json()["stored"]["steps"] == 2


def test_api_disabled_without_configured_secret(store):
    client = TestClient(create_app(store, "", clock=lambda: NOW))
    assert client.post("/ingest/health-connect", json=PAYLOAD, headers={"x-api-key": ""}).status_code == 401


def test_steps_appear_in_coach_context_as_observed(service, store):
    ingest_payload(store, PAYLOAD, NOW)
    ctx = service.build_context(NOW)
    assert ctx["today"]["steps"] == {"value": 5300.0, "source": "health_connect"}
    assert ctx["today"]["sleep_hours_last_night"] == 7.0
    assert "last sync" in ctx["health_connect"]
