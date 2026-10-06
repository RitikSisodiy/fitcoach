# Bugs

Status: OPEN | FIXED | WONTFIX. Each fix has a regression test (in `tests/test_regressions.py` unless noted).

| ID | Bug | Impact | Root cause | Fix | Regression test | Status |
|---|---|---|---|---|---|---|
| BUG-001 | Days with no data counted as misses | Escalated to level 3/4 on day one of a new commitment | `analyse_commitment` treated "no log" as "missed" | Missed only if nudged and not done; otherwise `no_data`; days before creation excluded | `test_planner::test_escalation_ladder_levels_and_versions` | FIXED |
| BUG-002 | Late "Done" tap logged to the wrong day | Yesterday counted as missed, today's nudge suppressed | `log_commitment` used `now` | Outcomes from buttons use the nudge's `local_date` | `test_bug002_*` | FIXED |
| BUG-003 | In-reply rule reminders expired as "ignored" | Back-off triggered and smaller versions offered while the user was compliant | Contextual rows counted in ignored streak / slot stats | Contextual reminders expire as `expired`; analysis only uses scheduled/follow-up nudges | `test_bug003_*` | FIXED |
| BUG-004 | Windows not on :00/:30 bypassed the learner | Always nudged on the first tick | `slots_between` grid did not match `slot_of` | Snap the window start to the 30-min grid | `test_bug004_*` | FIXED |
| BUG-005 | "200 g paneer" estimated at ~2 kcal | Wildly wrong totals | Quantity > 50 dropped but unit `g` kept → 1 g | Unit-aware max (3000 for g/ml); drop the unit with the quantity | `test_bug005_*` | FIXED |
| BUG-006 | Window without days never nudged | Silent failure of a promised check-in | `is_scheduled_on` needs `schedule_days` | Default to `daily` when a window is present (validator and store) | `test_bug006_*` | FIXED |
| BUG-007 | Edited Telegram message crashed the handler | Exception per edit | PTB `MessageHandler` also matches `edited_message` | Filter `UpdateType.MESSAGE` | `test_bug007_*` | FIXED |
| BUG-008 | Health messages could go to a group chat | Privacy leak | Only the user id was checked; `chat_id` was overwritten | Require a private chat | `test_bug008_*` | FIXED |
| BUG-009 | Equivalent timestamps double-counted steps | Inflated steps | Natural key used raw strings | Normalise instants in keys | `test_bug009_*` | FIXED |
| BUG-010 | "Couldn't today" escalated the ladder | Pushing when the user was genuinely unable | `cannot` skips counted as misses | `cannot` → `excused` (neutral) | `test_bug010_*` | FIXED |
| BUG-011 | Gemini thinking tokens could exhaust the 300-token nudge budget | Empty response → template fallback | Low `max_output_tokens` | Raised to 1024 | — (provider-specific; verify with a live key, T-102) | FIXED (unverified live) |
| BUG-012 | OpenAI reasoning models reject `max_tokens` / `temperature` | All OpenAI calls fail | Legacy parameter names | `max_completion_tokens`; retry once without temperature | `test_llm_providers::test_openai_retries_*` | FIXED |
| BUG-013 | Partial writes on failure; redelivery skipped | Lost or duplicated logs | Dedupe row written first, no transaction | Extraction before writes; message + extraction in one transaction | `test_bug013_*` | FIXED |
| BUG-014 | Times shown in UTC | Confusing pause times for an IST user | Raw UTC formatting | `Store.fmt_local` | `test_bug014_*` | FIXED |
| BUG-015 | Double-tapping Done wrote duplicate rows | Inflated adherence | Button path not idempotent | Ignore taps on already-successful nudges | `test_bug015_*` | FIXED |
| BUG-016 | "Later" near the window end got no follow-up | Snooze silently dropped | Follow-up required being inside the window | Follow-up allowed up to 3 h after snooze, once (`kind=follow_up`, excluded from slot learning) | `test_planner::test_later_gives_exactly_one_follow_up` | FIXED |
| BUG-017 | LLM outage sent a generic chat reply as a nudge / weekly review | Confusing proactive message | Single fallback text for every purpose | Template nudge; weekly review deferred | `test_planner::test_llm_outage_*` | FIXED |

