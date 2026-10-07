# Research: Android + iPhone for the FitCoach agent (2026-10-07)

> **Status: DROPPED (2026-10-07).** The user decided to stay Android-only. This file is kept as a reference only; nothing here is planned. See D-044 in `../DECISIONS.md`.

**Question:** can the companion app run on both Android and iPhone? It must keep:
- background context collection;
- proactive notifications;
- incoming-call-style voice;
- live voice;
- chat;
- the agent's plan, with mute and pause;
- memory synced with a backend.

**Which approach makes the agent reliably proactive on both platforms?**

**Sources:** official Apple, Android, Google and GitHub documentation read on 2026-10-07, plus Apple Developer Forums answers from Apple staff and a few dated third-party sources (marked).

---

## 1. The finding that decides the architecture

FitCoach today is "the phone is the backend": the agent loop, memory, Telegram polling and scheduling all run inside the Android app. **That cannot work on iPhone:**

| Need | Android (what we do today) | iPhone |
|---|---|---|
| Run the agent every ~15 min | WorkManager periodic work (deferred in Doze; OEM killers) [A4] | **No equivalent.** `BGAppRefreshTask` runs "when [the system] thinks your app needs a refresh. You have virtually no control over this"; rarely used apps may get none [I3] |
| Wake on our own decision ("look again at 20:00") | Alarms / WorkManager (approximate) | No reliable timer. A **server push** is the only dependable wake-up |
| Long-poll Telegram in a foreground service | Yes (`specialUse` foreground service) | No. iOS has no general-purpose foreground services |
| Ring the phone for a coach call when the app is not running | CallStyle notification from the background, plus full-screen intent [A1][A2] | Only through **PushKit VoIP push → CallKit**, which a **server** must send. Every VoIP push must be reported as a call, or the app is terminated (enforced for the iOS 26 SDK) [I1] |
| Proactive notification decided by the agent at that moment | Posted locally after the agent decides | Only a **server-sent APNs push** works while the app is suspended or terminated. Local notifications must be scheduled in advance, which is a static schedule, the thing we explicitly reject |

**Conclusion:** on iOS the agent's brain must live on a server that can push. The phone becomes a sensor, a UI and a voice client.

Running two brains (on-device for Android, server for iOS) would double the logic and the bugs. One backend for both platforms is the reliable design. It also fixes known Android weaknesses: ColorOS killing the agent, Doze delaying decisions, and the Gemini key stored on the device.

## 2. iOS limits (iOS 26 shipped; iOS 27 released 2026-09-14 [I10])

- **Background execution:**
  - `BGAppRefreshTask` and `BGProcessingTask` are opportunistic. "earliestBeginDate" is not a guarantee, and processing tasks usually run overnight on power [I3].
  - The system budgets background time per app, and the budget is undocumented [I4].
- **Push notifications:**
  - APNs alert pushes are the way to deliver an agent's message at the moment it decides.
  - Interruption levels:
    - `passive`;
    - `active`;
    - `timeSensitive`, which "breaks through system notification controls" (Focus);
    - `critical`, which bypasses mute and needs a special entitlement [I5].
- **Incoming-call UX (CallKit + PushKit):**
  - The server sends a VoIP push. The app must call `reportNewIncomingCall` immediately, and the system shows the native full-screen incoming-call UI, even on the lock screen and when the app was terminated.
  - Since the iOS 26 SDK, any VoIP push not reported to CallKit terminates the app. The last unrestricted PushKit entitlement is gone [I1].
  - So VoIP pushes may be sent **only** when the agent has decided to call. This matches our design.
  - Third-party reports describe this exact flow for AI-agent calls [T1].
  - CallKit is not available in mainland China (Apple forum [I2]).
- **AlarmKit (iOS 26+):**
  - Alarms and timers that override Focus and silent mode; needs user authorisation [I6][I7].
  - Schedules are fixed times or countdowns, so it is **not** a channel for agent messages. At most it could carry a user-requested "wake me" reminder. Not adopted.
- **Microphone and audio:**
  - Once the user answers a CallKit call, the system activates the app's audio session, so recording works on the lock screen. This is the standard VoIP path.
  - Starting the microphone on our own from the background is not possible. (Android has the same rule, A3.)
- **Health data:**
  - HealthKit background delivery (`HKObserverQuery` with `enableBackgroundDelivery`) wakes the app when samples change, but frequency is best-effort.
  - **Step count is delivered at most hourly**, even with `.immediate` (Apple, April 2026) [I4].
- **Location:**
  - Background location needs the Location background mode and a `CLServiceSession`. The system queues updates while the app is suspended and delivers them when it runs; monitoring must be restarted after the app launches [I8].
  - Region (place) monitoring through `CLMonitor` is the iOS counterpart of our geofences.
