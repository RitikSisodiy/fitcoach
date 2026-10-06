from coach.engine.extractor import validate_extraction

from .conftest import empty_extraction


def test_non_object_is_rejected():
    ex = validate_extraction(["nope"], set())
    assert ex.foods == [] and ex.dropped


def test_food_low_confidence_dropped_and_bad_quantity_ignored():
    raw = empty_extraction(
        food_items=[
            {"name": "roti", "quantity": 3, "unit": "piece", "eaten": "eaten", "confidence": 0.9},
            {"name": "maybe biscuit", "eaten": "eaten", "confidence": 0.1},
            {"name": "rice", "quantity": 400, "eaten": "eaten", "confidence": 0.8},
            {"name": "pizza", "eaten": "planned", "confidence": 0.9},
        ]
    )
    ex = validate_extraction(raw, set())
    names = [f.name for f in ex.foods]
    assert names == ["roti", "rice", "pizza"]
    assert ex.foods[1].quantity is None
    assert ex.foods[2].eaten is False


def test_implausible_weight_dropped():
    raw = empty_extraction(
        body_metrics=[
            {"metric": "weight_kg", "value": 7.8, "confidence": 0.9},
            {"metric": "weight_kg", "value": 78.4, "confidence": 0.9},
        ]
    )
    ex = validate_extraction(raw, set())
    assert ex.body_metrics == [{"metric": "weight_kg", "value": 78.4}]


def test_planned_activity_is_not_recorded_as_done():
    raw = empty_extraction(
        activities=[
            {"kind": "walk", "status": "planned", "duration_min": 20, "confidence": 0.9},
            {"kind": "walk", "status": "done", "duration_min": 20, "confidence": 0.9},
        ]
    )
    assert len(validate_extraction(raw, set()).activities) == 1


def test_update_for_unknown_commitment_dropped():
    raw = empty_extraction(commitment_updates=[{"commitment_id": 99, "outcome": "done"}, {"commitment_id": 1, "outcome": "done"}])
    ex = validate_extraction(raw, {1})
    assert [u["commitment_id"] for u in ex.commitment_updates] == [1]


def test_half_specified_window_is_cleared():
    raw = empty_extraction(
        commitments=[{"kind": "habit", "title": "Walk", "action": "walk 20 min", "window_start": "17:00"}]
    )
    com = validate_extraction(raw, set()).commitments[0]
    assert com["window_start"] is None and com["window_end"] is None
    assert com["versions"] == ["walk 20 min"]


def test_invalid_time_rejected():
    raw = empty_extraction(
        commitments=[
            {"kind": "habit", "title": "Walk", "action": "walk", "window_start": "25:00", "window_end": "19:00"}
        ]
    )
    com = validate_extraction(raw, set()).commitments[0]
    assert com["window_start"] is None


def test_settings_request_validated():
    raw = empty_extraction(settings_request={"coaching_mode": "strong", "pause_days": 400})
    ex = validate_extraction(raw, set())
    assert ex.coaching_mode_request == "strong"
    assert ex.pause_days is None


def test_ungrounded_items_are_dropped():
    raw = empty_extraction(
        food_items=[{"name": "chaat", "eaten": "eaten", "confidence": 0.9, "quote": "40 rupay chaat"}],
        settings_request={"coaching_mode": "normal", "quote": "mode normal karo"},
    )
    ex = validate_extraction(raw, set(), message="ok")
    assert ex.foods == [] and ex.coaching_mode_request is None and len(ex.dropped) == 2


def test_grounded_items_kept_with_minor_paraphrase():
    raw = empty_extraction(food_items=[{"name": "roti", "quantity": 3, "eaten": "eaten", "confidence": 0.9, "quote": "3 roti khayi"}])
    assert validate_extraction(raw, set(), message="3 roti, chicken aur sabzi khayi lunch me").foods


def test_media_messages_skip_grounding():
    raw = empty_extraction(food_items=[{"name": "biryani", "eaten": "eaten", "confidence": 0.8}])
    assert validate_extraction(raw, set(), message=None).foods


def test_food_not_in_message_is_dropped_but_aliases_count():
    raw = empty_extraction(food_items=[
        {"name": "chaat", "eaten": "eaten", "confidence": 0.9, "quote": "kuch bhi kha raha hu"},
        {"name": "curd", "eaten": "eaten", "confidence": 0.9, "quote": "dahi khaya"},
    ])
    ex = validate_extraction(raw, set(), message="stress me kuch bhi kha raha hu, dahi khaya",
                             aliases_for=lambda n: ["dahi", "yogurt"] if n == "curd" else [])
    assert [f.name for f in ex.foods] == ["curd"]


def test_flat_goal_settings_and_usual_meals():
    msg = "mujhe 76 kg tak aana hai, lunch me mostly 3 roti dal hota hai, coach ek hafte band karo"
    raw = empty_extraction(
        goal_weight_kg=76, goal_text="reach 76 kg", goal_quote="76 kg tak aana hai",
        usual_meals=[{"slot": "lunch", "quote": "lunch me mostly 3 roti dal", "items": [{"name": "roti", "quantity": 3}]}],
        pause_days=7, settings_quote="coach ek hafte band karo",
    )
    ex = validate_extraction(raw, set(), message=msg)
    assert ex.profile_updates["goal_weight_kg"] == 76 and "lunch" in ex.profile_updates["usual_meals"]
    assert ex.pause_days == 7


def test_rule_without_trigger_gets_the_single_situation():
    raw = empty_extraction(
        context=[{"tag": "chess", "timing": "planned"}],
        commitments=[{"kind": "precommitment", "title": "Coffee only", "action": "coffee only", "quote": "chess pe sirf coffee"}],
    )
    ex = validate_extraction(raw, set(), message="chess pe sirf coffee lunga")
    assert ex.commitments[0]["trigger_tag"] == "chess"


def test_activity_kind_inferred_when_model_omits_it():
    raw = empty_extraction(commitments=[{"kind": "habit", "title": "Evening walk", "action": "Walk 30 min", "quote": "30 min walk karunga"}])
    assert validate_extraction(raw, set(), message="roz 30 min walk karunga").commitments[0]["activity_kind"] == "walk"
