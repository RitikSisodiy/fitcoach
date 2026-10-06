from coach.nutrition.estimator import FoodTable, estimate

TABLE = FoodTable.load()


def test_exact_match_scales_by_quantity():
    est = estimate(TABLE, "roti", 3, None)
    assert est.food_key == "roti"
    assert est.nutrition_source == "food_table"
    assert (est.kcal_low, est.kcal_high) == (270, 360)


def test_plural_and_alias_match():
    assert TABLE.match("rotis")[0].key == "roti"
    assert TABLE.match("Dahi")[0].key == "curd"
    assert TABLE.match("chapatis")[0].key == "roti"


def test_unit_conversion_widens_range():
    same_unit = estimate(TABLE, "rice", 1, "katori")
    converted = estimate(TABLE, "rice", 1, "plate")  # 250 g vs 150 g katori
    assert converted.kcal_low > same_unit.kcal_low * 1.3
    assert (converted.kcal_high - converted.kcal_low) > (same_unit.kcal_high - same_unit.kcal_low)
    assert converted.confidence < same_unit.confidence


def test_missing_quantity_assumes_one_with_lower_confidence():
    known = estimate(TABLE, "samosa", 1, "piece")
    unknown_qty = estimate(TABLE, "samosa", None, None)
    assert unknown_qty.kcal_low < known.kcal_low and unknown_qty.kcal_high > known.kcal_high
    assert "assumed" in unknown_qty.note


def test_unknown_food_uses_llm_estimate_flagged_and_widened():
    est = estimate(TABLE, "sev parmal", 1, "plate", llm_kcal=(250, 350), llm_protein=(4, 6))
    assert est.nutrition_source == "llm_estimate"
    assert est.food_key is None
    assert est.kcal_low == 200 and est.kcal_high == 420
    assert est.confidence <= 0.5


def test_unknown_food_without_estimate_is_unknown_not_guessed():
    est = estimate(TABLE, "zzqx dish", 1, None)
    assert est.nutrition_source == "unknown"
    assert est.kcal_low is None and est.kcal_high is None


def test_partial_match_only_when_no_llm_estimate():
    est = estimate(TABLE, "ghar wali dal", 1, "katori")
    assert est.food_key == "dal"
    assert est.confidence < 0.5
    est2 = estimate(TABLE, "ghar wali dal", 1, "katori", llm_kcal=(150, 200))
    # "ghar wali dal" is not an exact/close alias, so the explicit LLM estimate wins over a weak partial match
    assert est2.nutrition_source == "llm_estimate"
