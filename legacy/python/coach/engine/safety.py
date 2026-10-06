"""Deterministic guard on every outgoing coach message.

Catches shame/guilt language and unsafe health advice. The LLM is instructed not
to produce these, but instructions are not guarantees, so code enforces them.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

MIN_SAFE_DAILY_KCAL = 1200  # never suggest a daily target below this

SHAME_PATTERNS = [
    r"\blazy\b",
    r"\bfailed again\b",
    r"\byou failed\b",
    r"\bno discipline\b",
    r"\black of discipline\b",
    r"\bruin(ing|ed)? (your|ur) life\b",
    r"\bdisappointed (in|with) you\b",
    r"\byou disappointed me\b",
    r"\bpathetic\b",
    r"\bshame on you\b",
    r"\bworthless\b",
    r"\bkaamchor\b",
    r"\bnikamma\b",
    r"\bsharam\b",
]

UNSAFE_PATTERNS = [
    (r"\b(skip|avoid)\b.{0,20}\b(water|drinking water|pani)\b", "dehydration advice"),
    (r"\bdehydrat", "dehydration advice"),
    (r"\blaxative", "laxatives"),
    (r"\bdiuretic", "diuretics"),
    (r"\bstarv", "starvation"),
    (r"\b(skip|don'?t eat)\b.{0,30}\b(all|every|next)\b.{0,15}\b(meals?|day)\b", "meal skipping to compensate"),
    (r"\b(stop|change|reduce|increase) (your )?(medication|medicine|dose|dawai)\b", "medication change"),
    (r"\bmake up for (it|that) by (not eating|skipping)", "compensatory restriction"),
    (r"\bpurg(e|ing)\b", "purging"),
]

KCAL_TARGET_PATTERN = re.compile(
    r"\b(eat|target|limit|stay under|keep it under|restrict to|only)\b[^.\n]{0,25}?\b(\d{3,4})\s*(k?cal|calories)\b",
    re.IGNORECASE,
)


@dataclass
class SafetyResult:
    ok: bool
    violations: list[str] = field(default_factory=list)


def check_message(text: str) -> SafetyResult:
    lowered = text.lower()
    violations: list[str] = []
    for pattern in SHAME_PATTERNS:
        if re.search(pattern, lowered):
            violations.append(f"shame language: {pattern}")
    for pattern, label in UNSAFE_PATTERNS:
        if re.search(pattern, lowered):
            violations.append(f"unsafe advice: {label}")
    for match in KCAL_TARGET_PATTERN.finditer(text):
        if int(match.group(2)) < MIN_SAFE_DAILY_KCAL and "day" in text[match.start() : match.end() + 20].lower():
            violations.append(f"unsafe calorie target: {match.group(2)}")
    return SafetyResult(ok=not violations, violations=violations)


SAFE_FALLBACK_REPLY = "Noted. The next decision is the one that matters - want to keep it simple and stick to the plan for the next meal?"
