"""Passive food signals from forwarded phone notifications / SMS.

The phone runs a forwarder (e.g. SmsForwarder or MacroDroid) that POSTs selected
notifications and bank SMS to `/ingest/notification`. Filtering by app package
happens on the phone (data minimisation); the server ignores anything it does
not recognise.

Nothing here becomes a food log directly. Signals become `inferred_events`
(status `pending`) and are confirmed with one tap in the evening recap.

Privacy: the raw SMS/notification body is parsed and then discarded. Only the
amount, time, app, a payee key and (for merchants) a display name are stored.
Person-to-person payees are never shown to the LLM or on buttons.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from ..memory.store import Store
from ..timeutil import local_date, local_time, parse_iso, to_iso

# Restaurant delivery only. Grocery apps (Zepto, Blinkit, Instamart) are not meals.
FOOD_DELIVERY_PACKAGES = {
    "in.swiggy.android": "Swiggy",
    "com.application.zomato": "Zomato",
}
PAYMENT_PACKAGES = {
    "com.google.android.apps.nbu.paisa.user": "GPay",
    "com.phonepe.app": "PhonePe",
    "net.one97.paytm": "Paytm",
}
SMS_PACKAGES = {"sms", "android.sms", "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging"}
SMALL_PAYMENT_MAX = 250.0  # rupees; street food / snack range
ORDER_MERGE_WINDOW = timedelta(hours=2)
PAYMENT_DEDUPE_WINDOW = timedelta(minutes=3)

CURRENCY = r"(?:rs\.?|inr|₹)"
AMOUNT_PATTERNS = [
    re.compile(rf"{CURRENCY}\s*([\d,]+(?:\.\d{{1,2}})?)", re.IGNORECASE),
    re.compile(r"\b(?:debited|deducted)\s+(?:by|for|with)?\s*([\d,]+(?:\.\d{1,2})?)\b", re.IGNORECASE),  # SBI: "debited by 60.0"
]
CREDIT_RE = re.compile(r"\b(credited|received)\b", re.IGNORECASE)
DEBIT_RE = re.compile(r"\b(debited|paid|sent|spent|payment of|transferred|deducted)\b", re.IGNORECASE)
EXCLUDE_RE = re.compile(
    r"\b(otp|one time password|verification code|request(?:ed|s)?|collect|due|reminder|refund|reversed|cashback|"
    r"failed|declined|autopay|mandate|emi|bill)\b",
    re.IGNORECASE,
)
VPA_RE = re.compile(r"\b([a-z0-9][a-z0-9._-]{1,60}@[a-z][a-z0-9]{1,20})\b", re.IGNORECASE)
AXIS_UPI_RE = re.compile(r"UPI/P2([AM])/\d+/([^/\n]{2,60})", re.IGNORECASE)
PAYEE_RE = re.compile(
    r"(?:paid to|sent to|to vpa|trf to|transferred to|\bto)\s+([A-Za-z0-9 .&'-]{2,60}?)"
    r"(?=\s+(?:on|via|ref|refno|ref\.?no|upi|from|a/c|avl|using|for|sms|call|if|not)\b|[.,;\n]|$)",
    re.IGNORECASE,
)
MERCHANT_HINT_RE = re.compile(
    r"(chaat|chat|tea|chai|stall|store|stores|foods?|corner|cafe|caf[eé]|restaurant|sweets?|bakery|dhaba|bhandar|"
    r"mart|kitchen|snacks?|juice|canteen|hotel|dairy|kirana|bhojnalaya|paytmqr|bharatpe|^q\d+@|merchant)",
    re.IGNORECASE,
)
ORDER_RE = re.compile(
    r"\b(order (?:placed|confirmed|is on the way|picked up|delivered|has been delivered)|out for delivery|delivered|"
    r"on (?:its|the) way|enjoy your meal|being prepared)\b",
    re.IGNORECASE,
)
ORDER_ID_RE = re.compile(r"order\s*(?:id|no\.?|#)\s*:?\s*#?(\d{6,})", re.IGNORECASE)
RESTAURANT_RE = re.compile(r"\bfrom\s+([A-Z][A-Za-z0-9&' .-]{1,50}?)(?=\s+(?:is|has|will|at)\b|[.,!\n]|$)")


@dataclass
class Signal:
    kind: str
    summary: str
    natural_key: str
    confidence: float
    payee_key: str | None = None
    amount: float | None = None
    payload: dict | None = None
    app: str | None = None


def parse_time(value, tz: str, default: datetime) -> datetime:
    """Forwarders send ISO with or without offset, or epoch seconds/ms. Naive times are the user's local time."""
    if value in (None, ""):
        return default
    if isinstance(value, (int, float)) or (isinstance(value, str) and value.strip().isdigit()):
        n = float(value)
        if n > 1e12:
            n /= 1000.0
        return datetime.fromtimestamp(n, tz=timezone.utc)
    text = str(value).strip().replace(" ", "T", 1)
    dt = datetime.fromisoformat(text.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=ZoneInfo(tz))
    return dt


def _norm_key(text: str) -> str:
    return re.sub(r"[^a-z0-9@._]+", " ", text.lower()).strip()


def _amount(body: str) -> float | None:
    for pattern in AMOUNT_PATTERNS:
        m = pattern.search(body)
        if m:
            try:
                return float(m.group(1).replace(",", ""))
            except ValueError:
                continue
    return None


