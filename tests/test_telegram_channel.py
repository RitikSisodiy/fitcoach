"""Offline checks for the Telegram channel wiring (no network)."""

import pytest

from coach.channels.telegram_bot import TelegramChannel, _markup
from coach.engine.service import nudge_buttons


def test_markup_layout_two_per_row():
    markup = _markup(nudge_buttons(7))
    rows = markup.inline_keyboard
    assert [len(r) for r in rows] == [2, 2]
    assert rows[0][0].callback_data == "iv:7:done"
    assert all(len(b.callback_data.encode()) <= 64 for r in rows for b in r)  # Telegram limit
    assert _markup([]) is None


def test_channel_refuses_to_run_open_to_everyone(service):
    with pytest.raises(RuntimeError):
        TelegramChannel(service, "123:abc", 0, 15)
    with pytest.raises(RuntimeError):
        TelegramChannel(service, "", 42, 15)


def test_channel_registers_handlers_and_planner_job(service):
    channel = TelegramChannel(service, "123456:TEST-token", 42, 15)
    handlers = [h for group in channel.app.handlers.values() for h in group]
    assert len(handlers) >= 11
    jobs = channel.app.job_queue.jobs()
    assert len(jobs) == 1
