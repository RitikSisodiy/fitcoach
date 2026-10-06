# Architecture

Status: **v0.2** (redesigned after GAP_ANALYSIS.md; §9 describes what changed). Module map:

```
coach/
├── __main__.py            entry point: Telegram + planner + ingest in one process
├── chat.py                terminal chat for local testing
├── config.py              env-based settings (secrets redacted in logs)
├── timeutil.py            UTC storage, local-day logic, 30-min slot grid
├── llm/                   provider abstraction (base, gemini, claude, openai_compat, fake, factory)
├── memory/                db.py (schema + migrations), store.py (repository)
├── engine/                extractor, service (orchestration), policy (gate/ladder/learner), coach (LLM wording), safety
├── nutrition/             estimator + seed food table (CSV)
├── analytics/progress.py  trend weight, day totals, target-change gate
├── analytics/habits.py    habitual/declared meals, coverage, intake estimate incl. assumed usual meals
├── analytics/patterns.py  deterministic pattern mining with evidence counts
├── engine/brain.py        candidate intents + gate + LLM choose/silence/write
├── engine/engagement.py   engagement state machine, per-intent response rates, retirement
├── ingest/                Health Connect, notifications/SMS (orders, UPI), calendar ICS, FastAPI endpoints
└── channels/telegram_bot.py
``` See `DECISIONS.md` for the reasoning behind each choice.

## 1. Overview

```
 Phone                                   Coach server (single Python process)
 ┌─────────────────────┐                ┌──────────────────────────────────────────────┐
 │ Samsung Health /    │   HTTPS POST   │  ingest/  FastAPI  /ingest/health-connect     │
 │ Google Health /     │ ─────────────▶ │           (shared-secret header, idempotent)  │
 │ scale app           │                │                     │                         │
 │   → Health Connect  │                │                     ▼                         │
 │   → HC Webhook app  │                │  memory/  SQLite (WAL) — the source of truth  │
 └─────────────────────┘                │                     ▲                         │
                                        │                     │                         │
 ┌─────────────────────┐  long polling  │  channels/telegram ──▶ engine/                │
 │ Telegram            │ ◀────────────▶ │     text/voice/photo     extractor  (LLM)     │
 │ text, voice, photo, │                │     one-tap buttons      validator  (code)    │
 │ one-tap buttons     │                │                          nutrition  (code)    │
 └─────────────────────┘                │                          coach      (LLM)     │
                                        │                          tone guard (code)    │
                                        │  scheduler ──▶ CoachService.tick (code decides   │
                                        │   (every N min)          whether; LLM decides │
                                        │                          wording)             │
                                        │  llm/  LLMProvider ── Gemini | Claude |       │
                                        │                       OpenAI | OpenRouter     │
                                        └──────────────────────────────────────────────┘
```

There is **no custom Android app** in v0.1. Health Connect data reaches the server through the open-source *Health Connect Webhook* app. A Kotlin companion app is a fallback (see `TASKS.md`).

## 2. Responsibilities: deterministic vs LLM

| Concern | Owner | Module |
|---|---|---|
| Parsing natural language / voice / photo into candidate events | LLM | `engine/extractor.py` |
| Validating, clamping, and normalising extracted events | Code | `engine/extractor.py` (`validate_extraction`) |
| Nutrition numbers (ranges) | Code (food table); LLM only as flagged fallback | `nutrition/` |
| Persistence, idempotency, supersession | Code | `memory/store.py` |
| Trend weight, averages, data quality | Code | `analytics/progress.py` |
| *Whether* a proactive message may be sent (budget, quiet hours, pause, intensity, escalation level) | Code | `engine/policy.py`, `CoachService.tick` |
| Which time slot to try (learning) | Code (Thompson sampling) | `engine/policy.py` (`SlotLearner`) |
| Wording of replies and nudges, contextual reasoning, pattern explanation | LLM | `engine/coach.py` |
| Shame/unsafe language and unsafe numeric advice filtering | Code | `engine/safety.py` |

## 3. Message flow (inbound)

