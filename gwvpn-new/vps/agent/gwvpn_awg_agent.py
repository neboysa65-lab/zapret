#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GWVPN AWG Agent — выдача персональных AmneziaWG-пиров для каждого пользователя.

Зачем: раньше все пользователи приложения получали ОДИН общий клиентский конфиг
(один PrivateKey / один туннельный IP). Из-за защиты WireGuard от replay-атак
(монотонный timestamp в handshake на каждый peer) одно устройство со сбитыми
вперёд часами навсегда блокировало подключение всем остальным — до рестарта
awg-quick@awg0. Этот агент выдаёт каждому userId свой собственный peer
(уникальные ключи + уникальный туннельный IP), что полностью устраняет проблему.

Работа:
  - HTTPS-сервер (самоподписанный сертификат) с токен-аутентификацией.
  - POST /v1/peer {"user_id": "..."} -> создаёт (или возвращает существующий)
    peer и отдаёт клиентский конфиг в base64 (тот же формат, что my_phone.conf).
  - GET /v1/health -> статус агента.
  - CLI-режим --cleanup: удаление давно неактивных пиров (см. setup_agent.sh).

Только стандартная библиотека Python 3.8+. Конфигурация через env
(EnvironmentFile systemd-юнита): см. /etc/gwvpn-agent/agent.env.
"""

import base64
import fcntl
import hashlib
import ipaddress
import json
import logging
import os
import re
import ssl
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ------------------------------------------------------------------------------
# Конфигурация (через переменные окружения)
# ------------------------------------------------------------------------------
AGENT_TOKEN = os.environ.get("AGENT_TOKEN", "")
AGENT_PORT = int(os.environ.get("AGENT_PORT", "39744"))
AGENT_CERT = os.environ.get("AGENT_CERT", "/etc/gwvpn-agent/agent.crt")
AGENT_KEY = os.environ.get("AGENT_KEY", "/etc/gwvpn-agent/agent.key")

AWG_DIR = os.environ.get("AWG_DIR", "/root/awg")
KEYS_DIR = os.environ.get("KEYS_DIR", os.path.join(AWG_DIR, "keys"))
CONFIG_FILE = os.environ.get("CONFIG_FILE", os.path.join(AWG_DIR, "awgsetup_cfg.init"))
SERVER_CONF_FILE = os.environ.get("SERVER_CONF_FILE", "/etc/amnezia/amneziawg/awg0.conf")
LOCK_FILE = os.path.join(AWG_DIR, ".awg_config.lock")  # тот же lock, что у bash-скриптов

# Для --cleanup: инвалидация кэша конфигов в API перед удалением пиров
API_BASE = os.environ.get("API_BASE", "https://api-gwvpn-app.vpnhub.xyz")

PEER_PREFIX = "u_"  # имена пиров, созданных агентом

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    stream=sys.stdout,
)
log = logging.getLogger("gwvpn-awg-agent")

_provision_lock = threading.Lock()

# Кэш содержимого awg0.conf: {"key": (mtime_ns, size) | None, "data": str}.
# Сбрасывается при каждой записи файла (invalidate_conf_cache).
_conf_cache = {"key": None, "data": ""}
# Кэш множества занятых туннельных IP, привязан к тому же ключу файла.
_used_ips_cache = {"key": None, "data": None}


# ------------------------------------------------------------------------------
# Утилиты
# ------------------------------------------------------------------------------
def peer_name_for_user(user_id: str) -> str:
    """Детерминированное безопасное имя пира из userId."""
    digest = hashlib.sha256(user_id.strip().encode("utf-8")).hexdigest()
    return PEER_PREFIX + digest[:16]


def load_awg_params() -> dict:
    """Парсит awgsetup_cfg.init (строки KEY="VALUE" / KEY=VALUE).

    Если cfg-файла нет (установка без bivlked-скриптов, например
    vps/awg31-install.sh) — извлекает параметры обфускации прямо из
    серверного awg0.conf, включая 3.1-параметры (I1, HeaderProtectionKey,
    ContentPaddingAddition).
    """
    params = {}
    try:
        with open(CONFIG_FILE, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, value = line.partition("=")
                key = key.strip()
                value = value.strip().strip('"').strip("'")
                params[key] = value
        if params:
            return params
    except OSError:
        pass

    conf = read_server_conf()

    def grab(key):
        m = re.search(r"^%s\s*=\s*(.+?)\s*$" % re.escape(key), conf, re.MULTILINE)
        return m.group(1) if m else ""

    mapping = {
        "AWG_Jc": "Jc", "AWG_Jmin": "Jmin", "AWG_Jmax": "Jmax",
        "AWG_S1": "S1", "AWG_S2": "S2", "AWG_S3": "S3", "AWG_S4": "S4",
        "AWG_H1": "H1", "AWG_H2": "H2", "AWG_H3": "H3", "AWG_H4": "H4",
        "AWG_I1": "I1", "AWG_I2": "I2", "AWG_I3": "I3", "AWG_I4": "I4", "AWG_I5": "I5",
        "AWG_HPK": "HeaderProtectionKey", "AWG_CPA": "ContentPaddingAddition",
        "AWG_PORT": "ListenPort",
    }
    for pkey, confkey in mapping.items():
        value = grab(confkey)
        if value:
            params[pkey] = value
    return params


def run(cmd, input_text=None, timeout=20):
    """Запуск команды, возвращает stdout. Бросает исключение при ошибке."""
    res = subprocess.run(
        cmd,
        input=input_text,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if res.returncode != 0:
        raise RuntimeError(
            "command failed: %s rc=%s stderr=%s" % (cmd, res.returncode, res.stderr.strip()[:500])
        )
    return res.stdout


def read_server_conf() -> str:
    """Читает awg0.conf с кэшем по (mtime, size).

    Файл на 16k пиров — это ~84k строк / несколько МБ. За один запрос агент
    читает его несколько раз (peer_exists, allocate_ip, count), и каждый
    проход стоит заметное время. Кэш отдаёт уже прочитанное содержимое,
    пока файл не изменился.
    """
    try:
        st = os.stat(SERVER_CONF_FILE)
        key = (st.st_mtime_ns, st.st_size)
    except OSError:
        key = None
    if key is not None and _conf_cache["key"] == key:
        return _conf_cache["data"]
    with open(SERVER_CONF_FILE, "r", encoding="utf-8", errors="replace") as f:
        data = f.read()
    if key is not None:
        _conf_cache["key"] = key
        _conf_cache["data"] = data
    return data


def invalidate_conf_cache():
    _conf_cache["key"] = None
    _conf_cache["data"] = ""
    _used_ips_cache["key"] = None
    _used_ips_cache["data"] = None


def get_server_network():
    """Возвращает (server_ip, network) из Address в awg0.conf."""
    conf = read_server_conf()
    m = re.search(r"^Address\s*=\s*([0-9.]+)/(\d+)", conf, re.MULTILINE)
    if not m:
        raise RuntimeError("Address not found in %s" % SERVER_CONF_FILE)
    server_ip = ipaddress.IPv4Address(m.group(1))
    network = ipaddress.IPv4Network("%s/%s" % (m.group(1), m.group(2)), strict=False)
    return server_ip, network


def get_used_ips() -> set:
    """Все туннельные IP, занятые пирами (AllowedIPs x.x.x.x/32).

    Результат кэшируется вместе с содержимым конфига: на 16k пиров
    построение множества — это десятки тысяч объектов на каждый вызов.
    """
    cached = _used_ips_cache.get("data")
    if cached is not None and _used_ips_cache.get("key") == _conf_cache["key"]:
        return cached
    used = set()
    conf = read_server_conf()
    for m in re.finditer(r"^AllowedIPs\s*=\s*([0-9.]+)/32", conf, re.MULTILINE):
        used.add(ipaddress.IPv4Address(m.group(1)))
    _used_ips_cache["key"] = _conf_cache["key"]
    _used_ips_cache["data"] = used
    return used


def allocate_ip() -> str:
    """Первый свободный IP в туннельной подсети (работает с любой маской, не только /24)."""
    server_ip, network = get_server_network()
    used = get_used_ips()
    used.add(server_ip)
    for host in network.hosts():
        if host not in used:
            return str(host)
    raise RuntimeError("no free IPs left in tunnel subnet %s" % network)


def get_endpoint(params: dict) -> str:
    """Endpoint сервера: из awgsetup_cfg.init, из существующего клиентского конфига, либо внешний lookup."""
    ep = params.get("AWG_ENDPOINT", "").strip()
    if ep:
        return ep
    # из любого существующего клиентского конфига (например my_phone.conf)
    try:
        for fname in sorted(os.listdir(AWG_DIR)):
            if not fname.endswith(".conf") or fname == "awg0.conf":
                continue
            with open(os.path.join(AWG_DIR, fname), "r", encoding="utf-8", errors="replace") as f:
                m = re.search(r"^Endpoint\s*=\s*(\S+):\d+\s*$", f.read(), re.MULTILINE)
                if m:
                    return m.group(1)
    except OSError:
        pass
    # внешний lookup (как в установщике)
    for url in ("https://checkip.amazonaws.com/", "https://api.ipify.org", "https://icanhazip.com"):
        try:
            with urllib.request.urlopen(url, timeout=5) as resp:
                ip = resp.read().decode().strip()
                if re.match(r"^\d+\.\d+\.\d+\.\d+$", ip):
                    return ip
        except Exception:
            continue
    raise RuntimeError("cannot determine server endpoint")


def render_client_config(params: dict, client_ip: str, client_privkey: str,
                         server_pubkey: str, endpoint: str) -> str:
    """Точно тот же формат, что render_client_config из awg_common.sh."""
    lines = [
        "[Interface]",
        "PrivateKey = %s" % client_privkey,
        "Address = %s/32" % client_ip,
        "DNS = 1.1.1.1",
        "MTU = 1280",
        "Jc = %s" % params["AWG_Jc"],
        "Jmin = %s" % params["AWG_Jmin"],
        "Jmax = %s" % params["AWG_Jmax"],
        "S1 = %s" % params["AWG_S1"],
        "S2 = %s" % params["AWG_S2"],
        "S3 = %s" % params["AWG_S3"],
        "S4 = %s" % params["AWG_S4"],
        "H1 = %s" % params["AWG_H1"],
        "H2 = %s" % params["AWG_H2"],
        "H3 = %s" % params["AWG_H3"],
        "H4 = %s" % params["AWG_H4"],
    ]
    if params.get("AWG_I1"):
        lines.append("I1 = %s" % params["AWG_I1"])
    if params.get("AWG_I2"):
        lines.append("I2 = %s" % params["AWG_I2"])
    if params.get("AWG_I3"):
        lines.append("I3 = %s" % params["AWG_I3"])
    if params.get("AWG_I4"):
        lines.append("I4 = %s" % params["AWG_I4"])
    if params.get("AWG_I5"):
        lines.append("I5 = %s" % params["AWG_I5"])
    # Параметры 3.x: header protection и паддинг — обязательны к переносу,
    # иначе клиент будет общаться по 2.0-линии, а сервер 3.1 их отвергнет.
    if params.get("AWG_HPK"):
        lines.append("HeaderProtectionKey = %s" % params["AWG_HPK"])
    if params.get("AWG_CPA"):
        lines.append("ContentPaddingAddition = %s" % params["AWG_CPA"])
    allowed_ips = params.get("ALLOWED_IPS") or "0.0.0.0/0"
    lines += [
        "",
        "[Peer]",
        "PublicKey = %s" % server_pubkey,
        "Endpoint = %s:%s" % (endpoint, params.get("AWG_PORT", "39743")),
        "AllowedIPs = %s" % allowed_ips,
        "PersistentKeepalive = 33",
        "",
    ]
    return "\n".join(lines)


def apply_config():
    """Полный syncconf — ТОЛЬКО как аварийный fallback.

    awg-quick strip переваривает весь awg0.conf (десятки тысяч пиров) и на
    16k пиров занимает ~13 секунд. Для штатных операций используйте
    set_peer_live()/remove_peer_live() — они обновляют ядро точечно.
    """
    try:
        stripped = run(["awg-quick", "strip", "awg0"])
        run(["awg", "syncconf", "awg0", "/dev/stdin"], input_text=stripped)
        log.info("config applied via syncconf")
    except Exception as e:
        log.warning("syncconf failed (%s), falling back to service restart", e)
        run(["systemctl", "restart", "awg-quick@awg0"], timeout=60)


def set_peer_live(pubkey: str, client_ip: str) -> bool:
    """Добавляет/обновляет один пир в ядре точечно — O(1), миллисекунды.

    Это ключевая замена полному apply_config(): `awg set` меняет только один
    пир, не читая и не парся весь конфиг. Возвращает True при успехе; при
    ошибке откатывается на полный syncconf, чтобы туннель точно поднялся.

    Набор полей ровно как в серверном блоке [Peer] — AllowedIPs без
    PersistentKeepalive (keepalive шлёт клиент, серверу он не нужен и
    на 16k пиров был бы лишним трафиком).
    """
    try:
        run(["awg", "set", "awg0", "peer", pubkey,
             "allowed-ips", "%s/32" % client_ip], timeout=15)
        return True
    except Exception as e:
        log.warning("awg set peer failed (%s) — falling back to full syncconf", e)
        apply_config()
        return False


def remove_peer_live(pubkey: str) -> None:
    """Удаляет один пир из ядра точечно."""
    try:
        run(["awg", "set", "awg0", "peer", pubkey, "remove"], timeout=15)
    except Exception as e:
        # Пира могло уже не быть в ядре — для чистки это не ошибка.
        log.debug("awg remove peer %.12s: %s", pubkey, e)


def peer_exists(name: str) -> bool:
    return ("#_Name = %s" % name) in read_server_conf()


def ensure_peer(user_id: str) -> dict:
    """Идемпотентно создаёт peer для userId и возвращает {'name':..., 'config': <base64>}."""
    name = peer_name_for_user(user_id)
    conf_path = os.path.join(AWG_DIR, "%s.conf" % name)

    with _provision_lock:
        # Межпроцессная блокировка — совместима с flock в awg_common.sh
        with open(LOCK_FILE, "a") as lock_fh:
            fcntl.flock(lock_fh, fcntl.LOCK_EX)
            try:
                if peer_exists(name) and os.path.isfile(conf_path):
                    with open(conf_path, "r", encoding="utf-8", errors="replace") as f:
                        cfg = f.read()
                    return {"name": name, "config": base64.b64encode(cfg.encode()).decode(), "created": False}

                params = load_awg_params()
                for required in ("AWG_Jc", "AWG_Jmin", "AWG_Jmax", "AWG_S1", "AWG_S2",
                                 "AWG_S3", "AWG_S4", "AWG_H1", "AWG_H2", "AWG_H3", "AWG_H4"):
                    if required not in params:
                        raise RuntimeError("missing %s in %s" % (required, CONFIG_FILE))

                # Битый/половинчатый peer — убираем и создаём заново
                if peer_exists(name) and not os.path.isfile(conf_path):
                    _remove_peer_block(name)

                # Ключи
                privkey = run(["awg", "genkey"]).strip()
                pubkey = run(["awg", "pubkey"], input_text=privkey).strip()
                with open(os.path.join(AWG_DIR, "server_public.key"), "r") as f:
                    server_pubkey = f.read().strip()

                client_ip = allocate_ip()
                endpoint = get_endpoint(params)
                client_cfg = render_client_config(params, client_ip, privkey, server_pubkey, endpoint)

                # Сохраняем ключи и конфиг (как awg_common.sh — чтобы manage-скрипт их видел)
                os.makedirs(KEYS_DIR, exist_ok=True)
                _write_secret(os.path.join(KEYS_DIR, "%s.private" % name), privkey + "\n")
                _write_secret(os.path.join(KEYS_DIR, "%s.public" % name), pubkey + "\n")
                _write_secret(conf_path, client_cfg)

                # Добавляем peer в серверный конфиг (атомарно)
                server_conf = read_server_conf()
                peer_block = "\n[Peer]\n#_Name = %s\nPublicKey = %s\nAllowedIPs = %s/32\n" % (
                    name, pubkey, client_ip)
                tmp_path = SERVER_CONF_FILE + ".tmp_agent"
                with open(tmp_path, "w", encoding="utf-8") as f:
                    f.write(server_conf.rstrip("\n") + "\n" + peer_block)
                os.chmod(tmp_path, 0o600)
                os.replace(tmp_path, SERVER_CONF_FILE)
                invalidate_conf_cache()

                # Ядро обновляем точечно (миллисекунды), а не полным
                # syncconf по всему конфигу (13+ секунд на 16k пиров).
                set_peer_live(pubkey, client_ip)
                log.info("peer %s created (ip=%s) for user_id hash", name, client_ip)
                return {"name": name, "config": base64.b64encode(client_cfg.encode()).decode(), "created": True}
            finally:
                fcntl.flock(lock_fh, fcntl.LOCK_UN)


def _write_secret(path: str, content: str):
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    os.chmod(path, 0o600)


def _remove_peer_block(name: str):
    """Удаляет блок [Peer] с #_Name = name из awg0.conf (без apply)."""
    conf = read_server_conf()
    blocks = re.split(r"(?=^\[)", conf, flags=re.MULTILINE)
    kept = [b for b in blocks if not (b.startswith("[Peer]") and ("#_Name = %s" % name) in b)]
    new_conf = "".join(kept)
    new_conf = re.sub(r"\n{3,}", "\n\n", new_conf)
    tmp_path = SERVER_CONF_FILE + ".tmp_agent"
    with open(tmp_path, "w", encoding="utf-8") as f:
        f.write(new_conf)
    os.chmod(tmp_path, 0o600)
    os.replace(tmp_path, SERVER_CONF_FILE)
    invalidate_conf_cache()


