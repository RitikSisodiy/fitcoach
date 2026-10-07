# Mobile platform decisions (M-xxx)

Evidence: [RESEARCH.md](RESEARCH.md). Design: [ARCHITECTURE.md](ARCHITECTURE.md). These supersede D-027 ("no server") once implemented.

### M-001: Move the agent to a backend; phones become clients
**Decision.** The agent loop, memory, plan, scheduler, Telegram and push sending move to a small Kotlin backend that reuses today's engine.

**Why:**
- iOS gives apps no dependable background execution ("virtually no control", Apple DTS).
- iOS can only be woken for an agent message or call by a server push. VoIP pushes must come from a server and must be real calls.
- On Android the server also removes Doze, OEM killing and the on-device key.

**Rejected:**
- *On-device agent on iOS* (BGTasks plus local notifications): opportunistic timing, and local notifications are static schedules.
- *On-device agent on Android plus server on iOS*: two brains.

**Cost:** a host to run (a small VM or container), plus operations and privacy responsibility.

### M-002: Kotlin Multiplatform + Compose Multiplatform for shared client logic and UI; native code for OS integrations
**Decision.**
- **Shared (`shared/commonMain`):** the API client, models, offline queue, the Gemini Live client and the whole UI (design system, Coach, Today/Up next, Settings, call screen).
- **Native per platform:**
  - push (FCM, APNs);
  - incoming call (CallStyle/Telecom; PushKit + CallKit);
  - audio sessions;
  - health (Health Connect, HealthKit);
  - places (geofences, CLMonitor);
  - the Android-only sensors.

**Why:**
- Reliability lives in the native integrations, so they stay native and first-class.
- Our existing code is Kotlin and Compose, and Compose Multiplatform for iOS has been stable since 1.8 (current 1.12.1).

**Rejected:**
- **Flutter / React Native:** a full rewrite, and the call and push paths would depend on third-party plugins (`flutter_callkit_incoming` stalled and was forked; `react-native-callkeep` is unmaintained). React Native has no Firebase AI Logic SDK.
- **Fully separate native apps:** two UIs to keep in sync for little gain. Revisit if the Compose UI feels wrong on iPhone in testing (iOS could then get SwiftUI screens over the same KMP client).

### M-003: Push is the wake-up channel
**Decision.**
- **Android:** FCM high-priority data messages carry "message" and "call" events. The app shows them with the existing Notifier / CallManager.
- **iOS:** APNs alert pushes for messages, using `timeSensitive` only when the agent marks the message as time-critical. APNs VoIP pushes are sent **only** when the agent decided to call.
- The backend scheduler holds exact timers for `next_check` and intention windows.

**Why:** it is the only real-time wake-up both OSes honour. It replaces WorkManager as the agent clock, though WorkManager still drives sensor syncs.

### M-004: Calls use the system call UI, with ephemeral Live tokens
**Decision.**
- **iOS:** PushKit → `reportNewIncomingCall` immediately → CallKit UI → on answer, the audio session is activated → `AVAudioEngine` with voice processing → Gemini Live using a server-minted ephemeral token.
- **Android:** unchanged UX (CallStyle + full-screen intent; Core-Telecom later per V-011), now with an ephemeral token.
- **Both:** the transcript goes back to the backend for `ingestCall`.

**Why:** native lock-screen call UI on iOS, and no Gemini key on phones (Google's recommendation, now possible with a backend).

**Note:** CallKit is unavailable in China. Calls appear in Recents unless `includesCallsInRecents = false`.

### M-005: Context is platform-dependent and labelled
**Decision.** iOS sends what it can: HealthKit (steps at most hourly), places, calendar and chat. It cannot provide food-order/UPI notification detection or screen time. The agent sees those sources as unknown; no fake parity.

### M-006: Distribution
**Android:** keep the GitHub APK + in-app updater. Add an AAB and an optional Play internal track. Register for developer verification before the 2027 global rollout (a free limited distribution account covers up to 20 devices).

**iOS:** TestFlight, internal testing, via GitHub Actions on macOS runners:
- Needs the Apple Developer Program ($99/year).
- Builds expire after 90 days, so CI must keep shipping.
- No self-update. The app shows "update available" from the backend's `min_client_version` and opens TestFlight.

### M-007: Phased migration, Android never broken
**Decision.** Order:
1. Backend, while Android stays on the local engine behind a switch.
2. Android as a client.
3. Shared KMP UI.
4. iOS.

The local-engine mode is removed only after the server mode has run for a real week. Each phase ships to the user.
