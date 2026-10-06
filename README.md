# Fitness Coach

> New session? Read `CODING_RULES.md`, then `docs/CONTEXT.md` and `docs/PROGRESS.md`.

**v2: an autonomous coaching agent on your phone.** `android/` is the whole product: there is no server. Chat with it in the app or on Telegram; it remembers what you say, watches what your phone can see (steps, sleep, walks, places, UPI/food orders, calendar, screen time), decides on its own when a message would help and when to stay quiet, and learns from what you answer and ignore. See `docs/AGENT.md` and the dashboard tab. The old Python engine is archived in `legacy/python/`.

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
