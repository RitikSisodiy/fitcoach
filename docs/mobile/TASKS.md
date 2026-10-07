# Mobile platform tasks (plan only — not started)

Follows [ARCHITECTURE.md](ARCHITECTURE.md) and [DECISIONS.md](DECISIONS.md). Every task has a test. Phases ship in order (M-007).

## Phase 0: decisions and accounts (user)
| ID | Task | Done when |
|---|---|---|
| MP-0a | Choose the backend host: a small VM or container service, with a domain and TLS | Host reachable over HTTPS |
| MP-0b | Apple Developer Program ($99/year); create the App ID with Push, CallKit/VoIP, HealthKit and Location capabilities; App Store Connect API key | Secrets in GitHub |
| MP-0c | Firebase project for FCM (free); Android app registered | `google-services.json` available to CI |
| MP-0d | Android developer verification: limited distribution account (before 2027) | Registration done |

## Phase 1: backend (Android keeps working locally)
| ID | Task | Test |
|---|---|---|
| MP-1 | Split the Gradle build: `/core` (engine + prompts, JVM) out of `android/app`; the `Store` interface with Android and JDBC implementations | All existing unit tests pass against both stores |
| MP-2 | `/server` (Ktor): pairing and device tokens; REST for messages, events, dashboard, plan, mute/pause, calls | API tests (in-memory DB); auth rejects unknown devices |
| MP-3 | Server scheduler: exact timers for `next_check` and intention windows; replaces WorkManager as the agent clock | Unit: plan window → wake at the start; restart restores timers |
| MP-4 | Telegram webhook on the server (stop phone polling in server mode) | Live: text/photo/voice through the webhook (port the TelegramTest live cases) |
| MP-5 | Push senders: FCM (data) and APNs (alert and VoIP, token auth) | Live: push reaches a test device; VoIP only from the call path (unit check) |
| MP-6 | Live ephemeral token endpoint (1 use, 1 min start, locked to the Live model and config) | Live: the client opens Live with the token; the key is never on the device |
| MP-7 | `server.yml`: tests → image → deploy; secrets from GitHub | Deploy on push; health check green |

## Phase 2: Android as a client
| ID | Task | Test |
|---|---|---|
| MP-8 | Server mode switch in Settings (local ↔ server), pairing UI | Emulator: pair, chat round-trip via server |
| MP-9 | Sensor uploads (Health Connect, geofences, listener, usage) as observations with an offline queue | Robolectric queue test; emulator airplane-mode replay |
| MP-10 | FCM receive: message → Notifier; call → CallManager (ephemeral token) | Emulator: server-decided call rings and connects |
| MP-11 | A real week on the user's phone in server mode; then remove local mode | PROGRESS log; no missed decisions |

## Phase 3: shared client (KMP + Compose Multiplatform)
| ID | Task | Test |
|---|---|---|
| MP-12 | `/shared`: models, Ktor API client, offline queue, LiveSession ported from OkHttp to Ktor WebSocket | commonTest; LiveVoiceTest on JVM via shared code |
| MP-13 | Move the design system and screens (Coach, Today/Up next, Settings, Call) to commonMain; platform hooks via interfaces | Android screenshots unchanged (compare the emulator shots) |

## Phase 4: iOS app
| ID | Task | Test |
|---|---|---|
| MP-14 | `/iosApp` Xcode project hosting the shared UI; pairing; chat; Today; Settings | Simulator smoke test (XCUITest launch) |
| MP-15 | APNs registration + notification actions (quick replies) → backend | Real device: agent message arrives while the app is terminated |
| MP-16 | PushKit + CallKit incoming call → audio session → `AVAudioEngine` with voice processing → Live with an ephemeral token → transcript upload | Real device, locked: ring, answer, talk, end; transcript in memory |
| MP-17 | HealthKit background delivery (steps, sleep, weight, workouts) → observations | Real device: new steps reach the backend within about an hour |
| MP-18 | CLMonitor places, EventKit calendar | Real device: arriving at a saved place creates an observation |
| MP-19 | Mute/pause and Up next parity; `min_client_version` banner → TestFlight link | UI test |

## Phase 5: release automation
| ID | Task | Test |
|---|---|---|
| MP-20 | `ios-release.yml` on a macOS runner: signing (API key + match, or p12/profile secrets in a temp keychain), archive, TestFlight upload; version from `version.properties` | Tag → build appears in TestFlight |
| MP-21 | Android: add an AAB artifact; optional Play internal-track upload | AAB attached to the release; Play upload green (if enabled) |
| MP-22 | Docs: CONTEXT, AGENT (backend), CHANGELOG; lessons in CODING_RULES | Docs match the code |

**Estimated effort** (one developer with AI help): Phase 1 ≈ 1–2 weeks, Phase 2 ≈ 1 week, Phase 3 ≈ 1 week, Phase 4 ≈ 2–3 weeks (real-device work), Phase 5 ≈ 2–3 days.