- **Not available on iOS at all:**
  - reading other apps' notifications (our Swiggy, Zomato and UPI detection);
  - usage stats / screen time without the Family Controls entitlement;
  - self-updating the app;
  - polling a bot.
- **Distribution:**
  - TestFlight, push notifications, CallKit and HealthKit need the **Apple Developer Program, $99/year**.
  - Free accounts get 7-day provisioning profiles and no TestFlight [I9].
  - iOS apps update only through TestFlight or the App Store.

## 3. Android limits (Android 17, API 37)

- **Background audio hardening (Android 17) [A5]:**
  - Playback, audio focus and volume changes from the background fail silently unless the app has a visible activity or runs a foreground service (FGS).
  - Apps targeting API 37 need an FGS with while-in-use capabilities.
  - VoIP apps that follow the telecom guidance are "unlikely to be impacted".
  - Our call already runs a microphone FGS started from the Answer tap, so it complies.
- **Other API 37 changes [A6]:**
  - Background activity launch rules are stricter (`MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`).
  - SMS OTP messages are delayed 3 h; we do not read SMS.
- **Rules from earlier versions that still apply [A1–A4]:**
  - A background app cannot start a microphone FGS, except after a notification interaction.
  - Full-screen intents are revoked by Play for non-calling apps (sideloaded apps keep them).
  - Doze defers WorkManager.
  - High-priority FCM is the sanctioned real-time wake-up.
- **Developer verification:**
  - Since 2026-09-30, apps installed outside Play must come from a verified developer on certified devices in Brazil, Indonesia, Singapore and Thailand. Global rollout (including India) is in 2027.
  - A free **limited distribution account** allows up to 20 devices; there is also an "advanced flow" for power users [A7].
  - Our GitHub-APK sideload model keeps working in India for now, but needs registration before 2027.

## 4. Live voice on both platforms

- **Gemini Live** is a WebSocket protocol, so it is platform-neutral. Our Android `LiveSession` is pure Kotlin.
- **Firebase AI Logic** ships SDKs for Swift, Kotlin, Web, Flutter (Dart) and Unity, but **not React Native** [F1]. It also needs a Firebase project and App Check (see `docs/voice/RESEARCH.md`).
- **iOS audio:** use `AVAudioEngine` with voice processing (echo cancellation) inside the CallKit-activated session. Forum reports of static on iOS come from sample-rate mismatches, so the 24 kHz output and 16 kHz input conversion must be explicit [T2].
- **A backend enables ephemeral tokens:** the server mints a short-lived Live token per call, so no Gemini key lives on any phone. This is Google's recommended practice and was impossible in our no-server design (docs/voice V-004).

## 5. Framework options

| | Native (Swift iOS + existing Kotlin Android) | **Kotlin Multiplatform + Compose Multiplatform** | Flutter | React Native |
|---|---|---|---|---|
| Status | Mature | Compose for iOS stable since 1.8 (May 2025); current 1.12.1 (2026-09-22), iOS 14+ [K1][K2] | Mature | Mature |
| Reuse of our code | Android 100%; iOS 0% | Engine and client logic shared; the Compose UI moves to commonMain with little change | Rewrite everything in Dart | Rewrite everything in JS/TS |
| CallKit / PushKit / ConnectionService | Native, first-class | Native Swift and Kotlin in platform source sets (no plugin dependency) | Plugins; the original `flutter_callkit_incoming` stalled and a community fork took over (3.1.2, May 2026) [T3] | `react-native-callkeep` "isn't actively maintained" and uses old ConnectionService [T4] |
| HealthKit / Health Connect | Native | Native per platform | `health` plugin (third party) | Third-party modules |
| Gemini Live client | Write twice | Shared Kotlin (Ktor WebSocket) | Firebase AI Logic Dart SDK | No Firebase AI Logic SDK |
| Look and feel on iOS | Fully native | Compose-rendered; good, but not UIKit-native | Own rendering | Native widgets |
| Maintenance | Two UIs | One UI, two thin native layers | One UI, plugin risk on the critical paths | One UI, plugin risk on the critical paths |

The parts that decide reliability are push wake-ups, CallKit/Telecom, audio sessions and health or location access. **They are native in every option.**
- Flutter and React Native only change the UI layer, and they add plugin risk exactly on those critical paths.
- KMP keeps the native parts native while sharing the logic and UI we already have in Kotlin and Compose.

## 6. CI/CD on GitHub Actions

- **Android:** already automated (test, sign, release the APK, in-app update). Optional additions:
  - an AAB plus a Play Console upload (internal track), which needs a $25 Play developer account;
  - developer-verification registration before 2027 (§3).
