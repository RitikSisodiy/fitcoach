# Voice architecture (recommended)

See [RESEARCH.md](RESEARCH.md) for the evidence and [DECISIONS.md](DECISIONS.md) for the choices.

**Summary:** keep the shape v2.1.8 already has. Change how the session is kept alive and ended, how the call is gated, what the coach can look up during the call, and how quality is measured.

## 1. Shape

```
                 ┌────────────────────── text agent (Agent.decide, Flash-Lite) ───────────────────────┐
 observations ──▶│ situation + memory + limits(can_call_now) ──▶ act? channel = notification|telegram|call │
                 └───────────────────────────────────────────────┬────────────────────────────────────┘
                                                                 │ channel = call (code checks limits)
                                                                 ▼
  calls row "ringing" ──▶ CallManager: CallStyle incoming notification + full-screen intent (ringtone, 45 s)
        │ Decline / no answer (3 min) ──▶ observation ──▶ text agent decides any follow-up (or nothing)
        │ Answer (notification tap ⇒ allowed to start the microphone foreground service)
        ▼
  CallActivity (shows on the lock screen) ──▶ VoiceCallService (FGS, type microphone)
        │  CallAudio: AudioRecord 16 kHz VOICE_COMMUNICATION + AEC/NS ──▶ 20–40 ms chunks, continuous (silence included)
        │             AudioTrack 24 kHz voice-communication; flush on `interrupted`
        ▼
  LiveSession (WebSocket, gemini-3.8-live → fallback gemini-3.1-flash-live-preview)
        setup: systemInstruction = call_system.txt(purpose, context, memory), voice, input+output transcription,
               contextWindowCompression, sessionResumption, tools [end_call, lookup]
        ◀── audio + transcripts + toolCall + goAway/resumption handles
        ▼
  end: end_call(summary) | user hangs up | idle timeout | max duration | error
        ▼
  CoachService.ingestCall: turns → messages(channel=call); extraction over the transcript (grounded in user turns);
        intervention answered; post-call safety check of the coach's words; observation call_ended
        ▼
  next agent decision sees the call (memory, commitments, observation)
```

## 2. Who decides what

| Concern | Owner |
|---|---|
| Whether to call, when and why | Text agent (LLM), the same decision as any message |
| Whether a call is allowed now | Code: user setting, permissions, quiet hours, daily cap, minimum gap, one agent call a day, **not while the phone is in another call** |
| What is said, follow-up questions, when the conversation is done | Live model, guided by `call_system.txt` and the purpose |
| Live data lookups during the call | Live model through a read-only `lookup` tool (§4) |
| What gets remembered | Post-call extraction + validator (the same path as chat), not in-call writes |
| Follow-up after the call or a missed call | Text agent, from observations |

## 3. Session lifecycle (changes vs v2.1.8 marked ★)

1. **Connect:**
   - Send the setup.
   - On `setupComplete`, send the opening text so the coach speaks first.
   - If setup fails, or the first coach turn has a transcript but no audio, switch to the fallback model.
2. ★ **Compression and resumption:**
   - Enable `contextWindowCompression` (sliding window) and `sessionResumption`.
   - Store the latest handle. On `goAway`, or an unexpected close mid-call, reconnect with the handle so the user hears no reset.
   - Show "Reconnecting…" only if it takes longer than 2 s.
3. **Audio:**
   - Stream the microphone continuously in 40 ms chunks, silence included. Automatic voice activity detection (VAD) needs the trailing silence.
   - Flush playback on `interrupted`.
4. ★ **Listening indicator:** 3.8 Live sends no speech-state signal, so derive "listening" and "you're speaking" from the local microphone level.
5. ★ **Ending, in this order:**
   1. `end_call(summary)`: let the goodbye finish playing, then close.
   2. The user hangs up.
   3. **Idle timeout:** no user speech and no coach audio for 30 s. The coach is asked once ("still there?"); if silence continues another 15 s, end.
   4. Hard maximum: 15 min.
   5. Unrecoverable error.
   
   The summary falls back to null; post-call extraction still runs.
6. **Ingest:** the same as v2.1.8, plus ★ a post-call safety check of the coach's transcript (log any violations to the decision log and coach insights so the next prompt corrects them).

## 4. Context and memory during a call

- **System instruction:** why the coach called, plus the same context and memory as chat (`buildContext`), plus the last 12 messages across channels. ★ Trim it to the relevant parts (memory, today, commitments, the last 7 days); the full JSON grows the per-turn re-billed context.
- ★ **`lookup` tool:**
  - Read-only and `NON_BLOCKING` (scheduling `WHEN_IDLE`).
  - Backed by the existing `runQueries` (`DataQuery`), so the coach can answer "how many steps did I do last week" with real numbers instead of guessing or saying it doesn't know.
- **No in-call write tools.** Everything said is extracted after the call from the full transcript, grounded and validated like chat. One write path, no duplicated logic.
- **Across calls:** nothing is kept server-side. The next call or message knows the call through messages (channel `call`), extracted memory and commitments, and the `call_ended` observation.

## 5. Ringing rules (code, not coaching)

- **Ring only if:**
  - the user allows calls;
  - notifications and the microphone are granted;
  - an API key is set;
  - the time is outside quiet hours;
  - the daily cap and minimum gap allow it;
  - ★ the phone is not in another call (`AudioManager.mode` is normal and no call is active);
  - ★ Do Not Disturb is off for our channel's interruption filter.
- **No full-screen permission:** the call is a heads-up notification. Setup shows how to enable full screen.
- ★ **Decision latency:** if the agent sets a near `next_check` (≤ 30 min), schedule one `setAndAllowWhileIdle` alarm. It can fire at most once per 9 min and wakes the tick in Doze. Otherwise WorkManager.

## 6. Spoken-output safety
The audio cannot be filtered before it plays. Mitigations:
- explicit guardrails in `call_system.txt`;
- the coach never invents numbers (the `lookup` tool provides them);
- ★ the post-call Safety check on the output transcript, logged and fed into reflection;
- the user can always hang up.

## 7. Optional later: Core-Telecom
Register as a self-managed VoIP app (`androidx.core:core-telecom`, `MANAGE_OWN_CALLS`). This adds Bluetooth-headset, watch and car answer/hang-up, proper earpiece/speaker/Bluetooth routing, and correct behaviour next to cellular calls.

Do it only if real-phone testing shows routing or headset problems.

## 8. What stays out
- Auto-answer or opening the microphone from the background: not allowed by Android, and not wanted.
- A server, FCM or ephemeral tokens: FitCoach stays no-server and bring-your-own-key.
- Cascaded STT/TTS and the extended-thinking model on calls.
