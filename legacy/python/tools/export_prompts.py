"""Export prompts and JSON schemas from the Python reference engine for the Android app.

Single source of truth: the Android app loads app/src/main/assets/prompts.json, generated from the Python engine.

    python tools/export_prompts.py
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from coach.engine import brain, coach, extractor  # noqa: E402

OUT = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/prompts.json"

data = {
    "extraction_system": extractor.EXTRACTION_SYSTEM,
    "extraction_schema": extractor.EXTRACTION_SCHEMA,
    "focused_schemas": extractor.FOCUSED_SCHEMAS,
    "focused_questions": extractor.FOCUSED_QUESTIONS,
    "coach_system": coach.COACH_SYSTEM,
    "decide_instructions": brain.DECIDE_INSTRUCTIONS,
    "decide_schema": brain.DECIDE_SCHEMA,
}
OUT.write_text(json.dumps(data, ensure_ascii=False, indent=1))
print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
