# Tasks

Priority: P0 (blocks real use) · P1 (high value) · P2 (later). Status: TODO · IN PROGRESS · BLOCKED · DONE.

## DONE

- [x] Android + iPhone research (`docs/mobile/`). **Dropped by the user**: the app stays Android-only (D-044).

- [x] v2.3: agent plan (temporary intentions, Up next, mute/pause, re-evaluation) (D-043)

- [x] v2.1: voice calls (Gemini Live), Telegram photo/voice fix, dashboard KPIs and source health, update status UI, generic re-audit (D-038…D-041)

- [x] v2 autonomous agent: audit, agent loop, memory tiers, outcome learning, Telegram, dashboard (D-032…D-037)

- [x] v1.0 Android app (D-027…D-030): engine port, on-device sensors, in-app chat, notifications with buttons, Setup checklist for ColorOS
- [x] Android tests: 11 engine + 9 service (scripted LLM) + 1 live Gemini day, all green on 2026-10-06

| ID | Task | Acceptance criteria | Evidence |
|---|---|---|---|
| T-001 | Research: Android / Health Connect, channels, food, LLMs, memory, behaviour | `RESEARCH.md` with sources and challenged assumptions | docs/RESEARCH.md |
| T-002 | Architecture decision and docs | ARCHITECTURE, DECISIONS, BEHAVIOR, MEMORY written | docs/ |
| T-101 | LLM provider abstraction (Gemini, Claude, OpenAI, OpenRouter, Fake, fallback) | No vendor SDK imported outside `coach/llm`; model and key from env; tests for each mapping | test_llm_providers.py |
| T-104 | SQLite schema, migrations, repository | Idempotent migrations; facts with source/confidence/supersession; FTS | test_db.py |
| T-201 | Natural-conversation extraction (text/voice/photo) with deterministic validation | Hinglish meal → items with ranges; invalid values clamped or dropped | test_journeys.py, test_extractor_validation.py |
| T-202 | Commitments: if-then, precommitment, habit; context triggers | Trigger mention → own-rule reminder once per day | test_journeys.py |
| T-203 | Lapse protocol and all-or-nothing counter | Guidance present; safety guard blocks compensation advice | test_journeys.py |
| T-301a | Seed food table + range estimator | Ranges, unit conversion, LLM fallback flagged | test_nutrition.py |
| T-401a | Health Connect ingest endpoint (HC Webhook format) | Secret required; idempotent; local-day attribution | test_ingest.py |
| T-501 | Proactive planner: gate, ladder, buttons, follow-up, weekly review | All behaviours in BEHAVIOR.md §5 tested | test_planner.py |
| T-502 | Slot learner (Thompson sampling with forgetting) | Prefers the better slot; bounded evidence | test_planner.py |
| T-503 | Safety/tone guard | Shame and unsafe advice blocked; rewrite then fallback | test_progress_and_safety.py, test_journeys.py |
| T-701 | Progress engine: EWMA trend, rate, target-change gate | No trend claims on thin data; spike-robust | test_progress_and_safety.py |
| T-901 | Code review pass and fixes | BUG-002..015 fixed with regression tests | test_regressions.py, BUGS.md |
| T-1101 | Gap analysis vs vision; v0.2 redesign | GAP_ANALYSIS.md, D-012..D-020 | docs/ |
| T-1102 | Proactive brain (candidates, gate, LLM choose/silence/write) | Coach acts with zero user setup; LLM can stay silent; template on outage | test_v2_autonomy.py |
| T-1103 | Engagement state machine and retirement; recap cadence | Silence shrinks volume; re-entry one tap | test_v2_autonomy.py |
| T-204a | Progressive onboarding (one question/day: goal, weight, usual meals, routine, first habit) | Asked only while missing | test_v2_autonomy.py |
| T-205a | Recap with usual-meal defaults (declared, then learned) | One tap fills missing meals | test_v2_autonomy.py |
| T-206a | Passive orders/UPI via notification forwarder; payee learning; labelled auto-logging | Never a silent fact; always a labelled source | test_v2_autonomy.py |
| T-207 | Meal-time attribution | Late report logged at the meal time | test_v2_autonomy.py |
| T-504 | Auto-complete walks from Health Connect | Observed outcome, no nudge | test_v2_autonomy.py |
| T-601 | Pattern miner with evidence thresholds; user rejection | Chess routine learned passively in simulation | test_v2_autonomy.py, SIMULATION.md |
| T-1104 | Conversation: history queries, corrections, commitment edits, pattern feedback | Numbers come from code | test_v2_autonomy.py |
| T-1105 | Calendar ICS ingest (times only) | Long days / free slots used | test_v2_autonomy.py |
| T-1106 | 30-day lazy-user simulator (offline + live) and live check | Report vs ground truth | tools/, SIMULATION.md |

## SUPERSEDED / DONE since v0.3

