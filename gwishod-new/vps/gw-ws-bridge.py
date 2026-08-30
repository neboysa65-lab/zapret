#!/usr/bin/env python3
"""GW WebSocket bridge: HTTP Upgrade -> raw SSH passthrough.

Accepts a WebSocket-style upgrade request, validates it per RFC 6455,
answers with a proper 101 Switching Protocols (incl. Sec-WebSocket-Accept)
and then bridges the socket bytes to the local SSH daemon. Cloudflare
(and any compliant proxy) requires exactly this handshake before it
switches to blind tunneling.
"""
import asyncio
import base64
import hashlib
import os
import socket
import ssl
import sys
import time

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
SSH_HOST = os.environ.get("GW_SSH_HOST", "127.0.0.1")
SSH_PORT = int(os.environ.get("GW_SSH_PORT", "22"))
DEFAULT_KEY = "dGhlIHNhbXBsZSBub25jZQ=="
STATS_TOKEN = os.environ.get("GW_STATS_TOKEN", "")
_STARTED_AT = time.time()

MAX_HEAD = 65536
HEAD_TIMEOUT = 30
# Лимит ТИШИНЫ, а не лимит жизни: туннель рвём только если в обе стороны
# не летело ни байта IDLE_TIMEOUT секунд. Живой клиент шлёт SSH-keepalive
# каждые 20 секунд, поэтому для него этот лимит недостижим. Раньше это был
# жёсткий таймер жизни (asyncio.wait(timeout=7200)): каждые ровно 2 часа
# мост обрубал полностью здоровый туннель, и клиент видел «реконнект».
IDLE_TIMEOUT = 7200

active_tunnels = 0
# Суммарный трафик туннелей за время жизни процесса бриджа.
# Клиент -> SSH (c2s) и SSH -> клиент (s2c). API считает по дельтам
# скорость и переводит её в load% (см. api/src/utils/gw_metrics.py).
bytes_tx = 0
bytes_rx = 0


def _accept(key: str) -> str:
    return base64.b64encode(hashlib.sha1((key + GUID).encode("latin-1")).digest()).decode()


def _parse_head(head: bytes):
    lines = head.split(b"\r\n")
    if not lines:
        return None, {}
    try:
        method, path, version = lines[0].decode("latin-1").strip().split(" ", 2)
    except ValueError:
        return None, {}
    headers = {}
    for ln in lines[1:]:
        if b":" in ln:
            k, _, v = ln.partition(b":")
            headers[k.strip().lower().decode("latin-1")] = v.strip().decode("latin-1")
    return (method, path, version), headers


def _enable_keepalive(writer: asyncio.StreamWriter):
    """Включает TCP keepalive на сокете с короткими таймингами.

    Без этого пропавший без «прощания» клиент (лифт, отлетевший Wi-Fi)
    держит полуоткрытую сессию часами: sshd и мост ждут данных, которые
    никогда не придут, а active_tunnels в /gw-stats завышен.
    60+20*5 ≈ 2.5 минуты до детекта мёртвого пира ядром.
    """
    sock = writer.get_extra_info("socket")
    if sock is None:
        return
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPIDLE, 60)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, 20)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPCNT, 5)
    except OSError:
        pass


async def _pipe(src: asyncio.StreamReader, dst: asyncio.StreamWriter, count_tx: bool,
                last_active):
    global bytes_tx, bytes_rx
    try:
        while True:
            data = await src.read(65536)
            if not data:
                break
            last_active[0] = time.monotonic()
            if count_tx:
                bytes_tx += len(data)
            else:
                bytes_rx += len(data)
            dst.write(data)
            await dst.drain()
    except (ConnectionError, OSError, asyncio.CancelledError):
        pass
    finally:
        try:
            dst.close()
        except Exception:
            pass