def _remove_peer_blocks(names: set) -> None:
    """Удаляет сразу пачку блоков [Peer] за одну перезапись файла.

    Раньше cleanup звал _remove_peer_block() в цикле — это N полных
    перезаписей конфига (на 2773 пирах — тысячи проходов по 84k строк).
    """
    conf = read_server_conf()
    blocks = re.split(r"(?=^\[)", conf, flags=re.MULTILINE)
    kept = []
    for b in blocks:
        if b.startswith("[Peer]"):
            m = re.search(r"^#_Name = (\S+)", b, re.MULTILINE)
            if m and m.group(1) in names:
                continue
        kept.append(b)
    new_conf = re.sub(r"\n{3,}", "\n\n", "".join(kept))
    tmp_path = SERVER_CONF_FILE + ".tmp_agent"
    with open(tmp_path, "w", encoding="utf-8") as f:
        f.write(new_conf)
    os.chmod(tmp_path, 0o600)
    os.replace(tmp_path, SERVER_CONF_FILE)
    invalidate_conf_cache()


def count_agent_peers() -> int:
    return len(re.findall(r"^#_Name = %s" % re.escape(PEER_PREFIX), read_server_conf(), re.MULTILINE))


def collect_stats() -> dict:
    """Метрики для load% в API: активные туннели и суммарный трафик.

    Источник — `awg show awg0 transfer` (накопительные счётчики байт по пирам,
    cp/1 скорее всего 0 -> игнорируем) + число пиров с ненулевым handshake-периодом
    считать не нужно: активным считаем пир с transfer > 0 за жизнь. API считает
    скорость из дельт этих накопительных значений — как у GW-бриджа.
    """
    out = run(["awg", "show", "awg0", "transfer"])
    tunnels = 0
    bytes_tx = 0
    bytes_rx = 0
    for line in out.strip().splitlines():
        parts = line.split()
        if len(parts) < 3:
            continue
        try:
            rx = int(parts[1])
            tx = int(parts[2])
        except ValueError:
            continue
        # пир с нулевым transfer ещё не обменивался данными — не «активный»
        if rx > 0 or tx > 0:
            tunnels += 1
        bytes_rx += rx
        bytes_tx += tx
    return {
        "active_tunnels": tunnels,
        "bytes_tx": bytes_tx,
        "bytes_rx": bytes_rx,
        "total_peers": count_agent_peers(),
    }


