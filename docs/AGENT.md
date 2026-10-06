# The agent (v2)

One backend on the phone serves every channel: the app chat, notifications (with tap-to-reply), Telegram and voice calls. There is one SQLite DB, one memory, one agent and one conversation. The LLM does the reasoning and all coaching words. Code observes, stores, validates, enforces limits and keeps the statistics the agent learns from.

## Loop

```
Observe   sensors (Health Connect, walks, places, UPI/food notifications, calendar, screen) + every message, any channel
            -> events tables + `observations` log
Understand  LLM extraction -> validated, grounded in the user's words (ExtractionValidator)
Remember  events (food, activity, metrics, context), temporary memory (facts.valid_until), long-term memory (facts),
          coach insights (facts, category coach_insight)
Retrieve  every reply and every decision gets the memory tiers, a 7-day digest, today's state, commitments + history,
          patterns, open loops, recent conversation, past interventions and their outcomes
Reason/   Agent.decide(SITUATION) -> act? message, quick replies, channel, expected outcome,
Decide      and next_check_minutes + reason (the agent picks its own next wake-up)
Limits    code: pause, quiet hours, daily cap per coaching mode/engagement, minimum gap, safety guard, eval quota
Send      notification (quick replies as actions), Telegram (inline keyboard) or a voice call; always also in the app chat
Observe   reply / tap (any channel) -> answered + response minutes; nothing within 3 h -> ignored
response
Evaluate  commitment done that day -> achieved (credits the slot learner), else not_achieved
Learn     daily reflection (LLM) turns evaluated outcomes + response stats by hour and channel into <=8 coach insights,
          which every later decision and reply sees
```

## When the agent wakes (CoachService.tick)
The WorkManager tick runs every 15 minutes, and a geofence arrival triggers one immediately. Each tick runs the housekeeping steps (expire, evaluate, auto-complete, inferred payments, reflection). It then calls the LLM only if one of these holds:
- a **significant observation** arrived (place arrival or leave, walk or workout, weigh-in, payment or order, commitment completed by sensors);
- the agent's **own next_check_at** has passed;
- there is **no evaluation for 4 h** (a safety net);
- the call is **forced** (tests).

The LLM is never called during quiet hours or while paused. Evaluations are capped at 48 per day (Gemini free tier).

## What is still deterministic, and why
Only safety, data integrity and infrastructure:
- **Safety:** the Safety guard; unsafe drafts are rewritten by the LLM once, otherwise dropped.
- **Grounding:** extraction is validated against the user's words.
- **Nutrition:** ranges come from the food table.
- **Data integrity:** notification parsing and dedupe.
- **Rate limits:** budget per coaching mode and engagement state, quiet hours, minimum gap.
- **Statistics:** computed by code (commitment history, response rates, timing evidence) so the LLM never invents numbers.

Nothing picks message content, timing windows or follow-up schedules in code. There are no templates. When the AI is unavailable the agent stays silent, and replies show a status line marked as such.

## Quick replies
The agent writes up to 3 quick replies per message. A tap is the user's own message (`handleMessage`) from any channel. The extractor maps it onto the open message: a commitment outcome, a "usual" meal, or a payment confirmation. There are no button-specific flows.

## Telegram (D-033)
- `TelegramService` is a foreground service that long-polls the user's own bot with `getUpdates`.
- **Pairing:** the user sends a one-time code (deep link `t.me/<bot>?start=<code>`).
- **Inbound:** text, voice and photo from the paired chat go to `handleMessage(channel="telegram")`.
- **Outbound:** replies go back to Telegram. Proactive messages go there when the agent chooses the Telegram channel.

## Media (D-039)
Photos and voice notes from any channel go to the multimodal extraction call. That call also writes `media_summary` (a transcript, or what the photo shows). The summary is stored as the message content and handed to the reply, so memory and later answers work on what was in the media.

## Voice calls (D-038)
```
agent decides channel "call" (only if limits.can_call_now) -> calls row "ringing" -> CallStyle notification / full screen
  declined | missed (3 min)            -> observation -> the agent reconsiders (maybe a text later, maybe nothing)
  answered -> VoiceCallService + Gemini Live (call_system.txt: why it called + context + memory)
           -> live transcript on screen; the coach ends with end_call(summary)
           -> ingestCall: turns stored as messages (channel call), extraction over the transcript (grounded in the
              user's words), the intervention marked answered, call_ended observation -> next decision
```
The user can also start a call from Chat (📞). Calls have no fixed times or scripts: the coach only gets the reason it chose to call.

## Agent-settled commitments
The agent can report `commitment_outcomes` (done, smaller or skipped, with evidence) when observations show what happened: a place stay, a workout, a call. Code accepts only active commitments with no report yet that day.

## Files
- `engine/Agent.kt`: situation builder, decision, reflection, response statistics.
- `engine/CoachService.kt`: message pipeline, tick, limits, outcome evaluation, memory retrieval.
- `engine/Dashboard.kt` and `ui/DashboardScreen.kt`: everything above made visible.
- `voice/`: Live session (WebSocket), call audio, ringing and decline, the call service and the call screen.
- `telegram/`: Bot API client, bridge (pairing, updates, quick-reply callbacks), service, and channel delivery.
- `assets/prompts/`:
  - `coach_system.txt`
  - `agent_system.txt` + `agent_schema.json`
  - `reflect_system.txt` + `reflect_schema.json`
  - `extraction_system.txt` + `extraction_schema.json`