def _payee(body: str) -> tuple[str | None, bool]:
    """Returns (payee, is_merchant). Never returns bank helplines or phone numbers."""
    axis = AXIS_UPI_RE.search(body)
    if axis:
        name = axis.group(2).strip()
        return name, axis.group(1).upper() == "M" or bool(MERCHANT_HINT_RE.search(name))
    vpa = VPA_RE.search(body)
    if vpa:
        v = vpa.group(1).lower()
        return v, bool(MERCHANT_HINT_RE.search(v))
    m = PAYEE_RE.search(body)
    if m:
        name = m.group(1).strip()
        if re.fullmatch(r"[\d\s+-]{6,}", name) or name.lower() in {"your", "you", "a/c", "account"}:
            return None, False
        return name, bool(MERCHANT_HINT_RE.search(name))
    return None, False


def parse_notification(package: str, title: str, text: str, when: datetime, tz: str) -> Signal | None:
    body = "\n".join(part for part in (title.strip(), text.strip()) if part)
    if not body:
        return None

    if package in FOOD_DELIVERY_PACKAGES:
        if not ORDER_RE.search(body) or re.search(r"\b(off|offer|deal|coupon)\b", body, re.IGNORECASE):
            return None  # promotions etc.
        app = FOOD_DELIVERY_PACKAGES[package]
        m = RESTAURANT_RE.search(body)
        restaurant = m.group(1).strip() if m else None
        oid = ORDER_ID_RE.search(body)
        key = f"order|{app}|{oid.group(1)}" if oid else f"order|{app}|{to_iso(when)}"
        label = f"{app} order" + (f" from {restaurant}" if restaurant else "")
        return Signal("food_order", label, key, 0.8, payload={"app": app, "restaurant": restaurant}, app=app)

    is_payment_app = package in PAYMENT_PACKAGES
    is_sms = package in SMS_PACKAGES or package.startswith("sms:")
    if not (is_payment_app or is_sms):
        return None
    if EXCLUDE_RE.search(body) or not DEBIT_RE.search(body):
        return None
    if CREDIT_RE.search(body) and not re.search(r"\b(debited|paid|sent)\b", body, re.IGNORECASE):
        return None  # money in, not out
    amount = _amount(body)
    if amount is None or amount <= 0 or amount > SMALL_PAYMENT_MAX:
        return None
    payee, is_merchant = _payee(body)
    payee_key = _norm_key(payee) if payee else None
    t = local_time(when, tz)
    snack_window = 10 <= t.hour < 13 or 16 <= t.hour < 21
    conf = (0.45 if snack_window else 0.25) + (0.1 if is_merchant else 0.0)
    shown = payee if (payee and is_merchant) else "UPI"
    return Signal(
        "small_payment",
        f"₹{amount:g} payment ({shown}) at {t.strftime('%H:%M')}",
        f"pay|{amount:g}|{to_iso(when)}",
        conf,
        payee_key=payee_key,
        amount=amount,
        payload={"source": PAYMENT_PACKAGES.get(package, "SMS"), "merchant": is_merchant, "display": shown},
    )


def _is_duplicate(store: Store, sig: Signal, when: datetime) -> bool:
    """Same payment via SMS and app notification; same order via several status notifications."""
    window = ORDER_MERGE_WINDOW if sig.kind == "food_order" else PAYMENT_DEDUPE_WINDOW
    rows = store.conn.execute(
        "SELECT occurred_at, amount, payload_json FROM inferred_events WHERE kind = ? AND occurred_at >= ? AND occurred_at <= ?",
        (sig.kind, to_iso(when - window), to_iso(when + window)),
    ).fetchall()
    for r in rows:
        if sig.kind == "small_payment" and r["amount"] == sig.amount:
            return True
        if sig.kind == "food_order" and f'"app": "{sig.app}"' in r["payload_json"]:
            return True
    return False


def ingest_notification(store: Store, payload: dict, now: datetime) -> dict:
    """Accepts {package, title, text, time?}. Returns what happened (for logging). Never raises on bad input."""
    try:
        package = str(payload.get("package") or payload.get("app") or "").strip()
        title = str(payload.get("title") or "")
        text = str(payload.get("text") or payload.get("message") or payload.get("content") or "")
        when = parse_time(payload.get("time"), store.tz, now)
    except (ValueError, TypeError, OverflowError) as exc:
        return {"status": "error", "reason": f"bad input: {exc.__class__.__name__}"}
    signal = parse_notification(package, title, text, when, store.tz)
    if signal is None:
        return {"status": "ignored"}
    if signal.payee_key:
        label = store.get_payee_label(signal.payee_key)
        if label is not None and not label["is_food"]:
            return {"status": "ignored", "reason": "payee labelled not food"}
        if label is not None and label["is_food"]:
            signal.confidence = max(signal.confidence, 0.75)
            if label["label"]:
                signal.summary = f"₹{signal.amount:g} at {label['label']} ({local_time(when, store.tz).strftime('%H:%M')})"
    if _is_duplicate(store, signal, when):
        return {"status": "duplicate", "kind": signal.kind}
    new_id = store.add_inferred_event(
        now,
        when,
        kind=signal.kind,
        summary=signal.summary,
        payee_key=signal.payee_key,
        amount=signal.amount,
        payload=signal.payload or {},
        confidence=signal.confidence,
        natural_key=signal.natural_key,
    )
    return {"status": "stored" if new_id else "duplicate", "kind": signal.kind, "id": new_id, "local_date": local_date(when, store.tz)}


__all__ = ["ingest_notification", "parse_notification", "parse_time", "Signal", "parse_iso"]
