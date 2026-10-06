# Behaviour

How the coach decides what to do. Evidence: `RESEARCH.md` §6. Implementation: `coach/engine/policy.py`, `coach/engine/service.py`, `coach/engine/coach.py`, `coach/engine/safety.py`.

## 1. Principles

1. **The user knows what to do.** We work on *doing* it: friction, timing, pre-commitment, smaller versions, recovery after slips.
2. **Silence is a valid intervention.** If things are going well, say nothing.
3. **Escalation means a better intervention, not more messages.**
4. **Talk about the situation, not the person.** No guilt or shame; code enforces this.
5. **The user owns the goals.** We hold them to commitments *they* made. They can pause or soften the coach at any time.
6. **Consistency beats precision.** A rough log is better than none. A minimum version counts as success.
7. **One slip changes nothing.** "That happened. The next decision still matters."
8. **Never fabricate.** Data is labelled observed, estimated, inferred or unknown.

## 2. Coaching modes

| Mode | Max level | Daily proactive budget | Notes |
|---|---|---|---|
| gentle | 1 | 1 | reminders only |
| normal (default) | 3 | 3 (user-configurable, never above cap) | |
| accountability | 4 | 4 | strong recommendations of minimum versions |
| strong | 5 | 4 | only after an explicit confirmation tap; `/mode normal` revokes it |

Pausing (`/pause N` or saying so) stops all proactive messages.

## 3. Intervention levels (escalation ladder)

| Level | Name | Triggered when (per commitment) |
|---|---|---|
| 0 | silent | done already, outside window, budget used, quiet hours, paused |
| 1 | gentle reminder | default |
| 2 | contextual | missed the last scheduled day ("never miss twice"); also in-reply reminders when a trigger situation is mentioned |
| 3 | accountability | ≥ 3 misses in the last 7 days, at most once every 5 days: state the pattern factually and propose one change |
| 4 | strong recommendation | ≥ 2 misses in a row: recommend the minimum version now |
| 5 | commitment enforcement | strong mode authorised **and** the commitment was created with strong enforcement **and** a miss |

**Counting rules:**
- A day only counts as **missed** if we actually nudged and nothing was done.
- A day with **no report and no nudge** is *unknown* and is skipped when counting.
- A skip with reason **"couldn't today"** is *excused* and never escalates.

**Smaller-version ladder.** Each commitment has `versions`, e.g. 30 → 20 → 10 → 5 min.
- The offered version is the version index, computed as `consecutive misses (+1 if ≥2 ignored nudges)`.
- A done or smaller outcome resets it to the full version.

**Back-off.** After 4 ignored nudges in a row, nudge only every other day and log a `back_off` decision. The weekly review then proposes a strategy change.

## 4. Commitments

| Kind | Example | Used by |
|---|---|---|
| `if_then` | "If I go to chess, I walk there." | context trigger (in-reply reminder) |
| `precommitment` | "At chess: coffee only." | context trigger |
| `habit` | "Walk 30 min every evening 18:00–19:00." | scheduled nudges |
| `boundary` | "Never message me before 9." | stored as a commitment and as profile (`never_do`) |

- Commitments are created only from the user's own explicit statements; the LLM extracts them, and the user's words are stored.
- A time window without days defaults to daily.
- An LLM-proposed `strong` enforcement is only honoured while strong mode is authorised.

## 5. Proactive nudge flow

The scheduler tick runs every 15 minutes:
1. **Expire stale nudges.** Scheduled nudges unanswered after 120 min become `ignored` (feeds the learner). In-reply reminders become `expired` (neutral).
2. **Gate.** Check pause, quiet hours, budget and the minimum gap.
3. **Weekly review.** On Sunday 19:00–21:30, at most one per week. It is deferred if the LLM is down.
4. **Per commitment.** Skip it if it isn't scheduled today, is already done/smaller/skipped, has an open nudge, or was already nudged today. One follow-up is allowed after "Later": once 60 minutes have passed, and only until 3 hours later.
5. **Ladder.** Pick the level and version. If back-off applies, skip.
6. **Slot learner.** Thompson sampling over the remaining 30-minute slots in the window. Send if the current slot wins, with a small exploration chance, or if it is the last slot.
7. **Compose.** The LLM writes the text; the safety guard checks it; a template is used if the LLM is down. Buttons: **Done / Smaller version / Later / Skip today**.
8. **Skip asks why**, with buttons: couldn't / forgot / didn't feel like it / bad time. The reason shapes future guidance.

