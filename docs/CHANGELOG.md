# Changelog

## 2.1.0 — 2026-10-07 (voice calls, media, real dashboard)

### Added
- **Coach calls:** the agent can decide to call. Android shows "Coach wants to talk" with a ringtone (full screen where allowed). Answering starts a live Gemini voice conversation with on-screen transcripts of both sides, mute, speaker and end. The coach ends the call when the conversation is done. The transcript becomes memory, and the agent decides any follow-up. You can also call the coach yourself from Chat (📞).
- **Dashboard:**
  - progress: goal progress, adherence for 7 and 14 days, logging coverage, average kcal and protein, steps compared with the previous week, message answer and action rates, agent activity;
  - voice calls;
  - data-source freshness and access status.
- **Updates in Setup:** installed and latest versions, check now, update, release notes, and visible errors.

### Fixed
- Photos sent on Telegram got no reply. The image is now described by the multimodal model, answered, and stored. Voice notes are transcribed the same way.

### Changed
- Off-plan food, meal times and gym check-outs are no longer decided by fixed lists or times (see `AUDIT.md`, B1–B3).

## 2.0.0 — 2026-10-07 (autonomous agent)

### Added
- **Agent loop** (`Agent.kt`): the LLM decides whether to act, the message, quick replies, channel and its own next check time, from one SITUATION built from the database.
  - It wakes on significant observations, on its own schedule, or after 4 h without evaluating.
- **Outcome evaluation:** answered (with minutes), ignored, achieved or not_achieved. A daily LLM reflection turns these into coach insights that later decisions and replies use.
- **Memory tiers:** long-term and temporary memory (with expiry) captured from natural chat, grounded in the user's words, and retrieved in every reply and decision.
- **Telegram:** the phone long-polls the user's bot, so app chat and Telegram are one conversation with one memory. Pairing uses a code. Quick replies appear as inline buttons, and voice and photos work.
- **Dashboard:**
  - today, weight chart with trend, steps and food charts
  - commitments with 14-day dots
  - memory and coach insights
  - interventions and their outcomes
  - observations and agent decisions
- `LiveAgentTest` (real Gemini, 3 simulated days) and live Telegram send tests.

### Removed
- Fixed-window candidates (the 21:00 recap, morning plan, and others), template fallbacks, scripted button replies, the escalation ladder, and weekly-review and snooze schedules. See `AUDIT.md`.
- Python engine moved to `legacy/python/`. Prompts now live in `assets/prompts/`.

## 1.0.0 — 2026-10-06 (Android app, no server)

### Added
- Native Android app (Kotlin, Jetpack Compose) with the full engine ported from Python:
  - store, extraction validator, nutrition estimation, safety guard, policy ladder, slot learner
  - engagement model, habits, progress, pattern miner, proactive brain, coach and service
- On-device signals:
  - Health Connect (steps, sleep, weight, exercise)
  - walk, run and ride detection
  - geofenced places
  - UPI and food-delivery notifications
  - calendar busy/free time
  - screen time
- In-app chat with text, voice notes (AAC) and photos. Notifications have one-tap action buttons.
- Today tab with status, observed patterns and the coach decision log.
- Setup tab with the API key, permissions, OnePlus/Oppo battery steps, places, coaching mode and pause.
- New candidate: arrival at a place with a rule or lapse pattern.
- Unit tests (Robolectric) and a live Gemini test gated on `GEMINI_API_KEY`.

## 0.3.0 — 2026-10-06 (live testing with real Gemini)

### Added
- Gemini provider built for the free tier:
  - routing by purpose (Lite pool for text, Flash pool for media and the weekly review)
  - per-model RPM/RPD pacing persisted to disk
  - daily-quota detection
  - 20 s call timeout with a 5-minute cooldown
  - fallback across models
  - quota usage shown in `/status`
- Grounded extraction: quotes required and food must be mentioned in the message; flat schema for goals, settings and usual meals; few-shot examples; trigger and activity-kind fallbacks.
- Focused follow-up extraction for answers to the coach's own questions.
- Budget reservation for high-value messages; onboarding topics capped at 2 asks in 14 days.
- A 7-day summary in every reply context.

### Fixed
- BUG-042 … BUG-049 (found by live runs).

## 0.2.0 — 2026-10-06

Redesign after an honest gap analysis (GAP_ANALYSIS.md).

### Added
- Proactive brain: candidate intents that need no user setup, deterministic gating, LLM choose-or-stay-silent-and-write, template fallback.
- Engagement state machine (engaged/drifting/silent/dormant) controlling budget, allowed intents, recap cadence and retirement of dead message types; one-tap re-entry.
- Evening recap with declared/learned usual meals ("All usual", "Usual + outside snack", "Off-plan day"); inferred items confirmed in the same message.
- Passive food signals: `/ingest/notification` for Swiggy/Zomato/Zepto/Blinkit orders and small UPI payments (apps + bank SMS); payee learning; labelled auto-logging.
- Calendar ICS ingest (times only): long days, free slots.
- Pattern miner with evidence thresholds: weekday situations (from mentions and from payments), situation → off-plan eating, rarely logged meals; user can reject patterns.
- Pre-emptive predicted-situation messages and rule proposals that become precommitments in one tap.
- Inactivity nudges from real Health Connect steps; walks auto-completed from exercise sessions.
- Conversation: history queries answered by code, food corrections, commitment edits, usual meals, inferred confirmations, pattern feedback; late meals attributed to meal time.
- Honest daily intake estimate including assumed usual meals (computed, never stored).
- Telegram quick-log reply keyboard; Gemini `thinking_level` config.
- `tools/simulate.py` (30-day lazy user, offline/live) and `tools/live_check.py`.
- 158 tests.

### Fixed
- BUG-018 … BUG-024 (found by simulation), BUG-025 … BUG-041 (second independent code review).

## 0.1.0 — 2026-10-05

### Added
- Research brief and architecture decisions (Telegram, Health Connect Webhook bridge, SQLite, Gemini default with provider abstraction).
- LLM providers: Gemini (text/image/audio + JSON schema), Claude (forced-tool JSON), OpenAI, OpenRouter, Fake; optional fallback provider.
- SQLite schema with migrations: profile, facts (with supersession), messages + FTS5, food/activity/body/context events, Health Connect records, commitments, commitment log, interventions, slot stats, decision log.
- Natural-conversation extraction (Hinglish text, voice, photo) with deterministic validation.
- Nutrition estimator: seed Indian food table with household units, kcal/protein ranges, flagged LLM fallback.
- Commitments (if-then, precommitment, habit, boundary) with fallback-version ladders and context triggers.
- Proactive planner: budget, quiet hours, pause, escalation ladder (levels 0–5) capped by coaching mode, back-off, "Later" follow-up, skip reasons, Thompson-sampling slot learner, weekly review.
- Strong accountability mode behind an explicit confirmation tap.
- Safety/tone guard on all outgoing messages.
- Progress engine: EWMA trend weight, weekly rate, data-gated target changes.
- Health Connect ingest API (shared-secret header, idempotent upserts).
- Telegram channel (private chat with the allowed user only), terminal chat (`python -m coach.chat`).
- 86 tests.

### Fixed
- BUG-001 … BUG-017 (see BUGS.md), including 13 issues found in an independent code review.