## Known limitations (open)

| ID | Limitation | Impact | Plan |
|---|---|---|---|
| LIM-001 | Steps from two sources (phone and watch) are summed | Overcount when both write to Health Connect | Restrict to one data origin, or ingest daily aggregates (T-402) |
| LIM-002 | Windows crossing midnight: the local-day logic attributes after-midnight slots to the next day | Rare double nudge | Disallow cross-midnight windows in validation, or anchor them to the start day (T-505) |
| LIM-003 | Extraction failures are not retried | Some messages never get structured | Nightly re-extraction of `extraction_failed` messages (T-206) |
| LIM-004 | Food is timestamped at message time, not meal time | "Lunch" mentioned at 6 pm is logged at 6 pm | Use `meal_slot` / time hints for `occurred_at` (T-207) |

## Found by the 30-day simulation (v0.2)

| ID | Bug | Impact | Root cause | Fix | Regression test | Status |
|---|---|---|---|---|---|---|
| BUG-018 | Recap retired after 6 ignores | Tracking stopped for a week | Generic retirement rule applied to the core tracking prompt | Recap never retired; cadence 1 → 2 → 3 days | `test_v2_autonomy::test_ignored_recaps_become_less_frequent_not_retired` | FIXED |
| BUG-019 | No defaults for lazy users | "All usual" never offered | Habits needed ≥ 3 identical user logs | Declared usual meals via onboarding | `test_declared_usual_meals_enable_one_tap_recap_from_day_one` | FIXED |
| BUG-020 | Unconfirmed passive signals expired | Orders/snacks lost | Expiry with no fallback | Labelled auto-logging rules (D-015) | `test_orders_auto_logged_as_unconfirmed_and_known_payees_logged` | FIXED |
| BUG-021 | Routine not learned when the user doesn't mention it | No pre-emptive coaching | Patterns came only from mentions | Payee × weekday pattern | `test_recurring_payee_becomes_snack_stop_pattern` | FIXED |
| BUG-022 | Daily morning-plan noise | Habituation | A commitment list alone triggered the plan | Only predicted situation / long day / slip yesterday | simulation | FIXED |
| BUG-023 | Rule proposal accepted with no rule text | Meaningless commitment | Button shown without an LLM rule | Button only with a concrete `proposed_rule` | `test_predicted_chess_day_triggers_pre_emptive_message_and_rule_proposal` | FIXED |
| BUG-024 | `commitment_changes` mutated in place | KeyError when summarising the extraction | `dict.pop` on shared data | Copy before popping | `test_commitment_can_be_edited_by_conversation` | FIXED |

## Found by the second code review (v0.2.2)