- **iOS:**
  - GitHub-hosted macOS runners are free for public repositories (private repos: $0.062/min) [G2].
  - Signing: GitHub documents importing a base64 `.p12` and a provisioning profile into a temporary keychain [G1]; alternatively fastlane `match` with an App Store Connect API key.
  - Build with `xcodebuild`, then upload to TestFlight (fastlane `pilot` or `altool`).
  - Internal testers need no App Review. Builds expire after 90 days [I11].
- **Versioning:** one `version.properties` → Android `versionName`/`versionCode` and iOS `CFBundleShortVersionString`/`CFBundleVersion` (run number).
- **Updates:**
  - Android keeps the GitHub in-app updater.
  - iOS gets updates from TestFlight or the App Store. The app can only *tell* the user (backend `min_version`) and deep-link to TestFlight; self-updating is not allowed.
- **Backend:** the same workflow builds a container and deploys it.

## 7. Answers to the product questions

- **Both platforms with the same core experience?** Yes for chat, voice calls, plan and status, mute/pause, notifications and memory. **Context is richer on Android**: iOS cannot read food-order or UPI notifications or screen time, and steps arrive at most hourly.
- **Incoming-call experience?**
  - iOS: better than Android. CallKit is the real system call UI on the lock screen, but it needs a server VoIP push.
  - Android: CallStyle plus a full-screen intent (sideload) or heads-up.
- **Autonomous and proactive?** Only with a server: on iOS no on-device approach is reliable, and on Android the server removes Doze and OEM-killer delays.

---

## Sources
- [I1] Apple Forums, "CallKit and PushToTalk related changes in iOS 26": https://developer.apple.com/forums/thread/787466
- [I2] Apple Forums, "Disabling CallKit for China apps": https://developer.apple.com/forums/thread/103083
- [I3] Apple Forums (DTS), "How accurate is BGTaskScheduler?": https://developer.apple.com/forums/thread/725675
- [I4] Apple Forums (Apple DTS, April 2026), HealthKit observer delivery frequency: https://developer.apple.com/forums/thread/823699
- [I5] UNNotificationInterruptionLevel: https://developer.apple.com/documentation/usernotifications/unnotificationinterruptionlevel
- [I6] AlarmKit: https://developer.apple.com/documentation/alarmkit
- [I7] Scheduling an alarm with AlarmKit: https://developer.apple.com/documentation/alarmkit/scheduling-an-alarm-with-alarmkit
- [I8] Handling location updates in the background: https://developer.apple.com/documentation/corelocation/handling-location-updates-in-the-background
- [I9] Apple membership comparison: https://developer.apple.com/support/compare-memberships/
- [I11] TestFlight overview (90-day builds, internal testing without review): https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview
- [I10] iOS 27 release date (MacRumors): https://www.macrumors.com/2026/06/05/ios-27-release-date-how-to-install-beta/
- [A1] CallStyle notifications: https://developer.android.com/develop/ui/views/notifications/call-style
- [A2] Full-screen intent limits: https://source.android.com/docs/core/permissions/fsi-limits
- [A3] FGS background start / while-in-use: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- [A4] Doze and App Standby: https://developer.android.com/training/monitoring-device-state/doze-standby
- [A5] Android 17 background audio hardening: https://developer.android.com/about/versions/17/changes/bg-audio
- [A6] Android 17 behavior changes (targeting 37): https://developer.android.com/about/versions/17/behavior-changes-17
- [A7] Android developer verification: https://developer.android.com/developer-verification
- [F1] Firebase AI Logic: https://firebase.google.com/docs/ai-logic
- [K1] Compose Multiplatform compatibility (1.12.1, 2026-09-22): https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- [K2] Compose Multiplatform 1.8.0, iOS stable: https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/
- [G1] GitHub: Signing Xcode applications: https://docs.github.com/en/actions/how-tos/deploy/deploy-to-third-party-platforms/sign-xcode-applications
- [G2] GitHub Actions runner pricing: https://docs.github.com/en/billing/reference/actions-runner-pricing
- [T1] CallSphere, PushKit VoIP push for AI inbound calls (third party, updated 2026-09-20): https://callsphere.ai/blog/vw4e-ios-pushkit-voip-push-ai-inbound-2026
- [T2] Google AI forum, static audio from Gemini Live on iOS AVAudioEngine: https://discuss.ai.google.dev/t/static-audio-output-from-gemini-live-api-google-genai-sdk-on-ios-with-avaudioengine/81881
- [T3] flutter_callkit_incoming_maintained (pub.dev): https://pub.dev/packages/flutter_callkit_incoming_maintained
- [T4] Software Mansion, react-native-callkeep alternatives (2026-08-26): https://swmansion.com/blog/react-native-callkeep-alternative-2026/
