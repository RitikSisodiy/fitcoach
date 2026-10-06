"""Telegram channel (python-telegram-bot v21+). Long polling; no public URL required.

Only the configured user id may talk to the bot.
"""

from __future__ import annotations

import logging

from telegram import InlineKeyboardButton, InlineKeyboardMarkup, KeyboardButton, ReplyKeyboardMarkup, Update
from telegram.ext import (
    Application,
    CallbackQueryHandler,
    CommandHandler,
    ContextTypes,
    MessageHandler,
    filters,
)

from ..engine.service import CoachService, InboundMessage, OutboundMessage
from ..llm.base import MediaPart
from ..memory.store import COACHING_MODES
from ..timeutil import utcnow

log = logging.getLogger(__name__)

HELP_TEXT = (
    "Just talk to me normally - meals, plans, walks, weight, how the day is going.\n\n"
    "Commands:\n"
    "/status - today at a glance\n"
    "/mode gentle|normal|accountability|strong - coaching intensity\n"
    "/pause [days] - pause proactive messages (default 1 day)\n"
    "/resume - resume\n"
    "/commitments - list active commitments\n"
    "/drop <id> - retire a commitment"
)


QUICK_BAR = ReplyKeyboardMarkup(
    [[KeyboardButton("Usual meal"), KeyboardButton("Had a snack")], [KeyboardButton("Walked"), KeyboardButton("Skipped today")]],
    resize_keyboard=True,
    is_persistent=True,
    input_field_placeholder="Or just type / send a voice note",
)


def _remaining_markup(message, pressed: str) -> InlineKeyboardMarkup | None:
    """After a tap, remove only the buttons that answered the same question (e.g. recap vs a pending payment)."""
    prefix, ident = pressed.split(":")[0], pressed.split(":")[1] if pressed.count(":") >= 1 else ""
    if message is None or message.reply_markup is None:
        return None
    keep = []
    for row in message.reply_markup.inline_keyboard:
        for b in row:
            data = b.callback_data or ""
            same_group = data.split(":")[0] == prefix and (prefix != "inf" or data.split(":")[1] == ident)
            if not same_group:
                keep.append((b.text, data))
    return _markup(keep)


def _markup(buttons: list[tuple[str, str]]) -> InlineKeyboardMarkup | None:
    if not buttons:
        return None
    rows, row = [], []
    for label, data in buttons:
        row.append(InlineKeyboardButton(label, callback_data=data))
        if len(row) == 2:
            rows.append(row)
            row = []
    if row:
        rows.append(row)
    return InlineKeyboardMarkup(rows)


