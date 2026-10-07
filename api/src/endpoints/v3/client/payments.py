import logging
import os
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse, HTMLResponse
from pydantic import BaseModel

from src.db.v3.payments.invoice_pojo import InvoicePojo as InvoicePojoV3
from src.db.v2.tariff_pojo import TariffPojo
from src.db.v3.user.user_pojo import UserPojo as UserPojoV3
from src.endpoints.v1.payment.get_pay_url import (
    PAYMENTS_ENABLED,
    PaymentLinkRequest,
    create_payment_link,
)
from src.endpoints.response_object import ResponseJson, ResponseJsonMessage

logger = logging.getLogger(__name__)

router = APIRouter()

# Redirect after the provider form. Deep link back into the app or any
# landing page — configurable so hosts can point it at their own domain.
SUCCESS_REDIRECT_URL = os.getenv(
    "PAYMENT_SUCCESS_REDIRECT_URL", "https://t.me/rabochiyvpn_chat"
)
FAIL_REDIRECT_URL = os.getenv(
    "PAYMENT_FAIL_REDIRECT_URL", "https://t.me/rabochiyvpn_chat"
)


class PaymentInitiateRequest(BaseModel):
    user_id: str
    tariff_id: str


class PaymentInitiateResponse(BaseModel):
    success: bool
    message: ResponseJsonMessage
    data: dict


def _error_response(status_code: int, info: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content=ResponseJson(
            success=False,
            message=ResponseJsonMessage(status_code=status_code, info=info),
            data={},
        ).model_dump(),
    )


async def _is_user_premium(user_id: str) -> bool:
    user = UserPojoV3()
    user.user_id = user_id
    found = await user.find_by_user_id()
    return bool(found and found.is_premium)


@router.post("/vpn/api/v3/client/payments/initiate")
async def payments_initiate(request_body: PaymentInitiateRequest):
    """Create a payment (WATA / SBP form) and return its id + pay url.

    Same flow as the reference storefront: the app (or the /pay page)
    calls initiate, receives `payment_id` + `pay_url`, opens the form,
    then polls `payments/check` until the webhook flips the status.
    """
    user_id = (request_body.user_id or "").strip()
    tariff_id = (request_body.tariff_id or "").strip()

    if not PAYMENTS_ENABLED:
        return _error_response(503, "Payments temporarily disabled")

    if not user_id or not tariff_id:
        return _error_response(400, "user_id and tariff_id are required")

    # Same source as /tariff/get and the payment webhook — the v2 `tariff`
    # collection. Mixing v2/v3 here caused "Tariff not found" for every
    # real tariff the app lists.
    tariff = TariffPojo()
    tariff.technical_name = tariff_id
    try:
        tariff = await tariff.find()
    except Exception as e:
        logger.error("Payments initiate: tariff lookup failed | tariff=%s error=%s", tariff_id, e)
        return _error_response(500, "Internal server error")

    if tariff is None:
        logger.error("Payments initiate: tariff not found | tariff=%s", tariff_id)
        return _error_response(404, "Tariff not found")

    internal_id = str(uuid.uuid4())
    payment_id = f"subscription{internal_id}"
    expiration = (datetime.now(timezone.utc) + timedelta(days=1)).isoformat()

    request = PaymentLinkRequest(
        amount=tariff.price,
        currency="RUB",
        description=f"Оплата тарифа: {tariff.description}",
        orderId=payment_id,
        successRedirectUrl=SUCCESS_REDIRECT_URL,
        failRedirectUrl=FAIL_REDIRECT_URL,
        expirationDateTime=expiration,
    )

    try:
        resp = await create_payment_link(request)
    except Exception as e:
        logger.error(
            "Payments initiate: provider call failed | user=%s tariff=%s error=%s",
            user_id, tariff_id, e,
        )
        return _error_response(500, "Failed to create payment link")

    pay_url = str(resp.get("url", ""))
    if not pay_url:
        logger.error("Payments initiate: provider returned no url | user=%s tariff=%s", user_id, tariff_id)
        return _error_response(500, "Invalid provider response")

    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    invoice = InvoicePojoV3()
    invoice.invoice_id = payment_id
    invoice.user_id = user_id
    invoice.amount = str(tariff.price)
    invoice.status = False
    invoice.pay_url = pay_url
    invoice.created = now_ms
    invoice.updated = now_ms
    invoice.p_email = ""
    invoice.p_phone = ""
    invoice.commission = ""
    invoice.plan = tariff_id
    invoice.provider = "wata"

    try:
        ok = await invoice.insert()
    except Exception as e:
        logger.error("Payments initiate: DB insert failed | user=%s payment=%s error=%s", user_id, payment_id, e)
        return _error_response(500, "Failed to save payment")

    if not ok:
        logger.error("Payments initiate: DB insert returned false | user=%s payment=%s", user_id, payment_id)
        return _error_response(500, "Failed to save payment")

    logger.info(
        "Payments initiate: success | user=%s tariff=%s amount=%s payment=%s",
        user_id, tariff_id, tariff.price, payment_id,
    )

    return JSONResponse(
        status_code=200,
        content=ResponseJson(
            success=True,
            message=ResponseJsonMessage(status_code=200, info="Payment created"),
            data={
                "payment_id": payment_id,
                "status": "pending",
                "pay_url": pay_url,
                "amount": tariff.price,
                "currency": "RUB",
            },
        ).model_dump(),
    )