## 6. In-conversation behaviour

The service adds deterministic **guidance lines** to the reply prompt:

| Situation | Guidance |
|---|---|
| Trigger situation mentioned (e.g. chess) and an active if-then/precommitment exists | Remind them of *their* rule in one line, before the decision. Once per commitment per day. |
| Lapse reported | Lapse protocol: no judgement, no compensation, name the next normal action. |
| All-or-nothing thinking ("din kharab", "Monday se") | Explicitly counter it. |
| Commitment done/smaller | Brief acknowledgement; the smaller version counts. |
| Skipped: cannot | Accept it and reschedule. No pushing. |
| Skipped: forgot | Suggest an anchor or a new time. |
| Skipped: dont_want | Offer the smallest version. |
| Skipped: too_hard | Shrink the default. |
| Skipped: bad_timing | Move the time. |
| Weight logged | Talk about the trend, not the single reading. |
| Food not recognised | Say so; don't guess. |

## 7. Recovery after failure

Bad meal → no starvation or compensation → next meal normal → keep activity → continue the plan. The safety guard blocks compensation advice even if the LLM produces it.

## 8. Goal adjustment

- Targets change only when `can_adjust_targets` allows it: ≥ 14 days, ≥ 8 weigh-ins, and ≥ 8 food-logged days out of 14.
- Changes are in ±100–200 kcal steps. The coach never suggests a target below 1200 kcal/day.
- A loss rate above 1 %/week is flagged as too fast.
- When progress stalls, investigate in this order before touching calories: missing data → portions/oil/snacks/drinks → activity → sleep → adherence → water fluctuation.

## 9. Learning loop

observation → intervention (logged with level, slot, version, reason) → response (button/reply/ignored) → outcome (commitment_log) → slot statistics → next decision.

**What is learned today:**
- Per-commitment response rate per 30-minute slot (Beta-Bernoulli with forgetting factor 0.9).
- Dominant skip reason.
- Ignored streaks.

**Planned:**
- Style/framing bandit (T-602).
- Structured experiments (T-603).

## 9b. v0.2 additions

### Proactive intents (no user setup required)
| Intent | When | Requires |
|---|---|---|
| recap | recap_time (default 21:00) + 90 min | a main meal missing or inferred items pending; cadence 1/2/3 days by ignore streak |
| morning_plan | 08:30–11:00 | a predicted situation, a long calendar day, or a slip yesterday |
| predicted_context | 2 h to 15 min before the typical time | active weekday pattern **and** (user rule or lapse/snack pattern); skipped if the user already mentioned it |
| propose_commitment | 10:00–18:00, ≤ 1 per 10 days per situation | active lapse/snack-stop pattern with no rule |
| inactivity | 15:00–19:30 | fresh Health Connect data (< 3 h), steps < 35 % of typical, not in a meeting |
| reengage | 10:00–20:00 | no user message ≥ 3 days; spaced ≥ 2 days (more when dormant) |
| onboarding | 10:00–20:00 | missing goal / weight / usual meals / routine / first habit |
| stale_sync | 10:00–19:00, every 3 days | Health Connect silent > 30 h |
| commitment | inside the window, slot learner says now | as in v0.1 (ladder, back-off) |

### Engagement states
See DECISIONS D-013. The tone never mentions silence in a blaming way. Re-entry is one tap: All good / Off track / Pause 1 week.

### Patterns
Evidence-gated (D-016). A pattern only *suggests* behaviour; the coach states it with its numbers ("4 of 4 Tuesdays") and the user can reject it ("galat hai").

## 10. Experiments log

| ID | Hypothesis | Period | Result | Decision |
|---|---|---|---|---|
| — | No experiments run yet (no real usage data). | | | |

## 11. Known behavioural patterns (user)

None recorded yet. Patterns observed in real use will be listed here, each with its evidence: date range, counts, and source tables.

## 12. Successful / failed interventions

None recorded yet. Fill in from `interventions` + `commitment_log` during weekly reviews.
