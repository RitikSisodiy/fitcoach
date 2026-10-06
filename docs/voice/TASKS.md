# Voice tasks (plan only — not started)

Baseline: v2.1.8 already ships the ring → answer → Live call → ingest path (D-038). These tasks close the gaps found in [RESEARCH.md](RESEARCH.md) and follow [ARCHITECTURE.md](ARCHITECTURE.md).

Each task is small and has its own test. Priority: P0 (do first), P1, P2.

| ID | P | Task | Done when (test) | Decision |
|---|---|---|---|---|
| VT-01 | P0 | **Measure first.** Add timing to `LiveSession`: time from Answer to the first coach audio, and per turn from the user's end of speech (last input-transcript fragment) to the first reply audio. Store both on the `calls` row; show them on the dashboard. | `LiveVoiceTest` prints p50/p90 turn latency. The emulator call shows "first audio after N ms". | — |
| VT-02 | P0 | **Real-phone call test** on the user's ColorOS phone: ring with the screen off and locked, full screen vs heads-up, Answer, earpiece and speaker, echo (speaker plus mic), barge-in mid-sentence, Bluetooth if available. | A written checklist result in `PROGRESS.md`. Each failure becomes a task. | V-003, V-011 |
| VT-03 | P0 | **Session resumption and compression**: enable `sessionResumption` and `contextWindowCompression` in setup; keep the latest handle; reconnect on `goAway` or an abnormal close; keep the transcript across reconnects. | JVM test with a fake WebSocket server: `goAway` → reconnect with the handle, transcript continuous. Live: a 12-min `LiveVoiceTest` variant does not drop. | V-005 |
| VT-04 | P0 | **Client-side ending**: an idle timeout (30 s of no speech either way → one check-in text → 15 s → end) alongside `end_call`, hang-up and max duration. | Unit test with a fake clock and fake session. Live: the simulated user goes silent and the call ends within about 50 s with the call ingested. | V-006 |
| VT-05 | P1 | **Ring gating**: do not ring during another call (AudioManager mode, telephony state) or when Do Not Disturb blocks our channel. Expose this as `limits.can_call_now = false` with a reason the agent sees. | Robolectric: `AudioManager.MODE_IN_CALL` → `can_call_now` false with a reason. Emulator: ring suppressed during a simulated call. | V-009 |
| VT-06 | P1 | **`lookup` tool**: a read-only function declaration (NON_BLOCKING, WHEN_IDLE) mapped to `runQueries` (`DataQuery` schema); its result is returned in the `toolResponse`. | Unit: a fake toolCall → the correct numbers in the toolResponse. Live: the simulated user asks "pichhle hafte kitne steps the?" and the coach says the number seeded in the DB. | V-008 |
| VT-07 | P1 | **Post-call safety check**: run Safety over the coach turns; log `call_safety` decisions; reflection sees them. | Unit: an unsafe coach transcript → a logged decision. Clean → none. | V-007 |
| VT-08 | P1 | **Trim the call context**: a dedicated `callContext()` (memory, today, active commitments, a 7-day digest, the last 12 messages) instead of the full `buildContext`. | The token estimate of the instruction drops (log its size). `LiveVoiceTest` quality is unchanged on review. | ARCH §4 |
| VT-09 | P1 | **Listening indicator** from the local mic level (3.8 Live has no speech-state signal); show "Listening…", "Coach speaking" and "Reconnecting…" on the call screen. | Emulator screenshot shows the states. Unit test of the RMS threshold helper. | ARCH §3 |
| VT-10 | P2 | **Doze-proof near decisions**: if the agent's next check is 30 min away or less, schedule a `setAndAllowWhileIdle` alarm that runs the tick. | Robolectric: `ShadowAlarmManager` has the alarm. Emulator: `dumpsys deviceidle force-idle` → the tick still runs. | V-010 |
| VT-11 | P2 | **Fallback on mid-call errors**: on a 1008/1011 close mid-call, resume (VT-03) and, if that fails, switch to the fallback model with the transcript as seeded history (`clientContent`). | Fake-server test: 1011 → resume fails → the fallback model gets the history. | V-002 |
| VT-12 | P2 | **Missed-call learning check**: confirm reflection counts call outcomes (answered, declined, missed by hour), so the agent learns when calls work. | Unit: three missed evening calls → the reflection prompt contains them. Live run reviewed. | — |
| VT-13 | P2 | **Core-Telecom spike** (only if VT-02 finds routing, Bluetooth or cellular-clash problems). | A spike branch with a decision recorded. | V-011 |
| VT-14 | P1 | **Docs and tests**: update `docs/AGENT.md` and CHANGELOG; add each live test to `fitcoach-live-tests.sh`; record lessons in CODING_RULES. | The docs match the code. | — |

**Order:**
1. VT-01 and VT-02 (measure the real thing first).
2. VT-03 and VT-04 (reliability).
3. VT-05 to VT-09.
4. The rest.
