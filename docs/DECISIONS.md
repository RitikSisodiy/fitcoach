# Decisions

Format: context → decision → consequences. Newest last. Evidence lives in `RESEARCH.md`.

---

### D-001 — Telegram as the only channel for now (2026-10-05)
**Context.** The coach must message first, at arbitrary times, with one-tap actions, voice and photos. WhatsApp Business API only allows free-form messages within 24 h of the user's last message; outside that, only pre-approved templates, plus per-message cost and Meta's 2026 restrictions on AI chatbots. A native app costs weeks before any behaviour change is tested.
**Decision.** Telegram bot, long polling. Channel code is isolated in `channels/`; the service layer is channel-agnostic.
**Consequences.** No public URL needed for chat. If the user ignores Telegram in practice, a second channel can be added without touching the engine.

### D-002 — No custom Android app in v0.1 (2026-10-05)
**Context.** Health Connect has no cloud API, so data must leave via an app on the phone. A maintained open-source app (Health Connect Webhook) already does periodic background sync with custom auth headers.
**Decision.** Use that app → `POST /ingest/health-connect` with `x-api-key`. Build a Kotlin companion only if it proves unreliable or a needed signal is missing.
**Consequences.** Zero Android code to maintain now. Payloads have no record ids, so ingest dedupes on normalised (type, start, end) keys. Background reliability depends on the user disabling battery optimisation for that app.

### D-003 — Conversation is the primary context sensor; no location tracking (2026-10-05)
**Context.** Background location needs heavy permissions and battery, and has little value for a WFH user. The user already narrates context ("aaj chess ja raha hu").
**Decision.** Context comes from conversation (LLM-extracted tags) plus Health Connect. Location, activity-recognition transitions and screen time are deferred.
**Consequences.** Contextual interventions fire only when the user mentions the situation. Revisit if analysis shows repeated unannounced lapses at a known place.

### D-004 — SQLite with structured tables, FTS5, no vector DB (2026-10-05)
**Context.** The data is relational and small, and needs aggregation. Benchmarks show context management matters more than the retrieval mechanism.
**Decision.** SQLite (WAL) is the single source of truth. A `facts` table carries source, confidence, timestamps and supersession. FTS5 indexes messages.
**Consequences.** One file to back up. `sqlite-vec` can be added later if semantic recall is ever needed.

### D-005 — The LLM proposes, code disposes (2026-10-05)
**Decision.**
- The LLM extracts candidates (validated and clamped in code) and writes words.
- Code decides persistence, nutrition numbers, whether and when to message, the escalation level, safety, and any target changes.
**Consequences.** Behaviour is testable without an LLM (`FakeProvider`). Prompts carry deterministic *guidance* lines so the LLM knows what policy decided.

### D-006 — Nutrition as ranges from a food table; LLM estimates only as flagged fallback (2026-10-05)
**Context.** Photo calorie estimation has 40–70 % error, and hidden oil makes Indian dishes harder still.
**Decision.**
- Use household units and kcal/protein *ranges*, widened for unit conversion and missing quantities.
- An unknown dish falls back to the LLM's own range, widened a further 20 % and labelled `llm_estimate`.
- No estimate means `unknown`, never a guess.
**Consequences.** Totals are honest but wide. Replace the seed table with an INDB import (T-301).

### D-007 — Gemini default, provider-agnostic interface (2026-10-05)
**Context.** Gemini is the only major API taking text, image and OGG voice in one call, and it is cheap.
**Decision.** Gemini is the default; Claude, OpenAI and OpenRouter are interchangeable via `LLM_PROVIDER`/`LLM_MODEL`, with an optional fallback provider. **Model ids are configuration only**; none are hardcoded.
**Consequences.** On providers without audio, voice notes get a polite "please type it" reply; the fallback provider is tried first if it supports audio.

