# Android App (v1.0)

Decided 2026-10-06 with the user:
- native Kotlin app
- in-app chat
- no server
- target phone: OnePlus/Oppo/Realme/Vivo (ColorOS/OxygenOS)

## Why

| Problem | Effect | Server-based design (v0.3) | Android app |
|---|---|---|---|
| Automatic signals needed 2 third-party phone apps, a tunnel and an always-on server | The lazy user never set them up, so nothing was detected | ✗ | ✓ |
| Full phone sensors were unavailable | Only data forwarded through Health Connect Webhook | ✗ | ✓ |

The Android app reads every signal on the device, keeps every decision on the device, and only calls Gemini over HTTPS. There is no server, no Telegram and no tunnel.

## Signals (all opt-in, each with its own permission)

| Signal | API | What the coach gets | Privacy |
|---|---|---|---|
| Steps, sleep, weight, workouts | Health Connect (`connect-client`), background read | Daily steps, hourly steps, sleep hours, weigh-ins, walk sessions | Numbers only |
| Moving vs still | Activity Recognition Transition API | Walk started/ended, long still periods (sitting) | No location |
| Places | Geofencing, only on places the user saves ("chess club", "office canteen") | Arrival/leave events, so the coach can act **before** the snack | Only saved places; never a track |
| Food orders, UPI payments | NotificationListenerService (Swiggy, Zomato, GPay, PhonePe, Paytm, bank SMS apps) | Orders and small payments become inferred events | Body parsed in memory; only amount, time, merchant stored |
| Meetings | CalendarContract.Instances | Busy intervals, free slots, long days | Times only, never titles |
| Phone use | UsageStatsManager screen events | Continuous screen-on time, late-night use | One aggregate number; never the app list |

## Architecture

```
            ┌──────────────────────── phone ────────────────────────┐
 sensors →  │ Receivers / NotificationListener / Workers             │
            │        │ (write events)                                │
            │        ▼                                               │
            │   SQLite (same schema as v0.3)  ◀── CoachService ──▶ Gemini REST (HTTPS)
            │        ▲                │ tick (WorkManager 15 min,    │
            │        │                │ + immediately on events)      │
            │   Compose UI: Chat · Today · Setup     Notifications with action buttons
            └────────────────────────────────────────────────────────┘
```

- **Engine.** A Kotlin port of the Python engine (`coach/`), with the same tables, policies, prompts, grounding and quota routing. The Python project remains the reference implementation and simulator.
- **Ticks.** WorkManager runs a tick every 15 minutes, and each tick first syncs Health Connect, the calendar and screen time. A geofence arrival triggers an immediate tick. Activity transitions and food notifications are stored at once and acted on in the next tick.
- **Notifications.** Proactive messages are posted as notifications with the same buttons (Done / Smaller / Later / Skip, All usual / Off-plan / Skip, …). Tapping opens the chat.
- **Chat.** Text, voice (MediaRecorder AAC → `audio/aac`) and photos (camera/photo picker → JPEG).
- **Storage.** App-private SQLite. The Gemini key lives in app-private preferences, entered once in Setup.

## OnePlus/Oppo (ColorOS) reliability

- dontkillmyapp.com rates these brands 5/5 severity, and there is no developer-side fix.
- The Setup screen walks the user through four settings, each with a button that opens the right screen:
  1. Battery → Don't optimize.
  2. Auto-launch on.
  3. Lock the app in Recents.
  4. Allow restricted settings. This is required for sideloaded apps before notification access or usage access can be granted.
- Opening the app always runs a tick, so a killed worker catches up on the next app open.

## Build

- **Toolchain:**
  - AGP 9.4 (built-in Kotlin), Kotlin 2.4.20, Gradle 9.8.0, Compose BOM 2026.09.00
  - compileSdk 37 (Compose 1.12 requires it), targetSdk 35, minSdk 28 (Android 9+)
  - JDK 21
- **Where to build:** on the laptop. The cloud sandbox cannot reach Google Maven.
  - `~/tools/jdk-21` and `~/Android/Sdk` are installed by `android-toolchain-setup.sh` (user-local, no sudo).
  - `fitcoach-build.sh assembleDebug testDebugUnitTest` builds the APK and runs the tests.
- **Tests:**
  - `EngineTest`: validator, safety, nutrition, notification parser, Gemini routing and quota handling.
  - `ServiceTest`: scripted LLM covering recap buttons, gates, place arrivals, walk auto-complete, UPI flow and LLM-down fallbacks.
  - `LiveGeminiTest`: a real Gemini lazy-user day. It runs only when `GEMINI_API_KEY` is set and uses about 8 Flash-Lite calls.
- **Releases:** CI publishes signed APKs to GitHub Releases on every push to master (D-031). After the first install, the app updates itself.
- **Install:**
  - `adb install -r app-debug.apk`, which also avoids the restricted-settings friction.
  - Or open the APK on the phone and allow "Install unknown apps".

## First-run checklist (Setup tab)

1. Paste the Gemini API key.
2. Allow:
   - Notifications
   - Health Connect (steps, sleep, weight, exercise, plus background read)
   - Physical activity
   - Calendar
   - Location, then "Allow all the time"
   - Microphone
3. Turn on notification access for FitCoach. On "Restricted setting": App info > ⋮ > Allow restricted settings.
4. Turn on usage access for FitCoach.
5. Set Battery to "Don't optimise" or "Allow background activity", turn on auto-launch, and lock FitCoach in Recents.
6. Save places by standing at them (gym, office, chess club).
