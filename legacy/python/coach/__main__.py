"""Entry point: `python -m coach`.

Runs the Telegram channel (long polling), the planner tick, and - if
INGEST_SECRET is set - the Health Connect ingest API, in one process.
"""

from __future__ import annotations

import asyncio
import logging
import signal

from .config import Settings
from .engine.service import CoachService
from .llm.factory import create_provider
from .memory.db import connect
from .memory.store import Store


async def run(settings: Settings) -> None:
    from .channels.telegram_bot import TelegramChannel

    conn = connect(settings.database_path)
    store = Store(conn, settings.timezone)
    llm = create_provider(settings)
    service = CoachService(
        store,
        llm,
        ignore_after_minutes=settings.ignore_after_minutes,
        min_gap_minutes=settings.min_minutes_between_proactive,
    )
    profile = store.get_profile()
    # Seed runtime defaults from configuration on first run only.
    if not conn.execute("SELECT 1 FROM profile WHERE key = 'daily_message_budget'").fetchone():
        from .timeutil import utcnow

        store.set_profile(
            utcnow(),
            daily_message_budget=settings.default_daily_message_budget,
            quiet_start=settings.default_quiet_start,
            quiet_end=settings.default_quiet_end,
        )
    logging.getLogger(__name__).info("Starting coach (mode=%s)", profile["coaching_mode"])

    channel = TelegramChannel(
        service, settings.telegram_bot_token, settings.telegram_allowed_user_id, settings.planner_interval_minutes
    )
    if settings.calendar_ics_url:
        from .ingest.calendar import fetch_ics, ingest_ics
        from .timeutil import utcnow as _now

        async def poll_calendar(context) -> None:
            try:
                ingest_ics(store, await fetch_ics(settings.calendar_ics_url), _now())
            except Exception as exc:  # network or parse problems must not stop the bot
                logging.getLogger(__name__).warning("Calendar poll failed: %s", exc.__class__.__name__)

        channel.app.job_queue.run_repeating(poll_calendar, interval=30 * 60, first=10)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except NotImplementedError:  # Windows
            pass

    async with channel.app:
        await channel.app.start()
        await channel.app.updater.start_polling(drop_pending_updates=False)
        server_task = None
        if settings.ingest_secret:
            import uvicorn

            from .ingest.api import create_app

            config = uvicorn.Config(
                create_app(store, settings.ingest_secret),
                host=settings.ingest_host,
                port=settings.ingest_port,
                log_level="info",
            )
            server = uvicorn.Server(config)
            server.install_signal_handlers = lambda: None  # signals handled above
            server_task = asyncio.create_task(server.serve())
        else:
            logging.getLogger(__name__).warning("INGEST_SECRET not set: Health Connect ingest is disabled")
        await stop.wait()
        if server_task:
            server.should_exit = True
            await server_task
        await channel.app.updater.stop()
        await channel.app.stop()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)  # avoid logging bot-token URLs
    settings = Settings.from_env()
    logging.getLogger(__name__).info("Config: %s", settings.redacted())
    asyncio.run(run(settings))


if __name__ == "__main__":
    main()
