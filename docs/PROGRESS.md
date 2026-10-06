# Progress

Newest first. One dated block per session: what changed, how it was verified, and what's next.

## Current state (2026-10-07, v2.0)
- **v2 autonomous agent is implemented** (D-032…D-037, `docs/AGENT.md`, audit in `docs/AUDIT.md`):
  - the LLM decides act, timing, channel and message, and learns from outcomes;
  - memory tiers;
  - Telegram on the phone;
  - the dashboard.
- **Tests:** 12 engine, 16 service and 3 Telegram tests, plus 2 live tests (real Gemini 3-day agent run, real Telegram send). All green.
- **Real end-to-end (emulator, real Gemini, real Telegram bot):**
  1. The agent stayed silent at midnight and gave its reason.
  2. In the morning, on its own, it sent a question with quick replies to the user's Telegram.
  3. The user tapped "Weight loss" in Telegram.
  4. The goal was saved to the profile, the coach replied in Telegram, and the intervention was marked answered after 3 min.
  5. Both the app chat and the dashboard show the same conversation.
- **Release:** CI publishes v2.0.N on push. A user on 1.1.x gets it as an in-app update.

## Next
1. The user updates to v2.0.x, pairs Telegram in Setup and grants the permissions. Then a real week.
2. Review the dashboard decision log and coach insights, and tune `assets/prompts/agent_system.txt` from real misses.
3. Watch: near-repeated messages, timing quality, ColorOS killing the polling service.

## Log
- **2026-10-07 (v2.0 autonomous agent).**
  - Changes:
    - Audited v1.1 honestly: rules plus LLM wording, fixed windows, template fallbacks, scripted buttons.
    - Replaced Brain with the Agent loop.
    - Added memory tiers, outcome evaluation and daily reflection.
    - Telegram long-polling on the phone; quick replies across all channels.
    - Dashboard; quiet-hours setting; Python archived; prompts in assets.
  - Verified:
    - `LiveAgentTest` transcript reviewed. The agent chose silence for work hours, timed messages after office, noticed ignored messages and backed off. Reflection wrote an evidence-backed insight. The reply recalled the goal and the temporary knee pain.
    - Emulator end-to-end with the real Telegram user.
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