### D-008 — Behavioural policy (2026-10-05)
**Decision.**
- **Daily budget:** gentle 1, normal 3, accountability/strong 4 at most.
- **Minimum gap** between proactive messages.
- **Quiet hours** apply.
- **One scheduled nudge** per commitment per day, plus one follow-up after "Later".
- **Escalation ladder:** driven by consecutive misses and 7-day misses, and capped by mode.
- **Smaller-version ladder:** fallback versions shrink with misses.
- **Back-off:** after 4 ignored nudges, nudge only every other day and log a strategy review.
- **Thompson-sampling slot learner:** works on a 30-minute grid, with a forgetting factor.
- **Missing data:** days with no data and no nudge are *unknown*, not misses.
- **"Couldn't today":** skips with this reason are excused and never escalate.
**Consequences.** See `BEHAVIOR.md`. All of this is unit-tested.

### D-009 — Strong accountability needs an explicit confirmation tap (2026-10-05)
**Decision.** Neither the LLM nor a text message can turn on strong mode. Only the confirmation button can, and leaving strong mode revokes the authorisation. A commitment gets `strong` enforcement only while strong mode is authorised.

### D-010 — Safety guard in code on every outgoing message (2026-10-05)
**Decision.** A regex guard runs on every outgoing message and catches:
- shame language
- dehydration, starvation, laxatives/diuretics, compensatory meal skipping
- medication changes
- daily targets below 1200 kcal

A flagged message gets one rewrite with feedback, then a safe template. LLM outages produce deterministic templates for nudges and defer the weekly review.

### D-011 — Single process, single user (2026-10-05)
**Decision.** Telegram polling, planner job and ingest API run in one asyncio process sharing one SQLite connection. Transactions never span an `await`.
**Consequences.** Simple to run on a home server or small VPS. Multi-user would need per-user scoping, which is out of scope.

---

## v0.2 redesign (2026-10-06): see GAP_ANALYSIS.md

### D-012 — Hybrid proactive brain replaces the commitment-only scheduler
**Context.** v0.1 messaged only for user-configured windowed commitments, so a lazy user got a silent coach. Research favours a hybrid split: rules gate, the LLM chooses and writes.
**Decision.**
- Every tick, deterministic generators propose candidate intents that need no setup: commitment due, recap, morning plan, predicted situation, inactivity, re-engagement, rule proposal, onboarding and stale sync.
- Code gates them (pause, quiet hours, engagement budget, minimum gap, once per intent per day, retired types).
- The LLM sees a digest plus ≤ 3 candidates and picks one or chooses silence, and writes the text. A silence decision suppresses those candidates for a 2-hour bucket.
- LLM failure → top candidate with a template.
**Consequences.** One LLM call per tick at most, and only when candidates survive the gate (~5–7/day). Fully logged in `coach_decisions`.

### D-013 — Engagement state machine controls volume
**Decision.** The state machine has four states:

| State | Condition | Messages |
|---|---|---|
| engaged | message within 1.5 d or ≥ 40 % response | mode budget |
| drifting | silent ≤ 4 d | ≤ 2/day |
| silent | 4–10 d | 1/day; only recap, re-engage, commitment, predicted |
| dormant | > 10 d | re-engagement only, every 3 days, then weekly after 21 days |

Message types with 0 responses in their last 6 sends are retired for 7 days. Re-engagement, weekly review and recap are never retired; the recap gets rarer instead.

### D-014 — Evening recap with usual-meal defaults
**Decision.**
- **Source of defaults:** declared usual meals (asked once in onboarding) until observed habits exist (≥ 3 identical logs in 28 days, weekday/weekend aware).
- **Buttons:** All usual / Usual + outside snack / Off-plan day / Skip, plus inferred items to confirm.
- **Storage:** defaults are stored as `default_confirmed` with 0.7× confidence (acquiescence bias).

### D-015 — Passive food signals through a notification/SMS forwarder
**Decision.**
- `POST /ingest/notification` receives forwarded notifications/SMS, with package filtering on the phone. Food-delivery orders and small UPI payments (≤ ₹250) become `inferred_events`, never direct logs.
- **Confirmation:** in the recap or in conversation. Payee labels are learned.
- **Auto-logging, labelled:**
  - payments to a known food payee → immediately
  - orders after 18 h → `inferred_unconfirmed`
  - payments to recurring snack-stop payees → `inferred_unconfirmed`

