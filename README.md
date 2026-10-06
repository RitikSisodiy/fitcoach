# Fitness Coach

> New session? Read `CODING_RULES.md`, then `docs/CONTEXT.md` and `docs/PROGRESS.md`.

**v1.0: Android app, no server.** The main product is now the native app in `android/` (see `docs/ANDROID.md`). It reads steps, sleep, workouts, walks, saved places, UPI/food-order notifications, calendar and screen time on the phone itself. Chat happens inside the app, and Gemini is called directly with your own key. The Python code below is the reference engine and simulator, and it is also the source of the app's prompts (`tools/export_prompts.py`).

A personal, proactive AI fitness coach. In the Python reference version it runs over Telegram. Talk to it normally (Hinglish is fine) and it does the tracking. It remembers the commitments you set, reminds you at the moments that matter, offers smaller versions instead of all-or-nothing, and learns which reminders actually work for you.

Start with `docs/`: `GAP_ANALYSIS.md` → `RESEARCH.md` → `DECISIONS.md` → `ARCHITECTURE.md` → `BEHAVIOR.md` → `SIMULATION.md`. The roadmap is in `PLAN.md` and `TASKS.md`.

## Quick start

```bash
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env        # fill in LLM_MODEL, GEMINI_API_KEY, TELEGRAM_BOT_TOKEN, TELEGRAM_ALLOWED_USER_ID
python -m pytest -q         # 158 tests, no network needed
python tools/simulate.py    # 30-day lazy-user simulation (offline)
python tools/live_check.py all   # real Gemini + Telegram checks (needs keys + network)
python -m coach.chat        # try it in the terminal (uses the real LLM)
python -m coach             # run the Telegram bot + planner (+ ingest API if INGEST_SECRET is set)
```

## Health Connect (steps, sleep, weight)

1. Make sure your fitness/scale apps write to Health Connect (Settings → Health Connect).
2. Install **Health Connect Webhook** (github.com/mcnaveen/health-connect-webhook) on the phone and grant read access to Steps, Sleep, Weight and Exercise, plus background access.
3. Set `INGEST_SECRET` in `.env`, expose the ingest port through a tunnel (Tailscale Funnel or Cloudflare Tunnel), and in the app set the URL to `https://<host>/ingest/health-connect` with the header `x-api-key: <INGEST_SECRET>`.
4. Disable battery optimisation for that app.

## Passive food signals (optional, recommended)

Install **SmsForwarder** (github.com/pppscn/SmsForwarder) or MacroDroid on the phone. Forward notifications from Swiggy, Zomato, Zepto, Blinkit, GPay, PhonePe and Paytm, plus bank SMS, to `https://<host>/ingest/notification`. Use the header `x-api-key: <INGEST_SECRET>` and a JSON body `{"package": "...", "title": "...", "text": "...", "time": "ISO-8601"}`. On Android 15+, allow "restricted settings" for the app first. Nothing is logged as food without your confirmation, except:
- payees you've already confirmed as food
- delivery orders, which are logged as clearly labelled *unconfirmed* meals

## Calendar (optional)

Set `CALENDAR_ICS_URL` to your Google Calendar "secret address in iCal format". Only meeting times are used, never titles.

## Telegram commands

`/status` · `/mode gentle|normal|accountability|strong` · `/pause [days]` · `/resume` · `/commitments` · `/drop <id>`

Everything else is plain conversation.
