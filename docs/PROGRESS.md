# Progress

Newest first. One dated block per session: what changed, how it was verified, and what's next.

## Current state (2026-10-07, v2.1)
- **v2.1:**
  - **Voice calls:** the agent decides when to call. Calls use Gemini Live (`gemini-3.8-live`, with a fallback model).
  - **Telegram photo and voice** are fixed.
  - **Dashboard:** progress KPIs and data-source health.
  - **Updates in Setup:** check now, status and errors.
  - Removed the last fixed lists (off-plan keywords, meal clock times, gym heuristic).
- **Tests:** 12 engine, 23 service and 6 Telegram unit tests, plus 4 live tests (agent, voice, Telegram send, Telegram photo+voice). All green.

## Previous state (v2.0)
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
- **2026-10-07 (v2.1 voice calls, media, dashboard).**
  - Changes: D-038…D-041 (see `CHANGELOG.md`, `AUDIT.md` B1–B5).
  - Verified with the real product:
    - **Telegram:** a real photo (a thali) and a real OGG voice note were sent through the bot to the user's chat. The bridge downloaded them and real Gemini described and transcribed them. Both were stored and replied to in Telegram (`build/live-telegram-media.txt`).
    - **Voice test (`LiveVoiceTest`):** Gemini Live coach plus a Gemini Live simulated user talking by audio, 5–7 turns of Hinglish. The coach ended the call itself with `end_call`. The transcript became a skip for the commitment, a new morning-walk commitment and a long-term memory. The next agent decision used the call ("check in before their morning walk").
    - **Emulator, real key:**
      1. The user typed "call kar lo…" in chat.
      2. On its own, the agent chose `channel: call`.
      3. The phone rang with a CallStyle notification (Decline/Answer).
      4. Answer opened a live call on `gemini-3.8-live`, and the coach spoke first in Hinglish about the user's situation.
      5. End: the call was stored, and the agent re-evaluated ("follow up on the silent call").
    - **📞 user-started call:** also verified on the emulator.
  - Release: CI published v2.1.8. Verified on the emulator: in-app update 2.0.6 -> 2.1.8 (DB migrated, Setup shows "You're up to date").
  - CI's first run failed on release lint (activity-result API in `CallActivity`). Fixed; the lesson is in CODING_RULES.
  - Found and fixed during verification:
    - The reply prompt said the coach "can't call". It now knows calls exist.
    - Live tests at night hit quiet hours; they now move quiet hours away from now.
    - Mic init failure is now shown on the call screen instead of crashing.
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
