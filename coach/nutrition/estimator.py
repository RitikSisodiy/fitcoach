"""Deterministic nutrition estimation with explicit uncertainty.

The LLM identifies foods and household quantities; this module turns them into
kcal/protein *ranges* using a food table. Values in the seed table are
approximate household-portion figures intended to be replaced by an INDB import
(see TASKS.md, T-301). LLM-provided estimates are only used for foods the table
does not know, and are labelled as such.
"""

from __future__ import annotations

import csv
import difflib
import re
from dataclasses import dataclass
from pathlib import Path

SEED_PATH = Path(__file__).parent / "data" / "foods_seed.csv"

# Approximate grams/ml for generic household units.
GENERIC_UNIT_GRAMS: dict[str, float] = {
    "g": 1,
    "gram": 1,
    "ml": 1,
    "katori": 150,
    "bowl": 200,
    "cup": 150,
    "glass": 250,
    "plate": 250,
    "handful": 30,
    "tbsp": 15,
    "tsp": 5,
    "slice": 30,
    "scoop": 30,
    "serving": 150,
    "can": 330,
    "packet": 70,
}

UNIT_SYNONYMS: dict[str, str] = {
    "pc": "piece",
    "pcs": "piece",
    "pieces": "piece",
    "nos": "piece",
    "no": "piece",
    "unit": "piece",
    "grams": "g",
    "gm": "g",
    "gms": "g",
    "kg": "kg",
    "bowls": "bowl",
    "katoris": "katori",
    "cups": "cup",
    "glasses": "glass",
    "plates": "plate",
    "spoon": "tbsp",
    "tablespoon": "tbsp",
    "teaspoon": "tsp",
    "chammach": "tbsp",
    "mutthi": "handful",
    "slices": "slice",
    "scoops": "scoop",
    "packets": "packet",
}

CONVERSION_WIDEN = 0.15  # extra relative uncertainty when converting between units
UNKNOWN_QTY_WIDEN = 0.10


@dataclass
class FoodRow:
    key: str
    aliases: list[str]
    default_unit: str
    grams_per_unit: float
    kcal_low: float
    kcal_high: float
    protein_low: float
    protein_high: float
    category: str


@dataclass
class NutritionEstimate:
    food_key: str | None
    kcal_low: float | None
    kcal_high: float | None
    protein_low: float | None
    protein_high: float | None
    nutrition_source: str  # food_table | llm_estimate | unknown
    confidence: float
    note: str = ""


def _norm(text: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]+", " ", text.lower())).strip()


def normalize_unit(unit: str | None) -> str | None:
    if not unit:
        return None
    u = _norm(unit)
    return UNIT_SYNONYMS.get(u, u)


class FoodTable:
    def __init__(self, rows: list[FoodRow]):
        self.rows = {r.key: r for r in rows}
        self.alias_index: dict[str, str] = {}
        for row in rows:
            for alias in row.aliases + [row.key.replace("_", " ")]:
                self.alias_index.setdefault(_norm(alias), row.key)

    @classmethod
    def load(cls, path: Path = SEED_PATH) -> "FoodTable":
        with path.open(encoding="utf-8") as fh:
            rows = [
                FoodRow(
                    key=r["key"],
                    aliases=[a.strip() for a in r["aliases"].split("|") if a.strip()],
                    default_unit=r["default_unit"],
                    grams_per_unit=float(r["grams_per_unit"]),
                    kcal_low=float(r["kcal_low"]),
                    kcal_high=float(r["kcal_high"]),
                    protein_low=float(r["protein_low"]),
                    protein_high=float(r["protein_high"]),
                    category=r["category"],
                )
                for r in csv.DictReader(fh)
            ]
        return cls(rows)

    def match(self, name: str) -> tuple[FoodRow | None, str]:
        """Return (row, match_kind) where match_kind is exact | close | partial | none."""
        n = _norm(name)
        candidates = [n]
        if n.endswith("es"):
            candidates.append(n[:-2])
        if n.endswith("s"):
            candidates.append(n[:-1])
        for c in candidates:
            if c in self.alias_index:
                return self.rows[self.alias_index[c]], "exact"
        close = difflib.get_close_matches(n, list(self.alias_index), n=1, cutoff=0.86)
        if close:
            return self.rows[self.alias_index[close[0]]], "close"
        # Longest alias contained as whole words, e.g. "ghar ki dal" -> dal.
        best: str | None = None
        for alias in self.alias_index:
            if len(alias) >= 3 and re.search(rf"\b{re.escape(alias)}\b", n):
                if best is None or len(alias) > len(best):
                    best = alias
        if best:
            return self.rows[self.alias_index[best]], "partial"
        return None, "none"