**Consequences.**
- Requires SmsForwarder/MacroDroid on the phone, and "Allow restricted settings" on Android 15+.
- Small payments are probabilistic; a payee marked "not food" is ignored forever.

### D-016 — Pattern mining with evidence thresholds
**Decision.** Patterns mined deterministically once a day:
- `weekday_context` — from mentions
- `weekday_payee` — from payments
- `context_lapse`
- `meal_gap`

**Active** requires support ≥ 3 across ≥ 3 distinct weeks (weekday patterns) or ≥ 2 weeks (lapse patterns), with Wilson lower bound ≥ 0.35. A user rejection is permanent. Active patterns drive predicted-situation messages and rule proposals.

### D-017 — Honest intake estimate with assumed usual meals
**Decision.** Days are rarely fully logged. Intake is estimated as recorded + the usual meal for each unrecorded main meal. This is **computed, never stored**, and always reported with its assumption.
**Consequences.** It is usable for weekly trends; the simulation showed ~13–15 % daily error, biased high. It is not used for single-day judgements.

### D-018 — Conversation can query history and edit state
**Decision.** Extraction can return:
- `data_needed` — code answers it, and the numbers go into the reply prompt
- `food_corrections` — replace instead of duplicating
- `commitment_changes`
- `inferred_confirmations`
- `profile_updates` — including usual meals
- `pattern_feedback`

### D-019 — Phone exercise sessions complete walk/workout commitments
**Decision.** A Health Connect exercise session in the commitment's day marks it `done` (≥ full minutes) or `smaller` (≥ smallest version), source `observed`. No nudge is sent.

### D-020 — Live validation tooling
**Decision.** Two tools:
- `tools/live_check.py`: real-provider extraction accuracy on 20 Hinglish cases, decide-schema acceptance, reply safety, Telegram delivery.
- `tools/simulate.py --mode live`: 30 days with the real LLM playing both coach and user.

**Blocked:** the cloud sandbox's egress policy blocks `generativelanguage.googleapis.com` and `api.telegram.org`.

---

## v0.3 — decisions from live testing (2026-10-06)

### D-021 — Design for the free-tier Gemini quota
**Context.** The AI Studio free tier gives 3.x Flash models 5 RPM and **20 requests/day each**, and Flash-Lite models 15 RPM and 500/day each. Flash models also returned 503 "high demand" and 504 often during testing.
**Decision (revised after measuring).**
- Purpose routing:
  - **Text work** (extraction, replies, decisions, nudges) goes to Flash-Lite first: 3.5-lite → 3.1-lite, ~1000/day. Measured at ~6 s per call, it scored the same as Flash on the extraction check (16/20 both).
  - **Photos, voice and the weekly review** go to the Flash pool first: 3.8 → 3.7 → 3.6 → 3.5, ~80/day.
  - Each pool falls back to the other.
- Every call has a 20 s timeout. A model that times out or returns 504 is skipped for 5 minutes.
- Local pacing is per model (RPM and RPD). Counts are persisted to `data/gemini_usage.json` and reset at Pacific midnight.
- A server 429 skips the model for ≥ 30 s; if the quotaId is `PerDay`, it is skipped for the rest of the day.
- `GEMINI_TIER=paid` removes local limits.
- `/status` shows today's usage.

**Consequences.** ~2 Lite calls per user message plus ~5–8 decisions/day, about 50/day against a 1000/day Lite budget. Replies stay fast while the Flash pool is overloaded.

### D-022 — Extraction must be grounded in the user's words
**Context.** Live runs invented food from context ("ok" → chaat from a pending payment) and invented a mode change from a history question.
**Decision.** Food items, commitments, edits, confirmations, settings and goals must carry a `quote` found in the message (≥ 60 % token overlap). Food names, or a known alias such as dahi/curd, must appear in the message. Voice and photo inputs are exempt, since there is no text to check against.

