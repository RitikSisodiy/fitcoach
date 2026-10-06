# Progress

Newest first. One dated block per session: what changed, how it was verified, and what's next.

## Current state (2026-10-06)
- **Repo:** github.com/RitikSisodiy/fitcoach (public).
  - Every push to master that changes `android/**` → CI tests, builds, signs and publishes GitHub Release `v1.1.N`.
  - Latest: see the Releases page.
- **In-app updates** were verified on the emulator against real GitHub releases (1.1.2 → 1.1.3 → 1.1.4): banner, download, system confirm, installed.
- **Not yet installed on the user's phone.** The 1.0.0 APK sent earlier was signed with the debug key, so uninstall it once and install the latest GitHub release. From then on the app updates itself.
- **Tests:** 11 engine + 9 service + 1 live Gemini test, all green.
- **Emulator:**
  - Real-key chat works.
  - The worker sent the evening recap on its own.
  - The release build shows no crashes.
- **Python Telegram bot:** retired and not running (the laptop rebooted; there is no need to restart it).

## Next
1. The user installs the latest GitHub release APK and completes the Setup checklist (`docs/ANDROID.md`).
2. Run one real week and review the Today-tab decision log.
3. Fix whatever breaks: ColorOS killing the worker, the bank SMS formats, geofence reliability.

## Log
- **2026-10-06 (git + CI + in-app updates).**
  - Changes:
    - Created the public repo.
    - Added a GitHub Actions workflow that tests, builds, signs and releases the APK on push to master.
    - Version is `MAJOR.MINOR` from `version.properties` plus the CI run number.
    - Added the in-app updater (notification, banner, download, PackageInstaller).
  - Fixed two problems found while verifying:
    - The `.gitignore` entry `data/` hid source folders, which broke the first CI build. Paths are now anchored.
    - App open shared the 6-hour update throttle. App open now checks with a 10-minute throttle; the background check runs every 3 h.
  - Verified:
    - CI runs green, releases v1.1.2–v1.1.5.
    - Emulator in-app update from one real release to the next.
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