# ------------------------------------------------------------------------------
# Cleanup (CLI): удаление пиров без handshake дольше N дней
# ------------------------------------------------------------------------------
def cleanup(days: int) -> int:
    """
    Удаляет агентские пиры (u_*), у которых последний handshake старше N дней
    (или handshake никогда не было, а конфиг создан более N дней назад).
    ПЕРЕД удалением инвалидирует кэш конфигов в API — если API недоступен,
    чистка пропускается (иначе юзеры получили бы из кэша мёртвый конфиг).
    """
    cutoff = time.time() - days * 86400

    # pubkey -> name из серверного конфига
    conf = read_server_conf()
    name_by_pubkey = {}
    for block in re.split(r"(?=^\[Peer\])", conf, flags=re.MULTILINE):
        if not block.startswith("[Peer]"):
            continue
        nm = re.search(r"^#_Name = (\S+)", block, re.MULTILINE)
        pk = re.search(r"^PublicKey\s*=\s*(\S+)", block, re.MULTILINE)
        if nm and pk and nm.group(1).startswith(PEER_PREFIX):
            name_by_pubkey[pk.group(1)] = nm.group(1)

    # последние handshake из ядра
    handshakes = {}
    out = run(["awg", "show", "awg0", "latest-handshakes"])
    for line in out.strip().splitlines():
        parts = line.split()
        if len(parts) == 2:
            handshakes[parts[0]] = int(parts[1])

    stale = []
    for pubkey, name in name_by_pubkey.items():
        hs = handshakes.get(pubkey, 0)
        if hs > 0:
            if hs < cutoff:
                stale.append(name)
        else:
            conf_path = os.path.join(AWG_DIR, "%s.conf" % name)
            try:
                if os.path.getmtime(conf_path) < cutoff:
                    stale.append(name)
            except OSError:
                stale.append(name)

    if not stale:
        log.info("cleanup: nothing to remove (peers=%d)", len(name_by_pubkey))
        return 0

    # 1) Инвалидируем кэш в API (обязательно ДО удаления пиров)
    server_ip = get_endpoint(load_awg_params())
    payload = json.dumps({"ip_address": server_ip, "names": stale}).encode()
    req = urllib.request.Request(
        API_BASE.rstrip("/") + "/vpn/api/v1/bot/user_awg_configs/invalidate",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = json.loads(resp.read().decode())
            if body.get("success") != 1:
                raise RuntimeError("API returned %s" % body)
    except Exception as e:
        log.error("cleanup aborted: cannot invalidate API cache: %s", e)
        return 1

    # 2) Удаляем пиры локально
    with open(LOCK_FILE, "a") as lock_fh:
        fcntl.flock(lock_fh, fcntl.LOCK_EX)
        try:
            # Из ядра — точечно (O(1) на пир), файл — одной перезаписью.
            name_to_pubkey = {name: pk for pk, name in name_by_pubkey.items()}
            for name in stale:
                pk = name_to_pubkey.get(name)
                if pk:
                    remove_peer_live(pk)
                for path in (
                    os.path.join(AWG_DIR, "%s.conf" % name),
                    os.path.join(AWG_DIR, "%s.png" % name),
                    os.path.join(AWG_DIR, "%s.vpnuri" % name),
                    os.path.join(KEYS_DIR, "%s.private" % name),
                    os.path.join(KEYS_DIR, "%s.public" % name),
                ):
                    try:
                        os.remove(path)
                    except OSError:
                        pass
            _remove_peer_blocks(set(stale))
            log.info("cleanup: removed %d stale peers", len(stale))
        finally:
            fcntl.flock(lock_fh, fcntl.LOCK_UN)

    log.info("cleanup: done, %d peers removed", len(stale))
    return 0


# ------------------------------------------------------------------------------
# HTTP-сервер
# ------------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    server_version = "GWVPN-AWG-Agent/1.0"
    protocol_version = "HTTP/1.1"

    def _send(self, code: int, obj: dict):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        token = self.headers.get("X-Auth-Token", "")
        return bool(AGENT_TOKEN) and token == AGENT_TOKEN

    def do_GET(self):
        if not self._authorized():
            return self._send(401, {"success": 0, "message": "unauthorized"})
        if self.path == "/v1/health":
            try:
                return self._send(200, {"success": 1, "peers": count_agent_peers()})
            except Exception as e:
                log.exception("health failed")
                return self._send(500, {"success": 0, "message": str(e)})
        if self.path == "/v1/stats":
            try:
                return self._send(200, collect_stats())
            except Exception as e:
                log.exception("stats failed")
                return self._send(500, {"success": 0, "message": str(e)})
        return self._send(404, {"success": 0, "message": "not found"})

    def do_POST(self):
        if not self._authorized():
            return self._send(401, {"success": 0, "message": "unauthorized"})
        if self.path != "/v1/peer":
            return self._send(404, {"success": 0, "message": "not found"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 65536:
                return self._send(400, {"success": 0, "message": "bad body"})
            data = json.loads(self.rfile.read(length).decode("utf-8"))
            user_id = str(data.get("user_id", "")).strip()
            if not user_id or len(user_id) > 256:
                return self._send(400, {"success": 0, "message": "user_id required"})
            result = ensure_peer(user_id)
            return self._send(200, {"success": 1, **result})
        except Exception as e:
            log.exception("provisioning failed")
            return self._send(500, {"success": 0, "message": str(e)})

    def log_message(self, fmt, *args):  # тише в journald
        log.debug("%s %s", self.address_string(), fmt % args)


def serve():
    if not AGENT_TOKEN:
        log.error("AGENT_TOKEN is not set, refusing to start")
        sys.exit(1)
    httpd = ThreadingHTTPServer(("0.0.0.0", AGENT_PORT), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(certfile=AGENT_CERT, keyfile=AGENT_KEY)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    log.info("agent listening on https://0.0.0.0:%d", AGENT_PORT)
    httpd.serve_forever()


def main():
    if "--cleanup" in sys.argv:
        days = 30
        for i, arg in enumerate(sys.argv):
            if arg == "--days" and i + 1 < len(sys.argv):
                days = int(sys.argv[i + 1])
        sys.exit(cleanup(days))
    serve()


if __name__ == "__main__":
    main()