### D-023 — Focused follow-up extraction
**Context.** When the user answered the coach's own question ("usually nashta me poha chai, lunch 3 roti…"), the big schema missed it, and the coach asked the same question 7 times in 7 days.
**Decision.** If an onboarding question was sent in the last 12 h and the general extraction missed the answer, a tiny single-purpose schema runs on the reply (usual meals, goal).

### D-024 — Budget reservation
**Context.** In the live run, morning plans and onboarding questions used the whole daily budget before the 19:00 walk window, so 0 walk nudges were sent in 4 days.
**Decision.** Candidates with priority < 70 may only use budget left after reserving one slot per commitment window still ahead today, plus one for the recap.

### D-025 — Onboarding never nags
**Decision.** Each onboarding topic is asked at most twice in 14 days, never on consecutive days. Topics rotate.

### D-026 — The reply always gets a 7-day summary
**Decision.** Every reply context includes a deterministic 7-day digest: food, kcal recorded, missing meals, steps, commitment outcomes and weight. History questions within a week are answered without depending on extraction (`data_needed` is only for longer ranges).

### D-027 — Native Android app, no server
**Context.** The user asked for automatic detection without a server. The v0.3 design relied on forwarder apps (SmsForwarder, Health Connect Webhook) that the user never set up, so nothing was detected automatically.
**Decision.** One Kotlin app holds the whole engine and reads phone signals directly: Health Connect, Activity Recognition transitions, geofences for saved places, a NotificationListenerService for UPI and food delivery apps, CalendarContract and UsageStats. Data lives in the app's SQLite database. The only network call is Gemini, made with the user's own key.
**Consequences.** No hosting, no forwarders and no Telegram. The Python code stays as the reference engine and the simulator, and prompts are exported from it (`tools/export_prompts.py`) so both share one source of truth.

### D-028 — Phone signals feed the same engine as chat
**Decision.** Signals reuse the existing tables instead of adding new paths:
- A place arrival is a `context_event` (`source='geofence'`), so rule reminders, weekday patterns and lapse patterns all work on arrivals without the user typing.
- Walks and runs from Activity Recognition, a gym stay of 20 minutes or more, and Health Connect sessions are `exercise` health records, so they auto-complete walk and workout commitments.
- UPI and delivery notifications are `inferred_events`.
- An arrival at a place with a rule or lapse pattern creates an `arrived:<tag>` candidate (priority 86), and the receiver runs a tick immediately.

### D-029 — Ticks on WorkManager, every 15 minutes
**Decision.** A unique periodic worker runs every 15 minutes (the platform minimum). Each run syncs the sensors, then runs `tick()`. Geofence arrivals also trigger an immediate tick. The app shows a ColorOS / OxygenOS checklist in Setup (battery optimisation, auto-launch, recents lock), because these ROMs kill background work.
**Consequences.** A tick can be late by several minutes in Doze. The 30-minute slot learner and the arrival path tolerate this.

### D-030 — Sleep and screen time are context, not targets
**Decision.** Short sleep (< 6 h, from Health Connect) and late-night phone use (≥ 45 min after 23:00) are added to the morning-plan facts and the reply context only. The coach never sets sleep or screen-time targets on its own.

### D-031 — Releases from CI, updates from GitHub
**Context.** The user wants every push to master to produce an installable APK, and the app to notice and install updates itself.
**Decision.**
- **Repository:** public GitHub repo `RitikSisodiy/fitcoach`. Being public, the app reads releases without a token. Secrets stay out of git (`.env`, the database, logs and keystores are ignored).
- **CI:** `.github/workflows/android-release.yml` runs on pushes to master that touch `android/**`. It runs the unit tests, builds the R8 release and publishes the GitHub Release `vMAJOR.MINOR.RUN`.
- **Versioning:**
  - `MAJOR.MINOR` lives in `android/version.properties`, and PATCH is the CI run number.
  - `versionCode = MAJOR*1e6 + MINOR*1e4 + PATCH`, so it always increases.
  - Local builds are `x.y.0`.
