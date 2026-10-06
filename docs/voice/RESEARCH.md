# Voice research: a natural real-time voice coach (2026-10-07)

**Scope:** what is currently the best way to give FitCoach a natural, real-time voice conversation, including a coach that "calls" the user.

**Sources:** official Google, Android and OpenAI documentation, read on 2026-10-07; supplemented by the Google AI developer forum and our own measured tests. Page dates are noted where the page gives one.

**Context:** v2.1.8 (shipped 2026-10-07) already contains a first voice-call implementation (D-038). This research re-checks that design from scratch and lists what should change. Nothing here assumes v2.1.8 is right.

---

## 1. Models available today

From the Gemini models page (updated 2026-10-06) [G1]:

| Model ID | Status | What it is |
|---|---|---|
| `gemini-3.8-live` | Stable (GA 2026-09-15) | "Default Live API model for most low-latency voice agent experiences without reasoning delays" |
| `gemini-3.8-live-extended-thinking` | Stable | Live model with background reasoning, for complex multi-step voice tasks |
| `gemini-3.1-flash-live-preview` | Preview, legacy | Previous audio-to-audio model; Google recommends migrating to 3.8 Live |
| `gemini-2.5-flash-native-audio-preview-12-2025` | Preview | Older native-audio model |
| `gemini-3.8-flash-tts`, `gemini-3.8-flash-lite-tts` | Stable | Text-to-speech only (for a cascaded design) |
| `gemini-3.5-live-translate-preview` | Preview | Speech-to-speech translation (not a conversational agent) |

`gemini-3.8-live` [G2]:
- **Input / output:** text, image, audio and video in; text and audio out.
- **Context:** 131,072 input tokens and 65,536 output tokens.
- **Supported:** function calling (asynchronous by default) and search grounding.
- **Thinking:** "interleaved"; `thinkingLevel` is not supported.
- **Proactive audio:** permanently on. Setting it to false is an error.
- **Affective dialog:** removed.

`gemini-3.8-live-extended-thinking` [G3]:
- Thinking levels low, medium and high.
- Asynchronous (non-blocking) tool calls only.
- `turnComplete` no longer means the model is idle; the client must watch `interactionStatus` (IN_PROGRESS / IDLE).

## 2. Live API capabilities [G4][G5][G6]

**Transport:** a stateful WebSocket (`BidiGenerateContent`).

**Audio:**
- In: raw PCM16 at 16 kHz, little-endian.
- Out: PCM16 at 24 kHz.
- Audio costs about 25 tokens per second of audio.

**Native speech-to-speech:** the model hears and speaks directly. There is no separate speech-to-text or text-to-speech step.

**Languages:**
- 99 languages; native audio models pick the language themselves, and the language cannot be forced [G5].
- Hindi is in the primary list [F1].
- Our own tests: Hinglish both ways worked (§10).

**Live transcription:**
- `inputAudioTranscription: {}` transcribes the user.
- `outputAudioTranscription: {}` transcribes the model.
- Both arrive as incremental text fragments during the turn.

**Voice activity detection (VAD) and barge-in:**
- Automatic VAD is on by default (`realtimeInputConfig.automaticActivityDetection`: sensitivities, `prefixPaddingMs`, `silenceDurationMs` about 800 ms).
- It can be turned off for manual `activityStart` / `activityEnd`; a hybrid mode with `audioStreamEnd` also exists.
- When the user talks over the model, the server sends `interrupted`. The client must stop playback and clear queued audio.

**Tools [G6]:**
- Function declarations go in the setup message.
- The server sends `toolCall.functionCalls[{id, name, args}]`; the client answers with `toolResponse.functionResponses[{id, name, response}]`.
- `NON_BLOCKING` functions take a scheduling value: `INTERRUPT`, `WHEN_IDLE` or `SILENT`.
- Google Search grounding is available.
- `functionCallingConfig` (forcing a call) is not available in Live [forum, F3].
- **The docs disagree on the default:** the 3.8 Live model page says function calling is asynchronous by default [G2], while the tools guide says calling is sequential unless `NON_BLOCKING` is declared [G6]. Declare the behaviour explicitly so the default never matters.