class TelegramChannel:
    def __init__(self, service: CoachService, token: str, allowed_user_id: int, tick_minutes: int):
        if not token:
            raise RuntimeError("TELEGRAM_BOT_TOKEN is not set")
        if not allowed_user_id:
            raise RuntimeError("TELEGRAM_ALLOWED_USER_ID is not set (the bot refuses to run open to everyone)")
        self.service = service
        self.allowed_user_id = allowed_user_id
        self.app = Application.builder().token(token).build()
        self._register()
        self.app.job_queue.run_repeating(self._tick, interval=tick_minutes * 60, first=30)

    # ---------------------------------------------------------------- helpers
    def _allowed(self, update: Update) -> bool:
        """Only the configured user, and only in a private chat (health data must never go to a group)."""
        user, chat = update.effective_user, update.effective_chat
        return (
            user is not None
            and user.id == self.allowed_user_id
            and chat is not None
            and chat.type == "private"
        )

    async def _deliver(self, chat_id: int, messages: list[OutboundMessage]) -> None:
        for msg in messages:
            await self.app.bot.send_message(chat_id=chat_id, text=msg.text, reply_markup=_markup(msg.buttons))

    def _register(self) -> None:
        a = self.app
        a.add_handler(CommandHandler("start", self._start))
        a.add_handler(CommandHandler("help", self._help))
        a.add_handler(CommandHandler("status", self._status))
        a.add_handler(CommandHandler("mode", self._mode))
        a.add_handler(CommandHandler("pause", self._pause))
        a.add_handler(CommandHandler("resume", self._resume))
        a.add_handler(CommandHandler("commitments", self._commitments))
        a.add_handler(CommandHandler("drop", self._drop))
        a.add_handler(CallbackQueryHandler(self._button))
        new_only = filters.UpdateType.MESSAGE  # edited messages are ignored (update.message would be None)
        a.add_handler(MessageHandler(new_only & (filters.VOICE | filters.AUDIO), self._voice))
        a.add_handler(MessageHandler(new_only & filters.PHOTO, self._photo))
        a.add_handler(MessageHandler(new_only & filters.TEXT & ~filters.COMMAND, self._text))

    # --------------------------------------------------------------- commands
    async def _start(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._allowed(update):
            return
        now = utcnow()
        self.service.store.set_profile(now, chat_id=update.effective_chat.id)
        await update.message.reply_text(
            "Hi. I'm your coach. Tell me about your day in your own words - I'll keep track. "
            "The buttons below are shortcuts for the most common things.\n\n" + HELP_TEXT,
            reply_markup=QUICK_BAR,
        )

    async def _help(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if self._allowed(update):
            await update.message.reply_text(HELP_TEXT)

    async def _status(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if self._allowed(update):
            await update.message.reply_text(self.service.status_text(utcnow()))

    async def _mode(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._allowed(update):
            return
        args = context.args or []
        if not args or args[0] not in COACHING_MODES:
            current = self.service.store.get_profile()["coaching_mode"]
            await update.message.reply_text(f"Current mode: {current}. Options: {', '.join(COACHING_MODES)}")
            return
        mode = args[0]
        if mode == "strong":
            await update.message.reply_text(
                "Strong accountability: I will push for at least the minimum version and ask for a reason before you "
                "drop a commitment. No shaming, and you can turn it off any time with /mode normal. Enable it?",
                reply_markup=_markup([("Yes, enable", "mode:strong:confirm"), ("No", "mode:strong:cancel")]),
            )
            return
        self.service.set_mode(utcnow(), mode)
        await update.message.reply_text(f"Mode set to {mode}.")

    async def _pause(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._allowed(update):
            return
        try:
            days = float(context.args[0]) if context.args else 1.0
        except ValueError:
            days = 1.0
        days = max(0.1, min(days, 30))
        until = self.service.pause(utcnow(), days)
        await update.message.reply_text(f"Paused until {self.service.store.fmt_local(until)}. /resume any time.")

    async def _resume(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if self._allowed(update):
            self.service.resume(utcnow())
            await update.message.reply_text("Resumed.")

    async def _commitments(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._allowed(update):
            return
        items = self.service.store.active_commitments()
        if not items:
            await update.message.reply_text("No active commitments yet. Tell me a rule you want to keep.")
            return
        lines = []
        for c in items:
            when = f" ({c.schedule_days} {c.window_start}-{c.window_end})" if c.window_start else ""
            trigger = f" [when: {c.trigger_tag}]" if c.trigger_tag else ""
            lines.append(f"#{c.id} {c.title}{trigger}{when}\n   fallback: {' -> '.join(c.versions)}")
        await update.message.reply_text("\n".join(lines))

    async def _drop(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._allowed(update):
            return
        try:
            cid = int(context.args[0])
        except (IndexError, ValueError, TypeError):
            await update.message.reply_text("Usage: /drop <id>")
            return
        if self.service.store.get_commitment(cid) is None:
            await update.message.reply_text("No such commitment.")
            return
        self.service.store.set_commitment_status(utcnow(), cid, "retired")
        await update.message.reply_text(f"Commitment #{cid} retired.")

    # --------------------------------------------------------------- messages
    async def _handle(self, update: Update, inbound: InboundMessage) -> None:
        self.service.store.set_profile(inbound.received_at, chat_id=update.effective_chat.id)
        await update.effective_chat.send_action("typing")
        try:
            replies = await self.service.handle_message(inbound)
        except Exception:
            log.exception("Failed to handle message")
            await update.message.reply_text("Something went wrong on my side. Your message is saved; try again in a bit.")
            return
        await self._deliver(update.effective_chat.id, replies)

    async def _text(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._allowed(update):
            return
        await self._handle(
            update,
            InboundMessage(text=update.message.text, received_at=utcnow(), external_id=f"tg:{update.update_id}"),
        )

    async def _voice(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._allowed(update):
            return
        voice = update.message.voice or update.message.audio
        tg_file = await context.bot.get_file(voice.file_id)
        data = bytes(await tg_file.download_as_bytearray())
        mime = getattr(voice, "mime_type", None) or "audio/ogg"
        await self._handle(
            update,
            InboundMessage(
                text=update.message.caption or "",
                received_at=utcnow(),
                kind="voice",
                media=[MediaPart(data=data, mime_type=mime)],
                external_id=f"tg:{update.update_id}",
            ),
        )

    async def _photo(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        if not self._allowed(update):
            return
        photo = update.message.photo[-1]  # largest size
        tg_file = await context.bot.get_file(photo.file_id)
        data = bytes(await tg_file.download_as_bytearray())
        await self._handle(
            update,
            InboundMessage(
                text=update.message.caption or "",
                received_at=utcnow(),
                kind="photo",
                media=[MediaPart(data=data, mime_type="image/jpeg")],
                external_id=f"tg:{update.update_id}",
            ),
        )

    async def _button(self, update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
        query = update.callback_query
        if not self._allowed(update):
            await query.answer()
            return
        await query.answer()
        now = utcnow()
        data = query.data or ""
        if data.startswith("rs:"):
            reply = await self.service.handle_reason(data, now)
        else:
            reply = await self.service.handle_button(data, now)
        if ":" in data:
            try:
                await query.edit_message_reply_markup(reply_markup=_remaining_markup(query.message, data))
            except Exception:  # message too old or already edited
                pass
        if reply:
            await self._deliver(update.effective_chat.id, [reply])

    # -------------------------------------------------------------- scheduler
    async def _tick(self, context: ContextTypes.DEFAULT_TYPE) -> None:
        chat_id = self.service.store.get_profile().get("chat_id")
        if not chat_id:
            return
        try:
            messages = await self.service.tick(utcnow())
        except Exception:
            log.exception("Planner tick failed")
            return
        await self._deliver(chat_id, messages)
