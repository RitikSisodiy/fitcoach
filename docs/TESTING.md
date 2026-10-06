# Testing

```bash
pip install -e ".[dev]"
python -m pytest -q
```

Current: **158 tests, all passing** (2026-10-06). No network access is needed; LLM SDK calls are stubbed and behaviour tests use `FakeProvider`.

## Suites

| File | Covers |
|---|---|
| `test_db.py` | migrations (idempotent, WAL), FTS search and injection safety, tag normalisation, profile key validation |
| `test_nutrition.py` | food matching (aliases, plurals, fuzzy, partial), unit conversion and widening, LLM-estimate fallback, unknown foods |
| `test_extractor_validation.py` | clamping, low-confidence drops, planned vs done, unknown commitment ids, time validation, settings requests |
| `test_progress_and_safety.py` | EWMA, insufficient data, loss/too-fast detection, single-spike robustness, target-change gate, shame and unsafe-advice detection |
| `test_journeys.py` | **user journeys**: Hinglish meal logging; precommitment → context trigger → own-rule reminder (once per day); lapse with all-or-nothing thinking; shaming-reply rewrite and fallback; duplicate delivery; extraction failure; voice on a non-audio provider; strong-mode confirmation; weight trend guidance; fact supersession |
| `test_planner.py` | windows, once-per-day, done suppresses the nudge, quiet hours / pause / budget, gentle budget, expiry → ignored → learner, buttons (done / late done / skip + reason / later + one follow-up), escalation ladder and mode caps, strong enforcement conditions, ladder reset, back-off, slot-learner preference and forgetting, weekly review, LLM outage templates |
| `test_ingest.py` | Health Connect Webhook payload (documented format), idempotency and updates, bad records, timezone day attribution, API secret, disabled without secret, steps in coach context as observed |
| `test_llm_providers.py` | JSON parsing, factory and config errors, each provider's request mapping (Gemini schema + audio, Claude forced tool, OpenAI json_schema + images, OpenRouter base URL), fallback provider, secret redaction, OpenAI parameter compatibility |
| `test_telegram_channel.py` | keyboard layout and callback-data size limit, refuses to run without an allowed user, handler and job registration |
| `test_regressions.py` | one test per fixed bug in `BUGS.md` |
| `test_v2_autonomy.py` | notification parsing (orders, UPI, OTP/credit/promo rejection), order dedupe, payee learning, calendar summaries without titles, habitual and declared meals, one-tap recap, inferred confirmation, pattern mining from conversation and from payments, pre-emptive predicted-situation message, rule proposal → commitment, pattern rejection, Wilson bound, dormant re-engagement and spacing, retirement, recap cadence, LLM silence suppression, inactivity from real steps, Health Connect auto-complete, history queries, corrections, commitment edits, meal-time attribution, intake estimate |

## Simulation and live checks

| Tool | What it proves | Needs |
|---|---|---|
| `python tools/simulate.py --mode offline --seed N` | 30 days of a lazy user: tracking coverage, intake-estimate error, passive inference, pattern learning, adaptation, volume, safety (SIMULATION.md) | nothing |
| `python tools/live_check.py llm` | Real-provider extraction on 20 Hinglish cases, decide-schema acceptance, safe Hinglish reply | LLM key + egress |
| `python tools/live_check.py telegram` | Bot identity and delivery to the allowed user | bot token + user id + egress; press /start first |
| `python tools/simulate.py --mode live` | Same 30 days with the real LLM as both coach and user | LLM key + egress (~350–500 LLM calls) |

## Not yet tested (needs real credentials or a device)

| Item | How to verify | Task |
|---|---|---|
| Live Gemini call with the real extraction schema (schema acceptance, Hinglish quality, voice notes) | `python -m coach.chat` with `GEMINI_API_KEY` set; send voice through the bot | T-102 |
| Telegram end-to-end (polling, buttons, voice download) | Run the bot and go through the manual checklist below | T-103 |
| Health Connect Webhook app → endpoint over a tunnel | Configure the app with `x-api-key`; check `/status` shows steps | T-401 |

## Manual end-to-end checklist (Telegram)

1. `/start` → welcome message.
2. "3 roti, dal aur sabzi khayi" → short acknowledgement. `/status` shows a kcal range.
3. "Roz shaam 6 se 7 ke beech 30 min walk karunga, nahi ho paye toh 10 min" → confirms commitment. `/commitments` shows the fallback ladder.
4. Between 18:00 and 19:00 a nudge arrives with 4 buttons. Tap **Later**: one follow-up arrives about 60 min later.
5. "Chess pe sirf coffee, snacks nahi" (rule). Then "aaj chess ja raha hu" → the reply reminds you of your rule.
6. "Samosa kha liya, aaj ka din gaya" → no shaming, no compensation, the next action is named.
7. `/mode strong` → confirmation buttons. Tap **No** → mode unchanged.
8. `/pause 1` → no nudges until the shown local time.
9. Edit a previous message → no error.
