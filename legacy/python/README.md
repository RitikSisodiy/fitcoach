# Legacy Python reference engine (archived)

This is the v0.1–v0.3 Python implementation: the Telegram bot, the server-side ingest, the simulator, and 158 pytest tests.
It is **no longer maintained** and is not the product. Since v2.0 the Android app (`android/`) is the only
implementation, and its prompts live in `android/app/src/main/assets/prompts/` (see `docs/DECISIONS.md` D-034).

It is kept for history, and for the offline simulator ideas in `tools/simulate.py`. It contains the scripted
flows and fixed schedules that v2.0 removed (see `docs/AUDIT.md`), so don't copy from it.