def _scale(row: FoodRow, quantity: float | None, unit: str | None) -> tuple[float, float, str]:
    """Return (scale_factor, extra_widen, note) relative to the row's default unit."""
    qty = quantity if quantity and quantity > 0 else 1.0
    widen = 0.0 if quantity else UNKNOWN_QTY_WIDEN
    u = normalize_unit(unit)
    if u is None or u == row.default_unit or (u == "piece" and row.default_unit in {"piece", "slice"}):
        return qty, widen, "" if quantity else "quantity assumed 1"
    if u == "kg":
        return qty * 1000 / row.grams_per_unit, widen + CONVERSION_WIDEN, "converted from kg"
    if u in GENERIC_UNIT_GRAMS:
        grams = qty * GENERIC_UNIT_GRAMS[u]
        return grams / row.grams_per_unit, widen + CONVERSION_WIDEN, f"converted {u}->{row.default_unit}"
    # Unknown unit: assume the default unit but widen generously.
    return qty, widen + 2 * CONVERSION_WIDEN, f"unknown unit '{unit}', assumed {row.default_unit}"


def estimate(
    table: FoodTable,
    name: str,
    quantity: float | None,
    unit: str | None,
    llm_kcal: tuple[float | None, float | None] = (None, None),
    llm_protein: tuple[float | None, float | None] = (None, None),
    extraction_confidence: float = 0.8,
) -> NutritionEstimate:
    row, kind = table.match(name)
    if row is not None and kind in {"exact", "close"}:
        return _from_row(row, quantity, unit, 0.85 if kind == "exact" else 0.7, extraction_confidence)

    lk_low, lk_high = llm_kcal
    if lk_low is not None and lk_high is not None and 0 <= lk_low <= lk_high <= 5000:
        lp_low, lp_high = llm_protein
        # LLM estimates get an explicit extra 20 % on each side (documented accuracy is poor).
        return NutritionEstimate(
            food_key=None,
            kcal_low=round(lk_low * 0.8),
            kcal_high=round(lk_high * 1.2),
            protein_low=round(lp_low * 0.8, 1) if lp_low is not None else None,
            protein_high=round(lp_high * 1.2, 1) if lp_high is not None else None,
            nutrition_source="llm_estimate",
            confidence=round(min(0.5, extraction_confidence), 2),
            note="not in food table; LLM estimate widened by 20%",
        )

    if row is not None:  # partial match is better than nothing, but low confidence
        est = _from_row(row, quantity, unit, 0.45, extraction_confidence)
        est.note = (est.note + "; " if est.note else "") + f"partial match to '{row.key}'"
        return est

    return NutritionEstimate(None, None, None, None, None, "unknown", 0.0, "food not recognised")


def _from_row(
    row: FoodRow, quantity: float | None, unit: str | None, match_conf: float, extraction_confidence: float
) -> NutritionEstimate:
    factor, widen, note = _scale(row, quantity, unit)
    return NutritionEstimate(
        food_key=row.key,
        kcal_low=round(row.kcal_low * factor * (1 - widen)),
        kcal_high=round(row.kcal_high * factor * (1 + widen)),
        protein_low=round(row.protein_low * factor * (1 - widen), 1),
        protein_high=round(row.protein_high * factor * (1 + widen), 1),
        nutrition_source="food_table",
        confidence=round(min(match_conf, extraction_confidence) * (1 - widen), 2),
        note=note,
    )
