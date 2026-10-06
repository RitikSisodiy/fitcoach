# Plan

The product goal is fixed; the sequence below is the current best guess and will change with real usage data.

## Revised phase order (vs the original brief)

| Original | Revised | Why |
|---|---|---|
| Phase 4: Android automatic tracking | Moved earlier and much cheaper: an existing open-source bridge (no custom app) | Health Connect data is the lowest-effort signal, and the bridge costs hours, not weeks |
| Phase 5: proactive coaching after food and Android | Built in Phase 1 | Adherence is the core problem; reminders and commitments matter more than food precision |
| Phase 6: behavioural learning late | A minimal learner (slot Thompson sampling, skip reasons, back-off) from day one | Learning needs data from the start; the cost is small |
| Location / geofencing | Deferred indefinitely | Conversation gives context for a WFH user; see D-003 |

## Phases

### Phase 0 — Research and architecture ✅
RESEARCH, ARCHITECTURE, DECISIONS.

### Phase 1 — Working coach core ✅ (code complete, awaiting live credentials)
- Provider abstraction, SQLite memory
- Hinglish conversation → structured events, nutrition ranges
- Commitments and context triggers
- Proactive nudges with ladder, budget and learner
- Safety guard, Health Connect ingest, Telegram channel, weekly review
- 86 tests

### Phase 2 — Go live and calibrate (next)
1. T-102 Live LLM validation on real Hinglish, voice and photos.
2. T-103 / T-105 Run the bot continuously; backups.
3. T-204 Onboarding chat: goals, boundaries, mode, 1–2 focus commitments.
4. T-401 Health Connect bridge on the phone.

**Exit criteria:** one week of real use with ≥ 5 days of interaction, no crashes, and nudge response rate measured.

### Phase 3 — Less effort, better data
T-205 one-tap food corrections, T-207 meal-time attribution, T-301 INDB import, T-504 auto-complete walks from Health Connect, T-403 stale-sync alerts.

### Phase 4 — Learning what works
T-601 pattern consolidation, T-603 experiments, T-602 style bandit. Fill in BEHAVIOR.md §10–12 from data.

### Phase 5 — Hardening
T-801 export/delete, T-802 encryption, T-803 ingest limits, and a monitoring/alert on the process.

## What would change this plan
- **The user ignores Telegram:** evaluate a second channel (e.g. Android notifications through the bridge, or WhatsApp templates).
- **The Health Connect bridge is unreliable on the user's phone (OEM battery killer):** T-1001 Kotlin companion app.
- **Extraction quality on voice/photos is poor with the chosen model:** switch model or provider via config (no code change) and re-run T-102.
