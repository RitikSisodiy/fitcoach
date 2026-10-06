# 30-Day Lazy-User Simulation

`tools/simulate.py` plays 30 days of a lazy, inconsistent user against the full system. It drives the real `CoachService`, the scheduler tick every 15 minutes, the ingest paths, and the Telegram button handlers. Results are scored against ground truth.

## The simulated user

| Behaviour | Model |
|---|---|
| Work | WFH; ~50 % of weekdays are long meeting days (calendar ICS) |
| Chess | Every Tue/Sat ~18:00; buys street food there 70 % of the time (UPI payment notification) |
| Other street food | ~25 % of other days, 17:30 (UPI) |
| Orders | ~28 % of dinners via Swiggy (notification) |
| Logging | Mentions home meals 22 %, outside food 35 %, decaying over the month |
| Chess mentions | ~50 % of chess days |
| Reactions to bot | ~40 %, halved after 2 messages that day, decaying over the month |
| Random chatter | ~30 % of days: history questions, complaints |
| Walks | Commits on day 3 to "30 min, else 10 min"; actually walks ~1 day in 3 (Health Connect exercise) |
| Phone | Hourly Health Connect steps; weight on ~30 % of days |

## Modes

| Mode | Coach LLM | User messages | Purpose |
|---|---|---|---|
| `offline` | Scripted. Extraction returns the ground truth for each message; "decide" picks the top candidate | Templates | Validates plumbing, policy, adaptation and passive inference, independent of LLM quality |
| `live` | Real provider from `.env` | Written by the real LLM role-playing the persona | The real test: extraction quality, Hinglish understanding, choice and wording of proactive messages |

## Results — offline mode, v0.2.2 (after the second code review), 3 seeds

The offline run measures what the **system design** achieves with perfect extraction. It is an upper bound on understanding and says nothing about LLM quality.

| Metric | Seed 11 | Seed 23 | Seed 42 |
|---|---|---|---|
| Main meals with any record (of 90) | 37 % | 31 % | 30 % |
| … user-typed / one-tap default / inferred order | 22 / 3 / 8 | 17 / 3 / 8 | 21 / 3 / 3 |
| **Daily intake estimate error (MAPE), incl. assumed usual meals** | 7 % | 9 % | 5 % |
| Intake estimate bias | +5 % | +8 % | 0 % |
| Street-food days captured | 11/11 | 10/11 | 9/12 |
| Walk days recorded (truth) | 8 (10) | 5 (10) | 7 (8) |
| Steps days with data | 30/30 | 30/30 | 30/30 |
| Chess routine learned passively | Tue + Sat | Tue + Sat (+ chess → off-plan link) | Sat |
| Pre-emptive messages before chess | 1 | 2 | 1 |
| Proactive messages/day (week 1 → 4) | 2.7 → 2.7 | 2.4 → 2.1 | 2.3 → 2.4 |
| Response rate (week 1 → 4) | 68 % → 32 % | 53 % → 27 % | 63 % → 35 % |
| Quiet-hour messages / tone violations | 0 / 0 | 0 / 0 | 0 / 0 |
| LLM calls (30 days) | 181 | 158 | 170 |

Reference: v0.1 under the same user would have recorded only what the user typed (~20 % of meals), had no intake estimate, captured no street food the user didn't mention, learned nothing, and sent only walk reminders.

## What the simulation exposed (and what changed)