1. Telegram update → `channels/telegram_bot.py` normalises it to `InboundMessage` (text, optional audio/image bytes).
2. `CoachService.handle_message`:
   1. Skip if the Telegram `update_id` was already processed.
   2. `Extractor.extract` → LLM with JSON schema → `Extraction` (food items, activities, weight, context events, commitments, facts, intervention response, lapse flag).
   3. `validate_extraction` clamps impossible values and drops low-confidence noise.
   4. In **one transaction** (so a crash leaves no partial state and a redelivered update is processed cleanly), store the message row (dedupe key) and: food events (with nutrition ranges from `nutrition/`), activity, weight, context events, facts (with supersession), commitments.
   5. Context triggers: for each context tag in the message, look up **active commitments with that trigger** (deterministic match on normalised tags; the LLM is given the existing trigger vocabulary so it reuses tags).
   6. If the message responds to an open intervention, record the outcome.
   7. Build `CoachContext` (core profile, goals, mode, active commitments, triggered commitments, today's totals with data-quality labels, trend, learned patterns, recent conversation).
   8. `Coach.reply` → LLM → `SafetyGuard.check` → send. Decision summary logged in `coach_decisions`.

## 4. Proactive flow (outbound)

Every `PLANNER_INTERVAL_MINUTES` the scheduler runs `CoachService.tick(now)`:

1. For each active commitment that has a due window today and no completion: compute the candidate.
2. `Policy` gates: paused? quiet hours? daily budget left? min gap since last proactive message? already nudged for this commitment today?
3. `EscalationLadder` picks the level from the commitment's recent outcome history (ignored streak, skip count this week) and the user's coaching intensity.
4. `SlotLearner` (Beta-Bernoulli Thompson sampling with exploration floor and forgetting) chooses whether *this* slot is a good one.
5. The coach LLM writes the message for the chosen level; safety guard; send with one-tap buttons (`Done`, `Smaller version`, `Later`, `Skip today`).
6. An `interventions` row is created with status `sent`. Button presses / replies / expiry update it to `acted`, `smaller`, `snoozed`, `skipped`, `ignored`. Outcomes feed the slot learner and the ladder.

## 5. Storage (SQLite)

See `MEMORY.md` for the full schema rationale. Key tables:

`profile`, `facts`, `messages` (+ `messages_fts`), `food_events`, `activity_events`, `body_metrics`, `context_events`, `health_records` (raw HC upserts), `commitments`, `interventions`, `slot_stats`, `coach_decisions`, `processed_updates`.

## 6. LLM provider abstraction

```
LLMProvider (abstract)            llm/base.py
├── GeminiProvider               llm/gemini.py      text, image, audio, JSON schema
├── ClaudeProvider               llm/claude.py      text, image, JSON (via tool-forcing)
├── OpenAIProvider               llm/openai_compat.py text, image, JSON schema
├── OpenRouterProvider           llm/openai_compat.py (OpenAI-compatible base URL)
└── FakeProvider                 llm/fake.py        deterministic, for tests
```

`create_provider(settings)` selects by `LLM_PROVIDER`; the model is `LLM_MODEL`. Nothing outside `llm/` imports a vendor SDK. Providers declare `capabilities` (`audio`, `image`); the service degrades gracefully (asks for text) when audio isn't supported.

## 7. Configuration and secrets

All configuration comes from environment variables (`.env` supported, never committed). See `.env.example`. API keys are never logged.

## 8. Deployment (target)

Single process (`python -m coach`) on a small always-on machine (home server / VPS). Telegram uses long polling (no public URL needed). The HC ingest endpoint needs to be reachable from the phone: Tailscale Funnel or Cloudflare Tunnel, protected by `INGEST_SECRET`.


## 9. v0.2 redesign

```
 Phone ── Health Connect Webhook ──▶ /ingest/health-connect ─┐
 Phone ── SmsForwarder/MacroDroid ─▶ /ingest/notification ───┤   (orders, small UPI payments → inferred_events)
 Calendar ICS (polled 30 min) ───────────────────────────────┤   (times only → calendar_days)
 Telegram (text/voice/photo/buttons/quick bar) ──────────────┤
                                                             ▼
                                                      SQLite (truth)
                                                             │
   daily: pattern miner (weekday_context, weekday_payee, context_lapse, meal_gap; evidence-gated)
   tick:  housekeeping (expire, auto-complete from HC, process inferred)
          → engagement state (budget, allowed/retired intents, recap cadence)
          → candidates (commitment, recap, morning_plan, predicted_context, inactivity,
                        reengage, propose_commitment, onboarding, stale_sync)
          → gate (pause, quiet, budget, gap, once/intent/day, retired, recent LLM silence)
          → LLM decide: pick one or SILENCE, write text  → safety guard → send with intent-specific buttons
   reply: extraction (+ corrections, commitment edits, inferred confirmations, usual meals, pattern feedback,
          data_needed) → transaction → code answers data queries → LLM reply with numbers from code
```

**Principle:** code computes every number and enforces every limit. The LLM interprets language, chooses among pre-approved options (including silence), and writes.