- **Signing:** CI signs with a fixed release key held in repository secrets. The backup is on the laptop at `~/.fitcoach-signing/`. Losing the key means users must reinstall.
- **In-app update:**
  - `update/Updater.kt` checks `releases/latest` every 3 h from the tick worker, and on app open (throttled to 10 minutes).
  - It notifies once per new version and shows an Update banner.
  - It downloads the APK and installs it through `PackageInstaller`. The user confirms the system dialog and allows "Install unknown apps" once.
**Consequences.** Docs-only pushes don't create releases. Installs signed with the old debug key (1.0.0) must be uninstalled once before the first CI release.

### D-032 — The LLM decides when and what; code only limits (v2 agent loop)
**Context.** The v1.1 audit (`docs/AUDIT.md`) found rules plus LLM wording:
- fixed clock windows (a 21:00 recap, a 08:30 morning plan, others);
- template fallbacks sent as coaching;
- about 20 scripted button replies;
- a deterministic escalation ladder.
**Decision.** On each wake-up, the agent (`engine/Agent.kt`) gets one SITUATION built from the database and returns:
- whether to act;
- the message and up to 3 quick replies;
- the channel;
- the expected outcome;
- its **own next check time and reason**.

The agent is woken by significant observations, by its own check time, or by a 4-hour safety net. Code enforces only pause, quiet hours, the daily cap (coaching mode × engagement), the minimum gap, safety and the evaluation quota.

Brain candidates, templates, the ladder, the weekly-review slot and snooze follow-ups are all deleted. When the AI is down the agent stays silent. Replies to the user show a labelled status line.
**Consequences.** Behaviour depends on prompt quality, so it is verified with `LiveAgentTest` (real Gemini, 3 simulated days) and by reading its transcript. The cost is ~10–25 decision calls per day on Flash-Lite.

### D-033 — Telegram on the phone (long polling), one backend
**Decision.**
- **Polling:** a foreground service (`specialUse`) long-polls the user's own bot. Messages, voice notes, photos and quick-reply callbacks go into the same `CoachService.handleMessage` as the app chat.
- **Pairing:** a one-time code the user sends with `/start`.
- **Proactive messages:** go to Telegram when the agent picks that channel.

The retired Python bot is not used.
**Consequences.** Only one device may poll a bot at a time. The polling service needs the battery-optimisation exemption on ColorOS (already part of Setup).

### D-034 — Prompts live in Android assets; Python archived
**Decision.** Prompts and schemas are files in `android/app/src/main/assets/prompts/`. The Python engine moved to `legacy/python/`, unmaintained, because it duplicated all logic and still contained the removed scripted flows.

### D-035 — Memory tiers
**Decision.**
- **Events** stay in their tables.
- **Memory** lives in `facts`:
  - long-term: `valid_until` is null;
  - temporary: `valid_until` is set; the LLM gives `valid_days` and expired rows drop out of retrieval;
  - coach insights: category `coach_insight`, written only by the daily reflection.

Memory items must be grounded in the user's words, like food items. Every reply and decision gets all three tiers. History beyond 7 days is fetched on demand through `data_needed`.

### D-036 — Quick replies instead of buttons
**Decision.** Tappable options are written by the LLM per message. A tap is handled exactly like a typed message, in app chat, notification actions and Telegram inline keyboards. Extraction sees the open message and its options (`OPEN_NUDGES`). No outcome is hard-wired to a button.

### D-037 — Learning from outcomes
**Decision.**
- **Outcome tracking:** each proactive message gets an outcome: answered (with minutes to reply), ignored (no reply within 3 h), achieved or not_achieved (for commitment messages, from the day's commitment log).
- **Statistics:** code computes response rates by 3-hour block and by channel, plus slot-learner estimates for commitments.
- **Reflection:** a daily LLM step rewrites up to 8 coach insights with evidence. Decisions and replies are told to follow them.