| ID | Bug | Fix | Regression test | Status |
|---|---|---|---|---|
| BUG-025 | SBI/ICICI/Axis/Kotak formats misparsed; payees truncated or set to bank helplines | Per-format amount and payee parsing; full VPA as key; helpline/phone rejection | `test_bug025_*` | FIXED |
| BUG-026 | Collect requests, refunds, autopay and grocery orders treated as food spending | Exclusion words; grocery apps removed | `test_bug026_*` | FIXED |
| BUG-027 | Raw SMS stored; person payee names sent to the LLM / shown on buttons | Body discarded after parsing; only merchant names shown | `test_bug027_*` | FIXED |
| BUG-028 | Same payment via SMS + app counted twice | Amount ± 3 min dedupe | `test_bug028_*` | FIXED |
| BUG-029 | One order → several meals | Order id or same app within 2 h | `test_bug029_*` | FIXED |
| BUG-030 | Naive forwarder times read as UTC; bad time crashed a batch | Naive = user tz; epoch support; per-item errors | `test_bug030_*` | FIXED |
| BUG-031 | Inferred food double-counted with typed food / "All usual" | Merge check (±90 min snack, ±3 h meal); recap skips slots with a pending order | `test_bug031_*` | FIXED |
| BUG-032 | Recap buttons dead after 2 h; one tap removed unrelated buttons; repeat taps logged twice | Valid for 24 h, one tap per prompt; keyboard strips only the answered group | `test_bug032_*` | FIXED |
| BUG-033 | Patterns never demoted | Active patterns not re-derived become candidates | `test_bug033_*` | FIXED |
| BUG-034 | Situation → lapse ignored the base rate; healthy snacks counted as off-plan | Explicit off-plan list; lift ≥ 0.25 over other days; lapses after the mention only | `test_bug034_*` | FIXED |
| BUG-035 | "kal chess jaunga" dated today | `day: tomorrow` in extraction | `test_bug035_*` | FIXED |
| BUG-036 | Inactivity nudge mid-meeting on fully booked days | Busy intervals stored and checked | `test_bug036_*` | FIXED |
| BUG-037 | Weekly review/follow-ups bypassed engagement gating | Same allowed/retired checks | `test_bug037_*` | FIXED |
| BUG-038 | Button-only users treated as silent | Taps count as contact | `test_bug038_*` | FIXED |
| BUG-039 | "All usual" taps turned into self-confirming habits | Habits from user-entered rows only | `test_bug039_*` | FIXED |
| BUG-040 | Gemini thinking could truncate the decide JSON | Output limit 4096; google-genai ≥ 1.30 | live check | FIXED (verify live) |
| BUG-041 | Minor issues | "kal" history offset; dinner after midnight; auto-complete respects schedule and uses one session per commitment; malformed reason ids; unknown pattern ids | covered by existing tests | FIXED |

## Found by live testing with real Gemini (2026-10-06, on the user's laptop)

| ID | Bug | Impact | Fix | Test | Status |
|---|---|---|---|---|---|
| BUG-042 | No handling of 503 "high demand" / 504 / 429 | 20/20 extractions failed on the first live run | Retry across a model list; per-model cool-down | `test_gemini_falls_through_models_on_quota_and_overload` | FIXED |
| BUG-043 | Free tier is 5 RPM / **20 requests per day** per Flash model (Lite: 15 RPM / 500 RPD) | Bot would stop answering after ~10 messages a day | Purpose routing (understanding+replies → Flash pool ~80/day, background → Lite pool ~1000/day), local RPM/RPD pacing persisted to disk, day-quota detection | `test_routes_by_purpose_and_respects_daily_quota`, `test_daily_quota_error_blocks_model_for_the_day` | FIXED |
| BUG-044 | Model invented food from context ("ok" → chaat from a pending payment; "kuch bhi kha raha" → chaat) | Fabricated logs | Every food/commitment/setting/goal/correction must carry a quote found in the user's message; food name (or a known alias) must appear in the message | `test_ungrounded_items_are_dropped`, `test_food_not_in_message_is_dropped_but_aliases_count` | FIXED |
| BUG-045 | "kal kitna khaya?" produced a coaching-mode change | Silent settings change | Same grounding; flat settings fields; 7-day summary in every reply so history questions don't depend on extraction | `test_ungrounded_items_are_dropped` | FIXED |
| BUG-046 | Goals and usual meals ignored by the model inside a nested `profile_updates` object | Goal never saved | Flattened schema fields (`goal_weight_kg`, `usual_meals`, ...) with few-shot examples | `test_flat_goal_settings_and_usual_meals` | FIXED |
| BUG-047 | "3 roti" skipped because roti was already in FOOD_LOGGED_TODAY | Lost log | Prompt: today's log is context for corrections only | live check | FIXED |
| BUG-048 | Grounding tokenizer kept trailing dots ("sabzi.") | Valid food dropped | Tokenizer fix | `test_natural_meal_message_is_logged_with_ranges` | FIXED |
| BUG-049 | Rules returned without a trigger | Rule never fires | If the message names exactly one situation, it becomes the trigger | `test_rule_without_trigger_gets_the_single_situation` | FIXED |
| OPEN-050 | Output varies run to run and between fallback models; usual-meal statements and payment confirmations are sometimes missed | Some onboarding answers need repeating | Tracked; recap button path does not depend on extraction | live check (3 runs: 14/20 → 16/20 → 16/20) | OPEN |