| Finding (first runs) | Fix |
|---|---|
| Recap was ignored 6 times in a row → retired → **tracking stopped for a week** | Recap is never retired. It becomes every 2nd, then every 3rd day (BUG-018) |
| No usual meals known for a lazy user (needs ≥ 3 identical logs) → "All usual" button never offered | Onboarding asks once for usual meals; declared meals act as defaults until observed habits exist (BUG-019) |
| Unconfirmed orders and payments expired → information lost | Orders become `inferred_unconfirmed` meals after 18 h. Payees confirmed as food are auto-logged. Recurring snack-stop payees are auto-logged unconfirmed (BUG-020) |
| Chess learned only from mentions → pattern never reached threshold | Passive payee × weekday pattern from payments (BUG-021) |
| Morning plan sent daily just to list commitments (noise) | Sent only when there is something to plan: predicted situation, long day, or a slip yesterday (BUG-022) |
| "Accept rule" button with no rule text created a meaningless commitment | Accept button only when the LLM wrote a concrete rule (BUG-023) |
| Simulator RNG coupling produced fake "Wednesday" patterns | Per-day RNG; patterns now match the truth (Tue/Sat) |

## Honest limits seen in simulation

- **Meal capture stays ~30–36 %** because the user ignores most prompts. The usable number is the *intake estimate* (recorded + assumed usual), which is honest about assumptions and ~13–15 % off per day, biased high.
- **Pattern learning needs ~3 weeks** (≥ 3 distinct weeks by design, to avoid false patterns). Week 1–2 coaching is generic.
- **Rule proposals are rarely accepted** by a user who rarely taps. That is realistic: the coach cannot force commitments.
- **Live mode has not been run.** It needs network access to the Gemini and Telegram APIs from the environment that runs it (see TASKS T-102/T-103).

---

## Live results — real Gemini on the user's laptop (2026-10-06)

The coach ran on the free tier: Flash-Lite for text after D-021, with the Flash pool as fallback. The simulated user was scripted, to save quota. The days were simulated, but every coach decision and every word was real Gemini output.

### Extraction check (`tools/live_check.py llm`, 20 Hinglish cases)

| Run | Setup | Score | Notes |
|---|---|---|---|
| 1 | Flash 3.8 only, no retries | **0/20** | 503 "high demand", then 429 (5 RPM / 20 per day) → BUG-042/043 |
| 2 | Model fallback | 14/20 | Invented food from a pending payment ("ok" → chaat), goal stored as a fact, a roti skipped |
| 3 | + grounding, prompt fixes | 16/20 | Mode change invented from "kal kitna khaya?" (dropped by grounding) |
| 4 | + flat schema, trigger fallback | 16/20 | Varies by model and run; misses are now *omissions*, not inventions |

Decide-schema and Hinglish lapse reply passed safety in every run.

### 7-day live simulation

| Metric | Run 1 (seed 11, before D-023/24/25) | Run 2 (seed 23, after) |
|---|---|---|
| Main meals with any record | 33 % | 48 % |
| Street-food days captured | 2/2 | 2/3 |
| Commitment (walk) nudges | **0** (budget used by earlier messages) | 4 |
| Onboarding questions | 7 (same question repeated) | 4 (rotating topics) |
| Response rate to proactive messages | 11/18 | 9/17 |
| LLM chose silence | — | yes (recap the user kept ignoring) |
| Tone/safety violations, quiet-hour messages | 0, 0 | 0, 0 |

**What real Gemini did well:**
- Used the calendar free slot ("13:00–13:45 wale break mein lunch").
- Offered the smaller version on a busy day ("30 min nahi toh 10 min").
- Applied the lapse protocol after sev parmal.
- Answered "weight ka kya scene hai" from the 7-day summary with the real numbers.
- Wrote a weekly review that named the actual failure mode ("19:00 slot kept hitting work overruns").

**Found and fixed from the live runs:** BUG-042 … BUG-049, D-021 … D-026. Also, commitment `activity_kind` is now inferred when the model omits it, so walks recorded by the phone can complete the commitment.

**Still open:**
- **OPEN-050.** Usual-meal answers and payment confirmations are sometimes missed. The focused extraction (D-023) mitigates this, but it wasn't exercised in run 2 because the simulated user never answered that question.
- **Flash pool reliability.** The Flash models were frequently overloaded (503/504) during testing.
- **Telegram end-to-end.** Not yet run; it needs a valid bot token.