- T-1107, T-102, T-103: live Gemini and Telegram runs done (SIMULATION.md). Android `LiveGeminiTest` now covers live extraction.
- T-105, T-401, T-1001: replaced by the Android app (D-027). No server, no webhook, no deployment.
- Release build with R8 (3.4 MB APK): done 2026-10-06.

## TODO


- [ ] **P0** On the real phone: allow "full-screen calls" (Setup) and take one real coach call. Check the earpiece/speaker audio and the echo cancellation on ColorOS.
- [ ] **P1** Only one device can poll the Telegram bot: the emulator got 409 Conflict while the phone was polling. Fine in real use; tests must stop the phone's polling or use another bot.
- [ ] **P2** Live session resumption (`goAway`) for calls longer than about 10 min. Calls are short today.

- [ ] **P0** The user runs v2 for a real week (app + Telegram). Review the dashboard decision log and the coach insights. Tune `agent_system.txt` from real misses.
- [x] Quiet hours setting (Setup → Coaching style).
- [ ] P1: Agent repeats near-identical messages on consecutive days. Watch it and, if needed, add "never repeat" evidence to the SITUATION.

- [ ] **P0** The user installs the APK and runs a real week. Watch for ColorOS killing the worker, notification-parser misses on the user's bank SMS format, and geofence reliability.
- [x] Git repo, CI release on push to master, versioning, in-app updates (D-031).
- [ ] P2: Food photo straight from the camera (currently the photo picker).
- [ ] P2: Older Python-only TODOs below need re-scoping for the Android app before starting them.

| ID | P | Task | Depends on | Acceptance criteria |
|---|---|---|---|---|
| T-204 | P1 | (superseded by T-204a; remaining: WOOP-style obstacle question in weekly review) Onboarding conversation: goals, boundaries ("never do"), coaching mode, routine, first 1–2 focus commitments (WOOP-style obstacle) | T-102 | Profile, goals and ≤ 2 focus commitments stored from one guided chat; no forms |
| T-205 | P1 | One-tap food confirmation ("kam tha / zyada tha / theek") that updates personal portion priors | T-102 | Corrections change future estimates for that food |
| T-206 | P1 | Retry extraction for messages that failed (LIM-003) | — | Failed messages re-extracted nightly, once |
| T-301 | P1 | Import INDB (1,014 Indian recipes, CC BY) into the food table with household units | — | Seed CSV replaced or augmented; matching quality ≥ seed on a test set |
| T-302 | P2 | Barcode lookup (Open Food Facts) from photos | T-102 | Packaged item photo → product nutrition with user confirmation |
| T-402 | P1 | Single data origin / daily aggregates for steps (LIM-001) | T-401 | No double counting with phone + watch |
| T-403 | P1 | Stale-sync detection message ("Health Connect hasn't synced in 24 h") | T-401 | At most one alert per day; counts toward the budget |
| T-505 | P2 | Cross-midnight window handling (LIM-002) | — | Validator rejects them, or the day is anchored correctly |
| T-602 | P2 | Style/framing bandit (direct vs question vs tiny-step) | T-502 | Per-style success tracked; exploration floor kept |
| T-603 | P1 | Structured experiments ("next 5 days: walk right after lunch") with automatic evaluation | T-601 | Experiment record with hypothesis, period, measured result; logged in BEHAVIOR.md §10 |
| T-702 | P2 | Waist/weight check-in prompts tied to trend data quality | — | Prompts only when the trend is unreliable from missing weigh-ins |
| T-801 | P1 | `/export` and `/forget` (data export and deletion) | — | JSON export; deletion of selected categories |
| T-802 | P2 | Encryption at rest for the DB (SQLCipher) or disk-level encryption guidance | T-105 | Documented and enabled |
| T-803 | P2 | Rate limiting and payload size cap on the ingest endpoint | — | Oversized or flooding requests rejected |
| T-1001 | P2 | Kotlin companion app (only if the HC Webhook app proves insufficient) | T-401 evaluation | Decision recorded in DECISIONS.md first |

## New TODO from v0.2

| ID | P | Task | Acceptance criteria |
|---|---|---|---|
| T-1201 | P1 | Order *items* from Swiggy/Zomato emails (Gmail filter → IMAP poll → LLM parse) | An order becomes item-level food, not a generic "restaurant meal" |
| T-1202 | P1 | Payee labelling question in the recap ("Sharma Chaat Corner — food stall?") once per new frequent payee | Unknown frequent payees get labelled within a week |
| T-1203 | P1 | Show the intake estimate (with assumptions) in the weekly review and `/status` | Users see "~2,100 kcal/day avg (40 % of meals assumed usual)" |
| T-1204 | P2 | Style bandit on proactive wording (direct / question / tiny step) per intent | Response rate per style tracked; exploration kept |
| T-1205 | P2 | Pinned "today" message edited in place (one place to look; no new notifications) | Updated after each log |