async def _handle(client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter):
    global active_tunnels
    try:
        data = b""
        while b"\r\n\r\n" not in data and len(data) < MAX_HEAD:
            try:
                chunk = await asyncio.wait_for(client_reader.read(4096), timeout=HEAD_TIMEOUT)
            except asyncio.TimeoutError:
                return
            if not chunk:
                return
            data += chunk

        head, _, rest = data.partition(b"\r\n\r\n")
        reqline, headers = _parse_head(head)
        if reqline is None:
            return

        upgrade = headers.get("upgrade", "").lower()
        connection = headers.get("connection", "").lower()
        key = headers.get("sec-websocket-key")

        method, path, _version = reqline
        path_only, _, query = path.partition("?")
        if STATS_TOKEN and path_only.rstrip("/").endswith("/gw-stats"):
            url_token = ""
            for pair in query.split("&"):
                if pair.startswith("token="):
                    url_token = pair[6:]
            if url_token == STATS_TOKEN:
                import json
                import time as _time
                body = json.dumps({
                    "active_tunnels": active_tunnels,
                    "bytes_tx": bytes_tx,
                    "bytes_rx": bytes_rx,
                    "uptime_seconds": int(_time.time() - _STARTED_AT),
                }).encode()
                client_writer.write(
                    b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode()
                    + body
                )
            else:
                client_writer.write(
                    b"HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                )
            await client_writer.drain()
            return

        if "websocket" not in upgrade or "upgrade" not in connection:
            client_writer.write(
                b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
            )
            await client_writer.drain()
            return

        accept = _accept(key or DEFAULT_KEY)
        resp = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n"
            "\r\n"
        ).encode("latin-1")
        client_writer.write(resp)
        await client_writer.drain()

        ssh_reader, ssh_writer = await asyncio.open_connection(SSH_HOST, SSH_PORT)
        if rest:
            ssh_writer.write(rest)
            await ssh_writer.drain()
        _enable_keepalive(client_writer)
        _enable_keepalive(ssh_writer)

        active_tunnels += 1
        try:
            # Штамп последней активности один на туннель: обновляется обеими
            # «трубами» на каждом куске данных, сторож ниже читает его.
            last_active = [time.monotonic()]
            t_c2s = asyncio.ensure_future(
                _pipe(client_reader, ssh_writer, True, last_active))
            t_s2c = asyncio.ensure_future(
                _pipe(ssh_reader, client_writer, False, last_active))
            # Сторож тишины: если кончилась одна труба — туннель закрылся сам
            # (EOF/ошибка). Если истёк IDLE_TIMEOUT тишины — клиент пропал,
            # прибираем. Если же трафик шёл — просто продолжаем сторожить:
            # ЗДОРОВЫЙ туннель живёт сколько угодно, никакого 2-часового капа.
            while True:
                remaining = IDLE_TIMEOUT - (time.monotonic() - last_active[0])
                done, pending = await asyncio.wait(
                    {t_c2s, t_s2c}, return_when=asyncio.FIRST_COMPLETED,
                    timeout=max(remaining, 0.1),
                )
                if done:
                    for t in pending:
                        t.cancel()
                    break
                if time.monotonic() - last_active[0] >= IDLE_TIMEOUT:
                    for t in pending:
                        t.cancel()
                    break
        finally:
            active_tunnels -= 1
    except (ConnectionError, OSError):
        pass
    finally:
        try:
            client_writer.close()
        except Exception:
            pass


def _tls_context(cert: str, key_file: str) -> ssl.SSLContext:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key_file)
    return ctx


async def main():
    spec = os.environ.get("GW_WS_PORTS", "80")
    tls_spec = os.environ.get("GW_WS_TLS", "")
    tls_map = {}
    if tls_spec:
        for entry in tls_spec.split(","):
            parts = entry.split(":")
            if len(parts) == 3:
                port, cert, key_file = parts
                tls_map[int(port)] = _tls_context(cert, key_file)

    servers = []
    for item in spec.split(","):
        port = int(item.strip())
        tls = tls_map.get(port)
        srv = await asyncio.start_server(
            _handle, host="0.0.0.0", port=port, ssl=tls,
            reuse_address=True,
        )
        label = f":{port}" + (" (tls)" if tls else "")
        print(f"gw-ws-bridge listening{label} -> {SSH_HOST}:{SSH_PORT}", flush=True)
        servers.append(srv)

    await asyncio.gather(*(s.serve_forever() for s in servers))


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