**Context and memory:**
- There is no server-side memory across sessions. Everything the model knows comes from `systemInstruction`, the text and audio sent in the session, and tool results.
- `clientContent` with explicit roles can seed history; a `turn_complete` sent there interrupts generation.

**Session limits [G7]:**
- Audio-only sessions: 15 min without compression.
- Connection: about 10 min. The server sends `goAway` with `timeLeft` before closing.
- `contextWindowCompression` (sliding window) removes the 15-min session limit.
- `sessionResumption` handles stay valid for 2 h, so a session can survive a reconnect.

**Best practices [G8]:**
- Send 20–40 ms audio chunks with no client-side buffering.
- Flush playback on `interrupted`.
- Enable compression and resumption, and handle `goAway`.
- Tool descriptions should state exactly when to call them.

**Security [G9]:**
- Google recommends ephemeral tokens for client-to-Live connections.
- Ephemeral tokens need a backend that holds the real key and mints the tokens (default: 1 min to start a session, 30 min of use).

## 3. Known issues (forum, measured by others)

- **No speech-state signal on 3.8 Live [F2] (2026-09-16):** `speechState` and `VoiceActivity` messages are gone on 3.8 Live, so there is no server-side "user started speaking" signal. Any "listening…" indicator must be computed on the client.
- **Possible VAD failure [F4]:** a third-party guide (2026-09-15) reports that 3.8 Live gave no response to streamed audio unless manual VAD was used. **This did not reproduce for us:** our two-session test used automatic VAD with continuous silence frames between turns and got 5–7 turns (§10). Probable cause: their client stopped streaming after the utterance, so the server never heard the end-of-speech silence. **Implication:** keep the microphone stream continuous (we do).
- **Older models [F3] (January–March 2026):**
  - disconnects with 1008/1011 around the 10–14 min mark;
  - a race between audio input and tool calls;
  - tools called only 60–70% of the time;
  - input transcription stopping during 30 s+ monologues.
  
  Reported as largely fixed in 3.1 Flash Live. **Implication:** never rely on the model calling `end_call`; keep a client-side end path.
- **Missing audio:** reports of a model returning a transcript but no audio (an earlier forum note), and of 2.5 native audio emitting control tokens. v2.1.8 already falls back to another model if the first turn has no audio.

## 4. Native speech-to-speech vs STT → LLM → TTS

| | Native (Gemini Live) | Cascaded (STT → `gemini-3.8-flash` → `gemini-3.8-flash-tts`) |
|---|---|---|
| Latency | Lowest: one model, streaming both ways | Three hops. Each hop streams, but end-of-speech detection plus LLM first token plus TTS first audio usually adds up to seconds. |
| Barge-in | Built in (`interrupted`) | Must be built: client VAD, cancel the LLM and TTS, keep turn state |
| Prosody and turn-taking | Natural; proactive audio decides when a reply is relevant | Robotic turn-taking unless heavily engineered |
| Text-level control | The spoken audio cannot be checked before it plays. Transcripts arrive alongside it. | Every sentence can pass our Safety guard before TTS |
| Tools and memory | Function calling in session; context from the system instruction | Full normal API (structured output, every tool) |
| Cost (paid tier) | Audio in $0.005/min, audio out $0.018/min, plus text context re-billed per turn [G10][F3] | STT + LLM tokens + TTS ($6–9 per 1M audio tokens, rates double from 2027-01-01) |
| Free tier | Live models "free of charge" on the free tier [G10] | Also free-tier eligible |

**Conclusion:** for a call that should feel like talking to a person, native Live is clearly better. The one real gap is spoken-output safety; mitigate it with instructions, a post-call check of the output transcript, and an end-call path (ARCHITECTURE §6).

## 5. Android integration

**Notification and full-screen call UX:**
- **CallStyle (Android 12+) [A1]:**
  - `CallStyle.forIncomingCall(person, decline, answer)` gives system-styled Answer/Decline buttons, top ranking, and can be made non-dismissible on Android 14+ with `setOngoing(true)`.
  - On API ≤ 30 it is recommended to tie it to a foreground service (FGS) for ranking.
