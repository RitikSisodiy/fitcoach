# Architecture: one agent backend, two native-capable clients

> **Status: DROPPED (2026-10-07).** The user decided to stay Android-only. This file is kept as a reference only; nothing here is planned. See D-044 in `../DECISIONS.md`.

Evidence: [RESEARCH.md](RESEARCH.md). Decisions: [DECISIONS.md](DECISIONS.md) (M-001…).

## 1. Shape

```
                        ┌──────────────────────── FitCoach backend (Kotlin/JVM, Ktor) ────────────────────────┐
  phone events  ──────▶ │ ingest API ─▶ store (SQLite/Postgres) ─▶ engine (today's CoachService, Agent, Plan,   │
  chat / call transcript│              memory, plan/intentions        extraction, reflection - moved from Android) │
                        │ scheduler: exact timers for agent next_check and intention windows (no Doze)          │
  Telegram ◀──webhook──▶│ Telegram bot (webhook, no phone polling)                                             │
                        │ push: FCM (Android) · APNs alert (iOS) · APNs VoIP (iOS calls only)                   │
                        │ Live: mints ephemeral Gemini Live tokens per call (no key on phones)                  │
                        └───────────────▲───────────────────────────────┬──────────────────────────────────────┘
                                        │ HTTPS (REST + JSON)           │ push
                 ┌──────────────────────┴───────────┐        ┌──────────▼────────────────────────────┐
                 │ Android app (Kotlin)              │        │ iOS app (Swift host + shared Kotlin)    │
                 │ shared: KMP client + Compose UI   │        │ shared: same KMP client + Compose UI    │
                 │ native: Health Connect, geofence, │        │ native: HealthKit bg delivery, CLMonitor │
                 │ activity, notif. listener, usage, │        │ places, EventKit, APNs, PushKit+CallKit, │
                 │ FCM, CallStyle/FSI (+Core-Telecom)│        │ AVAudioEngine voice processing           │
                 └───────────────────────────────────┘        └─────────────────────────────────────────┘
                              both: Gemini Live WebSocket (shared Kotlin LiveSession on Ktor) with ephemeral token
```

## 2. Responsibilities

| Concern | Where | Why |
|---|---|---|
| Agent loop: observe → reason → intention → re-evaluate → act | Backend | Needs dependable timers and push on both platforms (RESEARCH §1) |
| Memory, plan, outcomes, reflection, dashboard numbers | Backend DB (single source of truth) | Shared by both phones and Telegram |
| Proactive text | Backend → FCM / APNs (`timeSensitive` when the agent marks it urgent) | Real-time delivery when the app is not running |
| Coach call | Backend → FCM data (Android: CallStyle) / APNs VoIP (iOS: CallKit) | The only way to ring a terminated iOS app; VoIP pushes are sent only for real calls |
| Live conversation | Phone ↔ Gemini Live directly, using a server-minted ephemeral token | Lowest latency; no long-lived key on the device |
| Transcript → memory | Phone uploads the transcript; backend `ingestCall` | Same pipeline as today |
| Context collection | Phones, native per OS, uploaded as observations | Only the phone can see health, places and notifications |
| Mute / pause / plan UI | Shared UI → backend API | Enforced by backend code |
| Chat | Shared UI → backend API; Telegram via webhook | One conversation |
| Offline | Phone queues uploads and shows cached state | The backend is authoritative |

## 3. What each platform contributes (context)

| Signal | Android | iOS |
|---|---|---|
| Steps, sleep, weight, workouts | Health Connect (sync on tick and on open) | HealthKit background delivery (steps at most hourly) |
| Places | Geofences (Play Services) | `CLMonitor` region conditions |
| Walk / run | Activity Recognition | HealthKit workouts / CoreMotion when the app runs |
| Food orders / UPI payments | Notification listener | Not possible (the user tells the coach or shares a screenshot) |
| Screen time | Usage stats | Not possible without Family Controls; dropped |
| Calendar | Calendar provider | EventKit (when the app runs) |

The agent's SITUATION already marks data as observed / unknown. On iOS some sources are simply "unknown", and the prompts already handle that.

## 4. Code structure (target)

```
/core        Kotlin/JVM: engine (moved from android/app/.../engine), prompts, Store interface
/server      Ktor app: REST API, scheduler, Telegram webhook, push senders, Live token minting, SQLite→Postgres
/shared      KMP (commonMain): API client (Ktor), models, offline queue, LiveSession (Ktor WebSocket),
             Compose Multiplatform UI (Coach, Today/Up next, Settings, Call screen, design system)
/androidApp  Android host: platform services (Health Connect, geofences, listener, FCM, CallStyle, audio)
/iosApp      Xcode project (Swift): hosts shared UI; APNs, PushKit+CallKit, AVAudioEngine, HealthKit, CLMonitor
```

The engine needs one real change to move: `Store` uses Android `SQLiteDatabase`. It becomes an interface with a JDBC implementation; all other logic is plain Kotlin plus `org.json`.

## 5. Security and privacy
- **Phones authenticate to the backend** with a device token issued at pairing (single user: a QR or code shown on the server).
- **The Gemini key and Telegram token live only on the server.** Phones get Live ephemeral tokens with a 1-use, 1-minute start window.
- **Health data now leaves the phone** to our own backend; summaries already went to Gemini. This is stated plainly in the app's privacy text. TLS everywhere; DB encryption at rest on the host.

## 6. Delivery pipeline (GitHub Actions)

| Workflow | Runner | Output |
|---|---|---|
| `server.yml` | ubuntu | tests → container image → deploy (target host chosen in TASKS MP-0) |
| `android-release.yml` (existing, extended) | ubuntu | tests → signed APK (GitHub Release + in-app updater) and AAB (optional Play internal track) |
| `ios-release.yml` | macOS (free for public repos) | tests → `xcodebuild archive` with signing (API key + `match`, or a p12/profile from secrets) → TestFlight |

All three read `version.properties`. iOS build number = run number. The backend exposes `min_client_version`, so both apps can prompt for an update: Android downloads it, iOS deep-links to TestFlight.

## 7. Why not keep "the phone is the backend"
- **iOS cannot run it** (RESEARCH §1).
- **Two brains would diverge.** Android would keep a local agent and iOS a server agent, with double the testing.
- **The server also fixes Android issues:** ColorOS killing the polling service, Doze-delayed decisions, the API key on the device, and only one device being able to poll Telegram.
