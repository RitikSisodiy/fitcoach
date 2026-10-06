"""HTTP ingest API (FastAPI)."""

from __future__ import annotations

import logging
from typing import Callable

from fastapi import FastAPI, Header, HTTPException, Request

from ..memory.store import Store
from ..timeutil import utcnow
from .health_connect import check_secret, ingest_payload
from .notifications import ingest_notification

log = logging.getLogger(__name__)


def create_app(store: Store, secret: str, clock: Callable = utcnow) -> FastAPI:
    app = FastAPI(title="Coach ingest", docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/health")
    async def health() -> dict:
        return {"ok": True}

    @app.post("/ingest/health-connect")
    async def health_connect(request: Request, x_api_key: str | None = Header(default=None)) -> dict:
        if not check_secret(secret, x_api_key):
            raise HTTPException(status_code=401, detail="unauthorized")
        try:
            payload = await request.json()
        except Exception:
            raise HTTPException(status_code=400, detail="invalid JSON")
        now = clock()
        result = ingest_payload(store, payload, now)
        store.log_decision(
            now,
            "ingest",
            f"Health Connect: stored {result.stored}, unchanged {result.unchanged}",
            {"ignored_types": result.ignored_types, "errors": result.errors[:10]},
        )
        if result.errors:
            log.warning("Health Connect ingest errors: %s", result.errors[:5])
        return {
            "stored": result.stored,
            "unchanged": result.unchanged,
            "ignored_types": result.ignored_types,
            "errors": len(result.errors),
        }

    @app.post("/ingest/notification")
    async def notification(request: Request, x_api_key: str | None = Header(default=None)) -> dict:
        """Forwarded phone notification / SMS: {package, title, text, time?}. Filtering happens on the phone."""
        if not check_secret(secret, x_api_key):
            raise HTTPException(status_code=401, detail="unauthorized")
        body = await request.body()
        if len(body) > 20_000:
            raise HTTPException(status_code=413, detail="payload too large")
        try:
            payload = await request.json()
        except Exception:
            raise HTTPException(status_code=400, detail="invalid JSON")
        items = payload if isinstance(payload, list) else [payload]
        now = clock()
        results = [ingest_notification(store, item, now) for item in items[:50] if isinstance(item, dict)]
        stored = [r for r in results if r["status"] == "stored"]
        if stored:
            store.log_decision(now, "ingest", f"notifications: {len(stored)} inferred event(s)", {"kinds": [r["kind"] for r in stored]})
        return {"results": results}

    return app