- **Full-screen intent (FSI) [A2]:**
  - Android 14+ grants `USE_FULL_SCREEN_INTENT` at install, but **Google Play revokes it for apps that are not calling or alarm apps**.
  - Sideloaded apps (FitCoach is installed from GitHub releases) keep it, and the user can toggle it under Special app access.
  - Without it the call shows as a heads-up notification with buttons (about 60 s).
  - The app checks with `NotificationManager.canUseFullScreenIntent()`.

**Microphone and background [A3]:**
- **Rule:** an app in the background cannot start a microphone FGS. It must be started while an activity is visible, or from an exempt trigger, which includes **"the service starts by interacting with a notification"**.
- **What this means for us:**
  - The *ring* (a notification) can be posted from the background (WorkManager).
  - The *conversation* (microphone) can only start once the user taps Answer.
  - **Auto-answer is impossible, and should be.**

**Waking up to decide [A4]:**
- In Doze, WorkManager and JobScheduler jobs are deferred to maintenance windows and network access is suspended.
- `setAndAllowWhileIdle` / `setExactAndAllowWhileIdle` alarms can fire in Doze, at most once per 9 minutes per app.
- High-priority FCM is the server-side wake-up, but FitCoach has no server.
- ColorOS and OxygenOS add their own aggressive killing (Setup already guides the exemptions).

**Core-Telecom (`androidx.core:core-telecom`) [A5]:**
- Registers a VoIP app with the system (`MANAGE_OWN_CALLS`).
- Gives proper audio-endpoint switching (earpiece, speaker, Bluetooth), answer and hang-up from Bluetooth headsets, watches and Android Auto, and correct behaviour next to real cellular calls.
- Needs a notification within 5 s of adding a call, and phone-call FGS semantics.
- It is optional. CallStyle works without it.

**Firebase AI Logic Android SDK [F1][F5]:**
- `LiveModel.connect()` and `startAudioConversation()` handle microphone and speaker for you.
- Requirements:
  - a Firebase project with billing;
  - App Check, which becomes required on 2026-11-02;
  - no client API key.
- The docs list `gemini-3.1-flash-live-preview` and 2.5 native audio as supported Live models. 3.8 Live is not listed.

## 6. Can the agent start a voice session on its own?

**Yes, as a ring, not as an open microphone:**
1. The background agent decides to call (any wake-up: WorkManager, a geofence, or an alarm).
2. It posts a CallStyle notification with FSI. That is allowed from the background.
3. The user taps Answer, which opens our activity. The microphone FGS and the Live session start there. That is allowed because a notification interaction started it.

The Live session itself is client-initiated (the app opens the WebSocket). Nothing on Google's side can "call" the phone.

## 7. Cost, quotas, practical limits

- **Free tier [G10]:** Live models are free of charge. Rate limits are per project and per tier, shown only in AI Studio [G11]. Preview models have tighter limits.
- **Paid tier [G10]:**
  - text in $0.75/1M and text out $4.50/1M;
  - audio in $3/1M (about $0.005/min) and audio out $12/1M (about $0.018/min).
  - The context is billed per turn, cumulatively (forum [F3], quoting the docs).
- **Estimate for a 5-minute call** (about 2.5 min of each side talking, about 6k tokens of context re-sent over about 10 turns): about $0.06–0.10 on the paid tier, $0 on the free tier.
- **Practical limits:**
  - about a 10-min connection (needs resumption);
  - one call a day (our own rule);
  - the user has to tap Answer;
  - full-screen ringing depends on the FSI permission;
  - Doze can delay the decision to call;
  - OEM battery killers.

## 8. Alternatives checked

| Option | Verdict |
|---|---|
| **OpenAI `gpt-live-1`** [O1] | Full-duplex voice with smooth interruptions and tool calling. $0.05/min plus backend model and tool costs; **no free tier**; no image input. Not materially better for this product: it adds a second vendor and key and costs money from the first minute. Our Gemini setup already handled Hinglish turns and barge-in. Revisit only if real-device quality on 3.8 Live disappoints. |
| OpenAI `gpt-realtime-2.1` / `-mini` [O2] | Reasoning realtime models. Same vendor and cost argument. |
| `gemini-3.8-live-extended-thinking` | Better for deep reasoning during a call, but adds reasoning delay, only async tools, and the `interactionStatus` complexity. Our "should I call, and why" reasoning happens *before* the call in the text agent. Not needed. |
| `gemini-3.1-flash-live-preview` | Legacy preview. Keep only as an automatic fallback. |
| Cascaded STT → LLM → TTS | Rejected as the main path (§4). Its text-level safety is not worth the latency and barge-in cost. |
| Firebase AI Logic SDK | Simplest audio code, but it needs a Firebase project, billing and App Check, conflicts with the bring-your-own-key, no-server design, and does not list 3.8 Live. Rejected for now. |
| Ephemeral tokens | Correct for multi-user apps with a backend. FitCoach is single-user: the key belongs to the person and stays on their phone. Rejected (no server); the risk is documented. |

