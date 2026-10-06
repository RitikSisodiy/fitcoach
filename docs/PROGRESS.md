# Progress

Newest first. One dated block per session: what changed, how it was verified, and what's next.

## Current state (2026-10-06)
- **FitCoach 1.0.0 Android APK** (release build with R8, 3.4 MB) has been built and sent to the user. It is not yet installed on the real phone.
- **Tests:** 11 engine + 9 service + 1 live Gemini test, all green.
- **Emulator:**
  - Real-key chat works.
  - The worker sent the evening recap on its own.
  - The release build shows no crashes.
- **Python Telegram bot:** retired and not running (the laptop rebooted; there is no need to restart it).

## Next
1. The user installs the APK and completes the Setup checklist (`docs/ANDROID.md`).
2. Run one real week and review the Today-tab decision log.
3. Fix whatever breaks: ColorOS killing the worker, the bank SMS formats, geofence reliability.

## Log
- **2026-10-06 (git + CI + in-app updates).**
  - Changes:
    - Created the public repo.
    - Added a GitHub Actions workflow that tests, builds, signs and releases the APK on push to master.
    - Version is `MAJOR.MINOR` from `version.properties` plus the CI run number.
    - Added the in-app updater (notification, banner, download, PackageInstaller).
  - Verified with the unit tests and a CI run (details below the current state).
- **2026-10-06 (Android v1.0).**
  - Changes:
    - Ported the engine to Kotlin.
    - Added on-device sensors, the UI, notifications and the WorkManager tick.
    - Added the arrival candidate.
    - Fixed three issues found by live Gemini: the rule trigger is now inferred from the place name, usual meals are recovered when volunteered, and spurious mode changes are blocked.
    - Added a release build with R8.
  - Verified with the unit, service and live tests, plus the emulator smoke, end-to-end and release checks.
- **2026-10-06 (v0.3).**
  - Changes: Gemini free-tier routing, grounded extraction, budget reservation.
  - Verified with the live Telegram and Gemini runs (`SIMULATION.md`).
- **2026-10-05 (v0.1–v0.2).** Python engine, proactive brain, simulator. 158 pytest tests.
