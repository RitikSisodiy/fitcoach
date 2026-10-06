# Gap Analysis — v0.1 vs the product vision

Written 2026-10-06. This review assumes v0.1 may be wrong. It does not defend it.

## Verdict

v0.1 is a **reactive logger with a reminder scheduler**. Its plumbing is reasonable: storage, validation, safety, provider abstraction, and tests. But it fails the core product promise for the user we are actually designing for. A lazy user who never sets commitments, ignores messages, and forgets to log gets **almost nothing**: no proactive coaching, no tracking beyond what they volunteer, no learned patterns, and no adaptation beyond a slot learner that rarely gets data.

Also: **nothing has run against a real LLM or real Telegram.** All 86 tests use a scripted fake. Extraction quality, prompt behaviour, and Hinglish handling are unverified.

## 30-day walkthrough: lazy user on v0.1

| Day(s) | What the user does | What v0.1 does | Gap |
|---|---|---|---|
| 1 | `/start`, says "weight kam karna hai" | Replies, maybe stores a fact | **No onboarding.** No goal, baseline, routine, or first commitment is captured. |
| 1–30 | Never states a rule with a time window | **The proactive engine never fires.** Nudges exist only for commitments that have windows. | The coach is silent unless the user configures it. Zero autonomy. |
| 2–30 | Forgets food logging most days | Food totals stay "unknown". Nothing asks, infers, or offers defaults. | No low-effort capture: no recap, no habitual-meal defaults, no inferred orders/payments. |
| Tue/Sat | Goes to chess, buys sev parmal (mentions it ~half the time) | Logs it when mentioned. Lapse protocol fires. | **No pattern learning.** It never learns chess → snack, never predicts chess days, never intervenes *before* chess unless the user both created a precommitment and announced the trip. |
| Street food | Eats it, rarely mentions it | Nothing | No passive signal (UPI/food-delivery notifications) and no recap question. |
| Long work days | Sits 11 h | Nothing (HC steps stored but unused for coaching) | No inactivity or busy-day detection; no micro-actions in free slots; no calendar. |
| Skips workouts | — | The ladder works only if a scheduled habit exists | Same root cause: depends on user setup. |
| Ignores messages | — | The slot learner marks nudges ignored; back-off after 4 ignored | No engagement model: the budget doesn't shrink, there's no re-entry flow, and message types are never retired. Silence after day ~10 is the strongest dropout predictor and v0.1 does nothing about it. |
| Talks randomly ("kal kitna khaya tha?") | Reply LLM sees today + last 10 messages only | **It cannot answer questions about history.** The LLM has no way to query data. |
| "7 baje nahi, 8 baje remind karna" | Extraction has no commitment-edit action | **Commitments can't be changed by conversation.** |
| "Nahi 2 roti thi, 3 nahi" | Creates *another* food entry | **No corrections**, so totals inflate. |
| Week 1–4 | — | Weekly review needs data; the "experiment" is only text | No experiments are executed or evaluated; no strategy actually changes. |

## Gaps by vision area

### 1. Automatic tracking
| Need | v0.1 | Missing |
|---|---|---|
| Steps/sleep/weight | Ingest endpoint exists, untested on a device | Using the data: auto-completing walks, inactivity detection, stale-sync alerts |
| Food | Manual mention only | Recall-lite evening recap with habitual-meal defaults; food-delivery notifications; small-UPI snack inference with one-tap confirm |
| Routine/context | Only if mentioned | Weekday recurrence mining (chess days); calendar-based busy-day detection |

### 2. Autonomous behaviour
- The proactive engine depends entirely on user-configured windowed commitments. **This is the biggest gap.**
- No decision points exist independent of commitments: morning plan, pre-context, inactivity, evening recap, re-engagement, onboarding questions.
- The coach never proposes commitments from observed patterns (for example, "chess days → want a default?").

### 3. Minimum user effort
- No one-tap capture of the common case ("usual lunch").
- No inferred events awaiting a single tap.
- Onboarding is absent, so the user would have to know the rule syntax to get value.

### 4. Real LLM reasoning
- The LLM only writes words; every decision was hard-coded rules plus guidance lines. That is safe but rigid, and the vision explicitly asks for generalisation ("no fake AI").
- The LLM cannot query data (history questions, trends), so its "reasoning" is limited to the current day.
- Missing per the research: a **hybrid**. Code computes facts and enforces limits; the LLM chooses among candidate interventions, or chooses none, and writes them. Numbers come only from code (PHIA lesson).

### 5. Behavioural adaptation
| Exists | Missing |
|---|---|
| Slot learner, ladder, back-off | Engagement state (engaged → drifting → silent) controlling budget and message type |
| | Per-intent response tracking with retirement of dead message types |
| | Pattern memory with evidence counts (support/contradict, distinct weeks) |
| | Patterns driving predictions and pre-emptive interventions |
| | Experiments with an evaluation |

### 6. Real-world journeys
- No corrections, no commitment edits, no history questions.
- No handling of ordered food or of long silent periods.
- Nothing has been validated live.

## What v0.1 got right (kept)

- Storage, idempotency, transactions, and safety guard.
- Provider abstraction; ranges instead of point calories.
- Strong-mode consent; ladder concepts; no-data ≠ missed; excused skips.
- Telegram as the channel, and the decision against a custom Android app — still correct after re-research.

## Redesign summary (details in ARCHITECTURE.md v0.2, DECISIONS D-012…D-020)

1. **Hybrid proactive brain.** Code generates candidate intents every tick and gates them (quiet hours, pause, engagement-adaptive budget, cooldowns, retired types). The LLM picks one candidate or none and writes it; the result passes the safety guard.
2. **Candidate intents independent of user setup:**
   - evening recap
   - morning plan
   - predicted context (chess day)
   - inactivity
   - pending inferred events
   - commitment due
   - pattern → propose a precommitment
   - onboarding question
   - re-engagement
   - weekly review
3. **Recall-lite recap** with habitual-meal defaults learned from history. Sources are tagged `user_entered` / `default_confirmed` / `inferred_confirmed` / `missing`.
4. **Passive signals:**
   - notification/SMS webhook (food delivery, small UPI payments) → inferred events → one-tap confirmation
   - calendar ICS → busy days and free slots
   - HC exercise → auto-complete walks
5. **Pattern miner** with deterministic evidence. Weekday recurrence becomes predictions; context-to-lapse links become patterns, gated at ≥ 3 support across ≥ 2 weeks with a ≥ 3:1 ratio. The LLM only phrases them.
6. **Engagement model** drives the budget: engaged 3/day → drifting 2 → silent 1 → every 2 days → weekly re-entry. Per-intent response rates; intents with zero responses after 6 sends are retired for 7 days.
7. **Conversation upgrades:**
   - a data-query round (the LLM asks for history and code answers)
   - food corrections
   - commitment edits
   - confirming inferred events by text
8. **Live validation:**
   - a live smoke test (schema acceptance, Hinglish extraction set, voice)
   - a 30-day lazy-persona simulator with an LLM-played user and simulated clock, reporting tracking coverage, learned patterns, adaptation, message volume and tone violations
