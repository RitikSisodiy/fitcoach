# Coding Rules

The single source of truth for how this project is built. Read it before starting work and check it again before you finish.
Update it when you learn something reusable (add it under "Lessons"). Keep it short: one line per rule.

## Before you start
1. Read `CODING_RULES.md`, then `docs/CONTEXT.md` (what and where), `docs/PROGRESS.md` (latest state) and `docs/TASKS.md` (what's next).
2. Open only the files the task touches. Use `docs/DECISIONS.md` for the "why" behind the design.
3. Restate the goal in one line and pick the smallest change that achieves it.

## Writing code
- Write the least code that solves the problem. Prefer deleting or reusing code to adding it.
- Reuse existing utilities, patterns and dependencies before creating new ones. Search the codebase first.
  - Kotlin: `core/TimeUtil`, `core/Json`, `data/Store`.
  - Python: `coach/timeutil`, `memory/store`.
- No new dependency unless the platform or the existing deps can't do it. Record why in `DECISIONS.md`.
- No speculative abstractions, layers, interfaces or config "for later". Duplicate once; extract on the third use.
- Keep changes focused. No unrelated refactors, renames or reformatting in the same change.
- Follow existing conventions: naming, file layout, error handling, comment style. Change a convention only with a recorded decision.
- Project content is English only: code, comments, UI text, logs, docs, commit messages.
- Never hardcode or print secrets. Keys live in `.env` on the laptop or in app-private preferences on the phone.

## Project conventions
- **The LLM reasons and writes; code observes, stores, validates and limits.**
  - The agent (`engine/Agent.kt`) decides whether, when, how and where to reach the user. See `docs/AGENT.md`.
  - Code does only safety, grounding, data integrity, rate limits, quota and statistics.
  - Never add a fixed clock window, a message template, a scripted reply or a per-intent button flow. Give the agent the fact instead.
- **Kotlin (`android/`) is the only implementation.**
  - Prompts and schemas live in `android/app/src/main/assets/prompts/`.
  - `legacy/python/` is archived; don't edit it or copy from it.
- **One backend for every channel.** App chat, notifications and Telegram all call `CoachService.handleMessage`, and proactive messages go out through `Delivery`.
  - New phone signals reuse the existing tables: places become `context_events`, sessions become `exercise` health records, payments become `inferred_events`.
- **Every outgoing coach text goes through `Safety`.** Every LLM failure path has a template fallback.
- **Respect the Gemini free tier.** Lite models are for text and Flash for media. Add no new LLM calls on hot paths without counting the quota cost.

## Verify before calling it done
- Run the tests that cover the change. Add a regression test for every bug fixed.
  - Python: `python -m pytest -q`
  - Android, on the laptop: `~/projects/fitcoach-build.sh assembleDebug testDebugUnitTest`
- LLM-facing change: run `~/projects/fitcoach-live-tests.sh`. It runs `LiveAgentTest` (real Gemini, 3 days) and `TelegramTest.liveTelegramSend`. Then read `app/build/live-agent-transcript.txt`, not just the pass/fail.
- UI or Android-runtime change: install on the emulator (`fitcoach-smoke.sh` or `fitcoach-release-check.sh`), check the screenshots, and check that `logcat -b crash` is empty.
- Release APK: R8 is on, so smoke-test the release build itself, not just debug.

## Git and releases
- Work on `master` in small commits with clear English messages. Push from the laptop (it has `gh` auth; the cloud does not).
- A push touching `android/**` builds, tests and publishes a release that users get as an in-app update. Only push working code.
- Bump `versionMinor` in `android/version.properties` for notable releases; the patch number is automatic.
- Never commit secrets, the database, keystores or build outputs (see `.gitignore`).

## After a meaningful change
- `docs/PROGRESS.md`: add one dated line covering what changed, how it was verified, and what's next.
- `docs/TASKS.md`: tick done items and add new TODOs.
- `docs/DECISIONS.md`: record design choices. `docs/BUGS.md`: record bugs found and fixed. `docs/CHANGELOG.md`: record releases.
- `CODING_RULES.md`: add any new lesson. Prune rules that no longer help.

## Lessons (keep adding)
- Android `org.json`: `optString` on a JSON null returns `"null"`. Use `strOrNull()` from `core/Json.kt`.
- Robolectric tests must not boot `FitCoachApp` (WorkManager). `src/test/resources/robolectric.properties` sets `application=android.app.Application`.
- Unit tests prove the code paths; only real-Gemini runs prove extraction quality and agent judgement. Every live run so far has found bugs that mocks missed.
- Tests that call `tick()` must pin a daytime instant. Quiet hours make `tick()` a no-op at night (`Instant.now()` broke a test at 00:07).
- In JUnit 4, `runBlocking<Unit> { }` is needed when the last expression isn't Unit, otherwise "method should be void".
- Python prompt strings are f-strings, so double the `{}` in JSON examples.
- Pick tokenizers and regexes for Hinglish ("sabzi.", "roti,"). Test with real user-style messages.
- Build environment:
  - The cloud sandbox cannot reach Google Maven or Gemini, so build and run live tests on the laptop.
  - Laptop commands longer than ~60 s: run them in the background (`setsid nohup … > log &`) and poll the log.
  - The laptop JVM needs `-Djava.net.preferIPv4Stack=true`, otherwise Gradle downloads time out.
  - Compose 1.12+ needs `compileSdk 37`.
  - Files sent to the chat are capped at 30 MB. Ship the R8 release APK (~3.4 MB), not debug (~39 MB).
- Anchor `.gitignore` paths to the root (`/data/`, not `data/`). The unanchored `data/` silently excluded `android/.../data/` and broke the first CI build. Check `git status` for missing source files before pushing.
- Throttles must not block the user-facing path. The updater shared one 6-hour throttle with app open and missed a release it had just published. Test update flows with two real releases.
- Release signing must never change, or in-place updates fail. Keep the keystore backup (`~/.fitcoach-signing/` on the laptop) safe.
- After editing the same file in both places (cloud copy and laptop copy), compare checksums (`md5sum`) so the two never drift.
- Failures in background loops (Telegram polling, workers) must reach the user or the log, never be swallowed silently. A swallowed exception looked like "the bot ignores photos".
- When a multimodal call understands media, keep its text form (`media_summary`) so later steps never need the media again.
- Real-time voice can be tested without a human: two Gemini Live sessions, one role-playing the user, piping audio to each other (24 kHz out, resampled to 16 kHz in, streamed in real time with silence between turns).
- Live tests that call `tick()` run at any hour. Move quiet hours away from now inside the test, or the agent never runs.
- OkHttp 4 from Kotlin: use the extension APIs (`toMediaType()`, `asRequestBody()`, `response.body`). The Java-style statics are errors.