@router.get("/vpn/api/v3/client/payments/check")
async def payments_check(payment_id: str = Query(...)):
    """Poll payment status. `status` mirrors the reference API:
    pending / paid / failed. `is_premium` reflects the user right now."""
    payment_id = (payment_id or "").strip()
    if not payment_id:
        return _error_response(400, "payment_id is required")

    invoice = InvoicePojoV3()
    invoice.invoice_id = payment_id
    found = await invoice.find_by_id()

    if found is None:
        return _error_response(404, "Payment not found")

    status = "paid" if found.status else "pending"
    is_premium = await _is_user_premium(found.user_id)

    return JSONResponse(
        status_code=200,
        content=ResponseJson(
            success=True,
            message=ResponseJsonMessage(status_code=200, info="Payment status"),
            data={
                "payment_id": payment_id,
                "status": status,
                "is_premium": is_premium,
            },
        ).model_dump(),
    )


_PAGE_STYLE = """
  :root { color-scheme: dark; }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background: #1F2228; color: #fff; min-height: 100vh;
    font-family: "SF Mono", ui-monospace, Menlo, Consolas, monospace;
    padding: 48px 24px;
  }
  .wrap { max-width: 560px; margin: 0 auto; }
  h1 { font-size: 22px; font-weight: 400; letter-spacing: 2px; text-transform: uppercase; }
  .sub { color: rgba(255,255,255,.6); font-size: 14px; margin: 12px 0 32px; line-height: 1.5; }
  .card {
    border: 1px solid rgba(255,255,255,.1); border-radius: 16px;
    padding: 20px 22px; margin-bottom: 14px;
    display: flex; justify-content: space-between; align-items: center; gap: 16px;
    background: rgba(255,255,255,.03);
  }
  .card:hover { border-color: rgba(255,255,255,.2); }
  .name { font-size: 16px; letter-spacing: .5px; }
  .desc { color: rgba(255,255,255,.5); font-size: 12px; margin-top: 6px; line-height: 1.4; }
  .price { font-size: 18px; white-space: nowrap; }
  button {
    font-family: inherit; text-transform: uppercase; letter-spacing: 1.4px;
    background: #fff; color: #1F2228; border: none; border-radius: 12px;
    padding: 12px 22px; font-size: 13px; cursor: pointer;
  }
  button:disabled { opacity: .4; cursor: wait; }
  .status { margin-top: 28px; text-align: center; color: rgba(255,255,255,.7); font-size: 14px; display: none; }
"""


@router.get("/pay", response_class=HTMLResponse)
async def pay_page(uid: str = Query("")):
    """Storefront page (WebView target): tariff cards + buy buttons.

    Mirrors the reference flow — user picks a tariff, the page calls
    `payments/initiate` and redirects to the provider form.
    """
    if not PAYMENTS_ENABLED:
        return HTMLResponse(
            "<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"utf-8\">"
            "<title>Rabochiy VPN — Оплата</title></head>"
            "<body style=\"background:#1F2228;color:#fff;font-family:sans-serif;"
            "padding:48px 24px;text-align:center\">"
            "<h1>Rabochiy VPN</h1>"
            "<p>Оплата временно недоступна. Попробуйте позже.</p></body></html>",
            status_code=503,
        )
    cards = ""
    try:
        tariffs = await TariffPojo.find_all()
    except Exception:
        tariffs = []
    for t in tariffs:
        if t.price <= 0 or t.duration <= 0:
            continue
        days = max(1, round(t.duration / 86400000))
        cards += (
            '<div class="card">'
            f'<div><div class="name">{t.name}</div>'
            f'<div class="desc">{t.description} &middot; {days} дн.</div></div>'
            f'<div style="text-align:right"><div class="price">{t.price:g} &#8381;</div>'
            f'<button data-tariff="{t.technical_name}" onclick="buy(this)">Купить</button></div>'
            "</div>"
        )
    if not cards:
        cards = '<div class="card"><div class="desc">Тарифы временно недоступны.</div></div>'

    html = f"""<!DOCTYPE html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Rabochiy VPN — Оплата</title>
<style>{_PAGE_STYLE}</style></head>
<body><div class="wrap">
<h1>Rabochiy VPN</h1>
<p class="sub">Выберите тариф — оплата через СБП любым способом.<br>
После оплаты премиум активируется автоматически.</p>
{cards}
<p class="status" id="status">Создаём платёж…</p>
</div>
<script>
const UID = new URLSearchParams(location.search).get("uid") || "";
async function buy(btn) {{
  document.querySelectorAll("button").forEach(b => b.disabled = true);
  const st = document.getElementById("status");
  st.style.display = "block";
  st.textContent = "Создаём платёж…";
  try {{
    const r = await fetch("/vpn/api/v3/client/payments/initiate", {{
      method: "POST",
      headers: {{"Content-Type": "application/json"}},
      body: JSON.stringify({{ user_id: UID, tariff_id: btn.dataset.tariff }})
    }});
    const j = await r.json();
    const payUrl = j && j.data && j.data.pay_url;
    if (payUrl) {{ location.href = payUrl; return; }}
    st.textContent = "Не удалось создать платёж. Попробуйте ещё раз.";
  }} catch (e) {{
    st.textContent = "Не удалось создать платёж. Попробуйте ещё раз.";
  }}
  document.querySelectorAll("button").forEach(b => b.disabled = false);
}}
</script>
</body></html>"""
    return HTMLResponse(html)
