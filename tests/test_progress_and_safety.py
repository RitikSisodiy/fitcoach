from datetime import date, timedelta

from coach.analytics.progress import can_adjust_targets, ewma, weight_trend
from coach.engine.safety import check_message


def _series(start: str, values: list[float]) -> list[tuple[str, float]]:
    d0 = date.fromisoformat(start)
    return [((d0 + timedelta(days=i)).isoformat(), v) for i, v in enumerate(values)]


def test_ewma_smooths_spikes():
    out = ewma([80, 80, 82, 80, 80])
    assert max(out) < 80.3


def test_insufficient_data_never_claims_a_trend():
    t = weight_trend(_series("2026-10-01", [80, 79.5]), "2026-10-02")
    assert t.status == "insufficient_data"


def test_steady_loss_detected():
    values = [80 - 0.07 * i for i in range(21)]  # ~0.5 kg/week
    t = weight_trend(_series("2026-09-15", values), "2026-10-05")
    assert t.status == "losing"
    assert -0.8 < t.weekly_rate_kg < -0.1


def test_too_fast_loss_flagged():
    values = [80 - 0.2 * i for i in range(21)]  # 1.4 kg/week
    t = weight_trend(_series("2026-09-15", values), "2026-10-05")
    assert t.status == "losing_too_fast"


def test_single_noisy_reading_does_not_flip_status():
    values = [80.0] * 14 + [81.8]
    t = weight_trend(_series("2026-09-20", values), "2026-10-04")
    assert t.status in {"stable", "gaining"}
    assert t.latest_trend < 80.3


def test_target_change_gated_on_data():
    t = weight_trend(_series("2026-09-28", [80] * 8), "2026-10-05")
    ok, why = can_adjust_targets(t, 14)
    assert not ok and "weight" in why.lower()
    t2 = weight_trend(_series("2026-09-15", [80] * 21), "2026-10-05")
    ok2, why2 = can_adjust_targets(t2, 4)
    assert not ok2 and "food" in why2.lower()
    assert can_adjust_targets(t2, 10)[0]


def test_safety_catches_shame_language():
    assert not check_message("You're lazy, you failed again.").ok
    assert not check_message("Bhai thoda nikamma ho gaye ho aaj").ok
    assert not check_message("I am disappointed in you.").ok


def test_safety_catches_unsafe_advice():
    assert not check_message("Skip all meals tomorrow to make up for it.").ok
    assert not check_message("Avoid drinking water before weigh-in.").ok
    assert not check_message("Try a laxative.").ok
    assert not check_message("Eat only 900 kcal a day this week.").ok


def test_safety_allows_normal_coaching():
    assert check_message("That happened. Next meal normal rakho - dal, roti, sabzi. 10 min walk after?").ok
    assert check_message("Aim for about 1800 kcal a day for now.").ok
