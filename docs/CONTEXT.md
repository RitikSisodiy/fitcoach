# Project Context

Read this first in a new session. It is enough to continue without rereading the codebase.

## What it is
A personal, proactive AI fitness coach for one lazy user (Hinglish, India).
- It tracks food, steps, sleep and habits with minimum effort.
- It reminds the user of their own rules at the right moment, offers smaller versions instead of all-or-nothing, and never shames.
- **Product (v2):** a native Android app. No server.
  - The phone is the backend for the app chat, notifications and the user's own Telegram bot.
  - An LLM agent decides whether, when and how to reach the user, remembers what they say, and learns from outcomes.
  - Gemini is called with the user's own key. See `docs/AGENT.md`.

## Layout
| Path | What |
|---|---|
| `android/` | Kotlin + Compose app (the product) |
| `android/app/src/main/java/com/fitcoach/app/engine/` | Engine: `CoachService` (pipeline, tick, limits, outcomes, memory), `Agent` (situation → LLM decision, reflection), `Dashboard`, `Policy` (facts, caps, slot learner), `Engagement`, `Patterns`, `Analytics`, `Extraction` (validator), `LlmTasks` (prompts, extractor, coach reply), `Nutrition`, `Safety`, `NotificationParser` |
| `android/app/src/main/assets/prompts/` | All prompts and JSON schemas |
| `.../telegram/` | Bot API client, bridge (pairing, updates, quick replies), polling service, channel delivery |
| `.../data/` | `Db` (schema), `Store` (all SQL) |
| `.../llm/` | `GeminiProvider` (free-tier routing, quota pacing) |
| `.../sensors/` | Health Connect, activity recognition, geofences, notification listener, calendar, screen time |
| `.../ui/`, `notify/`, `work/`, `update/` | Chat / Dashboard / Setup (incl. Telegram) screens, notifications with quick-reply actions, the 15-min WorkManager tick, in-app updater |
| `legacy/python/` | Archived v0.x Python engine (do not edit) |
| `docs/` | Design docs, plus the lightweight tracking files below |

## Tracking files
- `CODING_RULES.md`: how to work.
- `docs/PROGRESS.md`: latest state and a session log.
- `docs/TASKS.md`: TODO and done.
- `docs/DECISIONS.md`: why (D-001…).
- `docs/BUGS.md`: bugs found and fixed.
- `docs/ANDROID.md`: app design and the first-run checklist.
- `docs/AGENT.md`: the agent loop.
- `docs/AUDIT.md`: what v1.1 got wrong.

## Environments
- **Cloud workspace** (`/home/claude/fitness-coach`): edit code here. It has no access to Google Maven or Gemini.
- **Laptop** (`~/projects/fitness-coach`, pop-os, user ritiksisodiya): git working copy (push from here), build, test, emulator.
  - Toolchain: JDK 21 at `~/tools/jdk-21` and SDK at `~/Android/Sdk` (installed by `~/projects/android-toolchain-setup.sh`).
  - `.env` holds `GEMINI_API_KEY`. Never print it.
  - Scripts in `~/projects/`:
    - `fitcoach-build.sh [tasks]` (log in `fitcoach-build.log`)
    - `fitcoach-smoke.sh` (emulator AVD `fitcoach_api35`)
    - `fitcoach-e2e.sh`
    - `fitcoach-release-check.sh`
    - `fitcoach-update-check.sh install|update [fresh]` (in-app update test against real releases)
    - `fitcoach-signing-setup.sh` (release key; secrets in GitHub; backup in `~/.fitcoach-signing/`)
    - `fitcoach-live-tests.sh` (unit tests plus live Gemini/Telegram, keys from `.env`)
    - `fitcoach-v2-e2e.sh setup|shot|tap|db|logs`: emulator with the real key and the Telegram bot paired to the user's chat via prefs. Night-time testing needs the emulator timezone moved or quiet hours changed.
  - Sync cloud → laptop: tar the changed files, deliver them with SendUserFile, then commit them to `~/projects/` and extract. For small edits, apply the same patch on both sides and compare `md5sum`.
- **Phone:** OnePlus / Oppo / Realme / Vivo (ColorOS). Aggressive background killing is the main runtime risk.

## Git, CI, releases
- Repo: github.com/RitikSisodiy/fitcoach (public). Branch: `master`. The laptop folder is the git working copy.
- CI: `.github/workflows/android-release.yml`. A push to master that changes `android/**` runs the tests, builds the signed APK and publishes the GitHub Release `vMAJOR.MINOR.RUN`.
- The app checks the latest release, notifies the user and installs updates in place (`update/Updater.kt`). See D-031.

## Key constraints
- Gemini free tier per model:
  - Flash-Lite 3.x: 15 RPM / 500 RPD
  - Flash: 5 RPM / 20 RPD
  - Text goes to Lite first; photos, voice and the weekly review go to Flash first.
- The user is chatted with in Hinglish, but every project artifact is in English.
