# Audit: v1.1 vs. the autonomous-agent vision (2026-10-07)

Honest findings from reading the code paths, the prompts, the DB and the tests. Each finding names the code, the problem, and what v2 does instead.

## Verdict
v1.1 was **"rules + LLM wording" with partial fake-AI paths**:
- Deterministic generators decided what *could* be said and *when*: each candidate type had a fixed clock window.
- The LLM only picked one of those candidates (or silence) and phrased it.
- When the LLM failed, scripted templates were sent as if they were coaching.
- Button flows replied with canned coaching text.
- Real data capture from sensors and chat was solid. The proactive behaviour was not autonomous.

## Findings

| # | Where | Problem | Class |
|---|---|---|---|
| A1 | `Brain.recap` | The evening recap fires only in `recap_time` (default **21:00**) to +90 min, every day. | fixed schedule disguised as intelligence |
| A2 | `Brain.morningPlan` / `inactivity` / `reengage` / `onboarding` / `proposeCommitment` / `staleSync` | Hardcoded clock windows: 08:30–11:00, 15:00–19:30, 10:00–20:00, 10:00–18:00, 10:00–19:00. | fixed schedule |
| A3 | `Brain.decide` | The LLM may only choose among pre-approved candidates. It cannot decide that something else matters, or when to look again. | rules + LLM wording |
| A4 | `Brain` `fallbackText`, `decide()` on `LlmException` or safety failure | Templates are sent as proactive coaching when the LLM is down or unsafe ("Quick recap: …", "Long sitting day - 5 minute walk now?", "Quick check-in - how's it going?"). | fake AI path |
| A5 | `CoachService.handleButton` / `v2Button` / `handleReason` | ~20 scripted replies: "Logged. Nice.", "Noted. What got in the way today?", "Fair. We'll pick it up tomorrow.", "Okay, noted - one off day doesn't change the trend…", "Thanks for being straight…", and others. Fixed button sets per intent. | static coaching text, issue-specific flows |
| A6 | `Policy.decideLevel` + nudge prompt levels 1–5 | Deterministic code chooses coaching intensity and the version to offer. The LLM is told "level 3: state the pattern". | rules replacing reasoning |
| A7 | `Coach.nudge` template, `maybeWeeklyReview` (Sunday 19:00–21:30), `snoozeFollowUp` (exactly 60 min after "Later") | Fixed schedules and fixed follow-up delay. | fixed schedule |
| A8 | `coach_system` prompt | Hardcodes one persona ("they already know what to do… Hinglish", "over chat (Telegram)"). Not generic, not built from memory. | user-specific |
| A9 | `CoachService.looksLikeAnswer`, `MODE_RE`, `GOAL_RE`, `USUAL_RE`, `inferTrigger` | Hinglish keyword regexes added as patches for specific live-test failures. | issue-specific hacks |
| A10 | Memory | `facts` exist, but there is no temporary memory with expiry. Retrieval dumps all facts. There is no learning memory: what the coach learned about *how* to coach this user. | missing |
| A11 | Learning | Only the Thompson slot learner for commitment windows, plus intent retirement. Intervention outcomes (answered / ignored / latency / behaviour achieved) are not evaluated or fed back into decisions. | missing |
| A12 | Telegram | The app has none. The Python bot is a separate, retired backend with its own DB. | missing |
| A13 | Dashboard | "Today" is a text status list. There are no trends, and memory, learning and interventions are not shown. | missing |
| A14 | Tests | `ServiceTest` uses a scripted LLM, so it passes even if the real agent never decides anything. `LiveGeminiTest` covers reply and extraction plus one tick with a pre-placed arrival. It never covers the LLM choosing timing, learning, or Telegram. | tests without the real agent path |
| A15 | Python `coach/` | A second implementation with its own copies of all of the above (Telegram bot, templates). It is the source of the prompts, which doubles maintenance. | duplicate logic |

## What stays deterministic (and why)
These remain code because they are not coaching decisions:
- **Safety:** shame language, unsafe advice, calorie floors.
- **Validation and grounding** of LLM extraction.
- **Nutrition ranges** from the food table.
- **Data integrity:** idempotency, dedupe of notifications.
- **Rate limits:** quiet hours, daily message cap by coaching mode, minimum gap, Gemini quota.
- **Infrastructure status lines:** "AI unavailable", "Telegram connected".

## v2 design (implemented)
See `docs/AGENT.md`.

## Re-audit for v2.1 (2026-10-07)
Searched all of `engine/`, `telegram/`, `notify/`, `work/` and the prompts for fixed lists, clock rules and canned text.

| # | Where | Problem | Fix |
|---|---|---|---|
| B1 | `Patterns.OFFPLAN_KEYS` | A keyword list (samosa, pizza…) decided what counts as "off-plan". | The LLM sets `off_plan` per food item against the user's goal (D-040). |
| B2 | `CoachService.MEAL_SLOT_TIMES` | Fixed clock per meal slot (lunch = 13:30…). | The user's stated time, else their own learned median time, else now. |
| B3 | `onPlaceEvent` leave | Leaving any place whose tag contained "gym" marked a commitment done. | Observation only. The agent decides outcomes from evidence (`commitment_outcomes`). |
| B4 | Telegram media | Photo: the reply LLM never saw what the photo showed, Flash timed out at 20 s, and errors were swallowed, so there was no reply. | `media_summary`, 60 s media timeout, and an error reply to the user (D-039). |
| B5 | Updater | Errors were silent and there was no manual check. | Status, errors, "Check now" (D-041). |

Still deterministic, on purpose:
- infrastructure lines ("AI unavailable", "Connected to Telegram", the update and call screen labels);
- the notification parser's list of food and UPI apps (an input adapter, not coaching);
- safety and rate limits;
- the one-call-a-day cap.

Nothing proactive has a fixed time or fixed text. Whether, when, how (text, Telegram or call) and what to say come from the agent.
