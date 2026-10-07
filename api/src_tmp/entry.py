"""Appwrite Functions entrypoint for the Rabochiy VPN HTTP API.

Wraps the FastAPI application (src/endpoints/server.py) so every request
that reaches the function's domain is served by the same ASGI app used
in Docker/VPS deployments. The Mongo client is created lazily on first
query and cached per warm instance (see src/config.py).

Function configuration (appwrite.json):
  - runtime: python-3.12
  - entrypoint: entry.py::main
  - commands: pip install -r requirements.txt
"""

import asyncio

import httpx

from src.endpoints.server import app

_asgi_transport = httpx.ASGITransport(app=app)

_HOP_HEADERS = {
    "host",
    "content-length",
    "connection",
    "transfer-encoding",
    "keep-alive",
    "upgrade",
    "date",
    "server",
}


def _read_body(req) -> bytes:
    for attr in ("body_binary", "bodyBinary"):
        data = getattr(req, attr, None)
        if data:
            return data if isinstance(data, bytes) else str(data).encode("utf-8")
    for attr in ("body_text", "bodyText", "body_raw", "bodyRaw"):
        text = getattr(req, attr, None)
        if text:
            return text.encode("utf-8") if isinstance(text, str) else bytes(text)
    return b""


def _read_query(req) -> str:
    qs = getattr(req, "query_string", None) or getattr(req, "queryString", "")
    if qs:
        return qs if isinstance(qs, str) else "&".join(
            f"{k}={v}" for k, v in dict(qs).items()
        )
    # Fallback: rebuild from the parsed query dict.
    query = getattr(req, "query", None) or {}
    if isinstance(query, dict) and query:
        parts = []
        for key, value in query.items():
            if isinstance(value, list):
                parts.extend(f"{key}={v}" for v in value)
            else:
                parts.append(f"{key}={value}")
        return "&".join(parts)
    return ""


def main(context):
    req = context.req

    async def _dispatch():
        method = (req.method or "GET").upper()
        path = getattr(req, "path", None) or "/"
        if not path.startswith("/"):
            path = "/" + path
        qs = _read_query(req)
        target = f"{path}?{qs}" if qs else path
        headers = {
            k: v
            for k, v in dict(req.headers or {}).items()
            if str(k).lower() not in _HOP_HEADERS
        }
        async with httpx.AsyncClient(
            transport=_asgi_transport, base_url="http://internal"
        ) as client:
            return await client.request(
                method, target, headers=headers, content=_read_body(req)
            )

    try:
        response = asyncio.run(_dispatch())
    except Exception as exc:  # never leak a 500-less empty response
        context.error(f"API dispatch failed: {exc!r}")
        return context.res.json(
            {"success": False, "message": {"status_code": 500, "info": "Internal server error"}, "data": {}},
            500,
        )

    headers = {
        k: v
        for k, v in response.headers.items()
        if k.lower() not in _HOP_HEADERS and k.lower() != "content-type"
    }
    content_type = response.headers.get("content-type", "")

    if "application/json" in content_type:
        return context.res.json(response.json(), response.status_code, headers)
    if content_type.startswith("text/"):
        return context.res.text(response.text, response.status_code, headers)
    return context.res.binary(response.content, response.status_code, headers)
