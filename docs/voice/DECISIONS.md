# Voice decisions

These refine D-038 in `../DECISIONS.md`. Evidence: [RESEARCH.md](RESEARCH.md). Design: [ARCHITECTURE.md](ARCHITECTURE.md).

### V-001: Native speech-to-speech (Gemini Live), not STT → LLM → TTS
**Decision.** Calls use the Live API with native audio.

**Why:**
- lowest latency;
- built-in barge-in;
- natural turn-taking;
- transcription of both sides in the same stream;
- free on the Gemini free tier.

**Rejected: cascaded STT → `gemini-3.8-flash` → `gemini-3.8-flash-tts`.** Its only real advantage is filtering text before it is spoken. It costs seconds of latency plus a custom VAD and barge-in stack.

**Trade-off accepted:** spoken output cannot be pre-filtered. This is mitigated by V-007.

### V-002: `gemini-3.8-live` primary, `gemini-3.1-flash-live-preview` fallback
**Why:** 3.8 Live is the stable default for low-latency voice agents (GA 2026-09-15). 3.1 Flash Live is a legacy preview, kept only for setup failures or audio-less turns.

**Rejected:**
- `gemini-3.8-live-extended-thinking`: background reasoning adds delay and protocol complexity. Our reasoning about whether and why to call happens before the call in the text agent.
- 2.5 native audio: older, with reported control-token and silent-audio bugs.

### V-003: The agent "calls" by ringing; the user must answer
**Decision.**
- The text agent picks `channel: call`.
- The phone shows a CallStyle incoming call (full screen where allowed).
- The microphone and the Live session start only after Answer.

**Why:** Android forbids starting a microphone foreground service from the background, except after a notification interaction. Consent per call is right anyway.

**Rejected:** auto-answer, and ambient listening.

### V-004: No server, bring-your-own key on the device
**Decision.** The phone connects straight to the Live WebSocket with the user's own key.

**Rejected:**
- **Ephemeral tokens:** they need a backend to mint them. They suit multi-user apps; here the key's owner is the only user.
- **Firebase AI Logic SDK:** needs a Firebase project, billing and App Check (required from 2026-11-02), and does not list 3.8 Live.

**Risk accepted:** the key sits on the user's own phone, as it already does for chat.

### V-005: Survive the 10-minute connection limit
**Decision.**
- Enable `sessionResumption` and `contextWindowCompression`.
- Reconnect with the latest handle on `goAway` or an unexpected close.
- Hard maximum: 15 min per call.

**Why:** connections last about 10 min, and audio sessions 15 min without compression. v2.1.8 does not handle this.

### V-006: The client owns ending the call
**Decision.** Ending order:
1. the model's `end_call`;
2. the user hangs up;
3. an idle timeout (30 s of silence, one check-in, then 15 s);
4. the maximum duration.

**Why:** Live tool calls are not guaranteed: older models called tools only 60–70% of the time, and `functionCallingConfig` is unavailable.

### V-007: Spoken-output safety after the fact
**Decision.**
- Guardrails go in `call_system.txt`.
- Numbers come only from the `lookup` tool.
- After the call, the coach's transcript passes through the Safety guard. Violations are logged and feed reflection.

**Why:** native audio cannot be filtered before playback (V-001).

### V-008: Read-only `lookup` tool during calls; all writes after the call
**Decision.**
- **During the call:** one `NON_BLOCKING` tool, backed by the existing `runQueries`, for real numbers.
- **After the call:** memory, food and commitments come from extraction over the transcript, grounded in the user's words.

**Why:** one write path (no duplicated logic) and validated memory. In-call writes would bypass the validator and are hard to undo.

### V-009: Code gates every ring
**Decision.** Code checks before ringing:
- the user setting;
- permissions and the key;
- quiet hours, the cap, the gap, and one agent call a day;
- **not while in another call**;
- **respect Do Not Disturb**.

**Why:** these are permissions and etiquette, not coaching. The agent still decides whether and why.

### V-010: Faster wake-up for near decisions
**Decision.** When the agent's next check is 30 min away or less, set one `setAndAllowWhileIdle` alarm. It may fire at most once per 9 min.

**Why:** WorkManager is deferred in Doze, so a "call in 20 min" decision could slip by hours.

**Rejected:** exact alarms (Play restricts `USE_EXACT_ALARM`; `SCHEDULE_EXACT_ALARM` needs user consent), and FCM (needs a server).

### V-011: Core-Telecom deferred
**Decision.** Do not adopt `androidx.core:core-telecom` now.

**Revisit when:** real-phone tests show Bluetooth, headset or routing problems, or the call clashes with cellular calls.

**Why deferred:** extra permission (`MANAGE_OWN_CALLS`) and lifecycle constraints (notification within 5 s, 5 s action timeouts) for benefits we have not yet needed.

### V-012: OpenAI and other providers not adopted
`gpt-live-1` is $0.05/min, has no free tier, and adds a second vendor and key. Our measured Hinglish calls on 3.8 Live worked.

**Revisit when:** real-device naturalness or latency on 3.8 Live is clearly poor.
