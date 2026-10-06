# Memory

The LLM is never the database. All state lives in SQLite (`coach/memory/db.py`); all writes go through `Store` (`coach/memory/store.py`).

## Layers

| Layer | Tables | Written by | Lifetime |
|---|---|---|---|
| Profile and settings | `profile` (key/value JSON) | commands, buttons, validated requests | until changed |
| Semantic facts | `facts` | extractor (user-stated) | superseded, never deleted |
| Episodic events | `food_events`, `activity_events`, `body_metrics`, `context_events` | extractor + validation, ingest | permanent |
| Device data | `health_records` | Health Connect ingest | permanent, upserted |
| Behaviour | `commitments`, `commitment_log`, `interventions`, `slot_stats` | service/policy | permanent |
| Conversation | `messages` + `messages_fts` | service | permanent |
| Observability | `coach_decisions` | service | permanent |
| Passive inferences | `inferred_events` (pending → confirmed/rejected/auto_logged/expired), `payee_labels` | ingest, recap, conversation | permanent |
| Patterns | `patterns` (support, contradict, distinct_weeks, confidence, status) | daily miner; user feedback | recomputed daily; rejection permanent |
| Calendar | `calendar_days` (meeting hours, first/last, free slots; no titles) | ICS poll | permanent |
| Jobs | `job_runs` | scheduler | permanent |

## Facts

Each fact has: `category, key, value, source (user_stated|inferred|observed), source_message_id, confidence, created_at, last_verified_at, superseded_by`.
- The same key with the same value refreshes `last_verified_at`.
- The same key with a new value inserts a new row and points the old one at it, so history is kept.
- Facts below 0.5 confidence are not stored. One-off remarks are excluded by the extraction prompt.
- Categories: preference, constraint, routine, goal, pattern, health, other.

## Food sources (v0.2)

| `source` | Meaning | Confidence |
|---|---|---|
| user_entered | typed, spoken or photographed by the user | extraction × match |
| default_confirmed | "All usual" tap | × 0.7 |
| inferred_confirmed | passive signal the user confirmed | 0.4 |
| inferred_known_payee | payment to a payee the user once confirmed as food | 0.5 |
| inferred_unconfirmed | order / recurring snack stop nobody confirmed | 0.3–0.35 |

Habit learning uses only `user_entered` and `default_confirmed`. Assumed usual meals are **computed** (`estimated_day_intake`) and never stored.

## Data status labels

Every number shown to the LLM carries a status:

| Status | Meaning | Example |
|---|---|---|
| observed | measured by a device | Health Connect steps, scale weight |
| estimated | computed range from user input | food kcal ranges |
| inferred | derived pattern | dominant skip reason, slot success |
| unknown | no data | steps when nothing was synced |

## Idempotency

- Telegram messages are keyed on `tg:<update_id>`. The message row and everything extracted from it commit in one transaction.
- Health Connect records are keyed on normalised `(type, start, end)` or `(type, time)`, and an upsert replaces changed values.
- Health Connect weight goes to `body_metrics` with natural key `hc|weight|<time>`.

## What is sent to the LLM

| Purpose | What is included |
|---|---|
| Reply | Compact context: mode, targets, active facts, active commitments, today's totals with status, weight trend, Health Connect freshness. Plus the last ~10 messages and deterministic guidance lines. |
| Extraction | Known context tags (for tag reuse), active commitments, and open nudges (so replies to nudges are linked). |
| Raw data | Raw Health Connect payloads and full history are never sent. |

## Retention and privacy

Everything is local to the server. Not yet implemented:
- export/delete commands (T-801)
- encryption at rest (T-802)