## 9. Which model is the best fit
**`gemini-3.8-live`.** It is the stable default for low-latency voice agents, free on the free tier, handles native audio with barge-in and transcription of both sides, and supports tools.

Keep `gemini-3.1-flash-live-preview` as an automatic fallback for setup failures or audio-less turns.

## 10. Our own measurements (2026-10-07, laptop and emulator)

**`LiveVoiceTest`:** a coach session (3.8 Live) and a simulated-user session (3.8 Live) exchanged audio, resampled 24 → 16 kHz and streamed in real time.
- 5–7 turns of Hinglish.
- The model ended the call itself with `end_call` and a correct summary.
- Both sides were transcribed.
- No fallback was needed.

**Emulator, real key:**
- The agent chose `channel: call` on its own after the user asked.
- The CallStyle Answer/Decline notification appeared.
- Answer → live call on `gemini-3.8-live`; the coach spoke first in context.
- End → ingested.

**Not yet measured:**
- latency (answer → first audio, end of user speech → first reply audio);
- a real phone's microphone, echo cancellation and earpiece;
- calls longer than 10 min.

---

## Sources
- [G1] Gemini models: https://ai.google.dev/gemini-api/docs/models (updated 2026-10-06)
- [G2] Gemini 3.8 Live: https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live
- [G3] Gemini 3.8 Live Extended Thinking: https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live-extended-thinking
- [G4] Live API overview: https://ai.google.dev/gemini-api/docs/live (updated 2026-09-15)
- [G5] Live API capabilities guide: https://ai.google.dev/gemini-api/docs/live-guide
- [G6] Live API tool use: https://ai.google.dev/gemini-api/docs/live-tools
- [G7] Live API session management: https://ai.google.dev/gemini-api/docs/live-session
- [G8] Live API best practices: https://ai.google.dev/gemini-api/docs/live-api/best-practices
- [G9] Ephemeral tokens: https://ai.google.dev/gemini-api/docs/ephemeral-tokens
- [G10] Pricing: https://ai.google.dev/gemini-api/docs/pricing
- [G11] Rate limits: https://ai.google.dev/gemini-api/docs/rate-limits
- [F1] Firebase AI Logic Live API limits and specs: https://firebase.google.com/docs/ai-logic/live-api/limits-and-specs (updated 2026-10-06)
- [F5] Firebase AI Logic Live API: https://firebase.google.com/docs/ai-logic/live-api (updated 2026-10-01)
- [F2] Forum, 3.8 Live speechState removed: https://discuss.ai.google.dev/t/gemini-3-8-live-speechstate-speech-non-speech-is-gone-no-server-side-speech-start-signal-remains/183171
- [F3] Forum, Live API disconnects, cost, function calling: https://discuss.ai.google.dev/t/gemini-live-api-issues-1008-1011-disconnects-per-session-cost-function-calling-api-logs/116509
- [F4] Third-party guide on 3.8 Live: https://www.livelingo.io/guides/gemini-3-8-live
- [A1] Android CallStyle: https://developer.android.com/develop/ui/views/notifications/call-style
- [A2] Full-screen intent limits: https://source.android.com/docs/core/permissions/fsi-limits
- [A3] FGS background-start and while-in-use limits: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- [A4] Doze and App Standby: https://developer.android.com/training/monitoring-device-state/doze-standby
- [A5] Core-Telecom: https://developer.android.com/develop/connectivity/telecom/voip-app/telecom
- [O1] OpenAI gpt-live-1: https://developers.openai.com/api/docs/models/gpt-live-1
- [O2] OpenAI models: https://developers.openai.com/api/docs/models
