"""Local terminal chat for trying the coach without Telegram.

    python -m coach.chat            # uses .env (real LLM provider)
    python -m coach.chat --tick     # also run one planner tick after each message

Uses the same database as the bot unless DATABASE_PATH is overridden.
"""

from __future__ import annotations

import argparse
import asyncio

from .config import Settings
from .engine.service import CoachService, InboundMessage
from .llm.factory import create_provider
from .memory.db import connect
from .memory.store import Store
from .timeutil import utcnow


async def main_async(run_tick: bool) -> None:
    settings = Settings.from_env()
    store = Store(connect(settings.database_path), settings.timezone)
    service = CoachService(store, create_provider(settings), ignore_after_minutes=settings.ignore_after_minutes)
    print("Coach terminal chat. Commands: /status, /tick, /quit")
    while True:
        try:
            text = input("you> ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not text:
            continue
        if text == "/quit":
            break
        if text == "/status":
            print(service.status_text(utcnow()))
            continue
        if text == "/tick":
            for out in await service.tick(utcnow()):
                print(f"coach> {out.text}  {[b[0] for b in out.buttons]}")
            continue
        for out in await service.handle_message(InboundMessage(text=text, received_at=utcnow())):
            buttons = f"  {[b[0] for b in out.buttons]}" if out.buttons else ""
            print(f"coach> {out.text}{buttons}")
        if run_tick:
            for out in await service.tick(utcnow()):
                print(f"coach (proactive)> {out.text}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tick", action="store_true")
    asyncio.run(main_async(parser.parse_args().tick))


if __name__ == "__main__":
    main()
