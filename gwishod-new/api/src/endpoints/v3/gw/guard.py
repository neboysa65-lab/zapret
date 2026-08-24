"""Защита эндпоинтов управления GW-нодами (ssh_password / proxy / payload).

Эндпоинты /vpn/api/v1/bot/servers_gw/* возвращают и изменяют секретные поля
GW-нод. Доступ разрешён только клиентам, предъявившим заголовок
X-Internal-Token, равный INTERNAL_API_TOKEN из окружения API-сервера.

Fail-closed: если INTERNAL_API_TOKEN не задан — все GW bot-эндпоинты
закрыты (503), а не открыты.
"""
import hmac
import logging
import os

from fastapi import Request
from fastapi.responses import JSONResponse

logger = logging.getLogger(__name__)

HEADER_NAME = "x-internal-token"


def _expected_token() -> str:
    return (os.getenv("INTERNAL_API_TOKEN") or "").strip()


def internal_token_configured() -> bool:
    return bool(_expected_token())


async def require_internal_token(request: Request):
    expected = _expected_token()
    if not expected:
        logger.warning("GW bot API blocked: INTERNAL_API_TOKEN is not configured")
        return JSONResponse(
            status_code=503,
            content={"success": 0, "message": "gw control plane locked"},
        )
    provided = request.headers.get(HEADER_NAME, "")
    if not provided or not hmac.compare_digest(provided.encode(), expected.encode()):
        logger.warning(
            "GW bot API rejected request %s %s from %s",
            request.method,
            request.url.path,
            request.client.host if request.client else "?",
        )
        return JSONResponse(
            status_code=401,
            content={"success": 0, "message": "unauthorized"},
        )
    return None
