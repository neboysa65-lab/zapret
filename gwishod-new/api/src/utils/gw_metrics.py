import asyncio
import logging
import time

import httpx

logger = logging.getLogger(__name__)

# Онлайн и нагрузка GW берутся напрямую с нод: бридж на каждом сервере держит
# счётчик активных туннелей и суммарный трафик (bytes_tx/bytes_rx) и отдаёт их
# на GET /gw-stats?token=... (см. vps/gw-ws-bridge.py). Это серверная правда —
# как ipsec_clients у IKEv2. Хартбиты приложения остаются запасным источником
# онлайна, если токен не задан или нода не отвечает.
#
# load% считается так же, как у IKEv2 (src/utils/server_metrics.py):
# скорость (дельта байт) против канала 1 Гбит/с, сглаживание EWMA.
# Для старых бриджей без bytes_* — фолбэк на число туннелей.

CACHE_TTL_SECONDS = 20.0
REQUEST_TIMEOUT_SECONDS = 4.0

# Раз в сколько секунд опрашивать ноды и пересчитывать load%.
GW_POLL_INTERVAL_SECONDS = 60
# Сколько байт/с считаются 100% нагрузки (1 Гбит/с) — как у IKEv2.
MAX_BYTES_PER_SEC = 125_000_000.0
# Фолбэк-ёмкость ноды в туннелях (когда бридж ещё не отдаёт bytes_*):
# 50 одновременных туннелей = 100%.
GW_MAX_TUNNELS = 50

_STATS_CACHE: dict[str, tuple[float, dict | None]] = {}


async def get_gw_stats(host: str, token: str) -> dict | None:
    """Статистика с ноды: active_tunnels, bytes_tx, bytes_rx, uptime_seconds.

    None — нода недоступна / токен не задан. Результат кэшируется на
    CACHE_TTL_SECONDS, чтобы /bot/servers/stats и фоновый цикл нагрузки
    не долбили ноду параллельными запросами.
    """
    if not host or not token:
        return None

    cached = _STATS_CACHE.get(f"{host}:{token}")
    now = time.monotonic()
    if cached and now - cached[0] < CACHE_TTL_SECONDS:
        return cached[1]

    stats: dict | None = None
    for scheme in ("http", "https"):
        try:
            async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT_SECONDS, verify=False) as client:
                resp = await client.get(
                    f"{scheme}://{host}/gw-stats",
                    params={"token": token},
                )
            if resp.status_code == 200:
                data = resp.json()
                if isinstance(data.get("active_tunnels"), int):
                    stats = {
                        "active_tunnels": data["active_tunnels"],
                        "bytes_tx": data.get("bytes_tx"),
                        "bytes_rx": data.get("bytes_rx"),
                    }
                break
            if resp.status_code in (403, 400):
                break
        except Exception:
            continue

    _STATS_CACHE[f"{host}:{token}"] = (now, stats)
    return stats


async def get_gw_tunnel_count(host: str, token: str) -> int | None:
    """Число активных туннелей с ноды или None (недоступна/токен не задан)."""
    stats = await get_gw_stats(host, token)
    return stats["active_tunnels"] if stats else None


def _clamp_load(pct: int) -> int:
    return max(1, min(100, pct))


def _ewma(raw_pct: int, old_load: int) -> int:
    # Та же формула сглаживания, что у IKEv2/VLESS в server_metrics.py.
    return _clamp_load(int(0.4 * raw_pct + 0.6 * old_load))


async def update_gw_load_once() -> None:
    """Один опрос всех GW-нод: пишет load% в SERVER_METRICS_STATE по ip ноды.

    Ключ — s.ip_address (реальный IP ноды, как в конфиге клиента), поэтому
    /user/servers/load отдаёт эти значения без дополнительных маппингов.
    """
    from src.db.v3.gw.server_pojo import ServerPojo as GwServerPojo
    from src.utils.server_metrics import SERVER_METRICS_STATE, save_state

    try:
        servers = await GwServerPojo.find_all()
    except Exception as e:
        logger.error(f"GW load: cannot list servers: {e}")
        return

    now = time.time()
    changed = False

    for s in servers:
        if not s.ip_address:
            continue
        # ip_address — домен ноды: Cloudflare роутит /gw-stats по SNI.
        # proxy_host — CF-IP: без Host-заголовка бридж не отвечает (403).
        host = s.ip_address or s.proxy_host
        try:
            stats = await get_gw_stats(host, s.stats_token)
        except Exception as e:
            logger.warning(f"GW load FAILED: {s.ip_address} -> {e}")
            continue

        if stats is None:
            # Нода недоступна: старую запись не трогаем — как у IKEv2,
            # в /user/servers/load останется последнее известное значение.
            continue

        old = SERVER_METRICS_STATE.get(s.ip_address)
        old_load = (old or {}).get("load_percent", 1)

        tunnels = stats["active_tunnels"]
        bytes_tx = stats.get("bytes_tx")
        bytes_rx = stats.get("bytes_rx")

        entry = dict(old or {})
        entry["gw_tunnels"] = tunnels

        has_bytes = isinstance(bytes_tx, (int, float)) and isinstance(bytes_rx, (int, float))
        if has_bytes:
            # Полный аналог fetch_and_update_metric у IKEv2: скорость из дельты
            # накопительных счётчиков бриджа против канала 1 Гбит/с.
            prev_tx = (old or {}).get("tx_bytes")
            prev_rx = (old or {}).get("rx_bytes")
            prev_ts = (old or {}).get("timestamp", 0)
            dt = now - prev_ts if prev_ts else 0

            raw_pct = None
            if old and prev_tx is not None and prev_rx is not None and 0 < dt < 600:
                # Бридж перезапустился и счётчики обнулились — считаем,
                # что это разовый сброс, и просто обновляем базу без расчёта.
                if bytes_tx >= prev_tx and bytes_rx >= prev_rx:
                    tx_rate = (bytes_tx - prev_tx) / dt
                    rx_rate = (bytes_rx - prev_rx) / dt
                    max_rate = max(tx_rate, rx_rate)
                    raw_pct = _clamp_load(int((max_rate / MAX_BYTES_PER_SEC) * 100))

            load_pct = _ewma(raw_pct, old_load) if raw_pct is not None else old_load

            entry.update({
                "timestamp": now,
                "tx_bytes": bytes_tx,
                "rx_bytes": bytes_rx,
                "load_percent": load_pct,
            })
        else:
            # Старый бридж без bytes_*: фолбэк на число туннелей.
            raw_pct = _clamp_load(int(tunnels * 100 / GW_MAX_TUNNELS))
            entry["load_percent"] = _ewma(raw_pct, old_load)

        SERVER_METRICS_STATE[s.ip_address] = entry
        changed = True
        logger.info(
            f"GW load OK: {s.ip_address} -> load={entry['load_percent']}%, "
            f"tunnels={tunnels}, source={'bytes' if has_bytes else 'tunnels'}"
        )

    if changed:
        save_state()


async def update_gw_load_loop():
    """Фоновый цикл нагрузки GW-нод — пара к update_server_metrics_loop (IKEv2)."""
    logger.info(f"Starting GW load loop (every {GW_POLL_INTERVAL_SECONDS} sec)...")
    while True:
        try:
            await update_gw_load_once()
        except Exception as e:
            logger.error(f"Error in update_gw_load_loop: {e}")
        await asyncio.sleep(GW_POLL_INTERVAL_SECONDS)


# ══════════════════════════════════════════════════════════════════════════
#  AWG-ноды: load% из provisioning-агента (GET /v1/stats на порту агента).
#  Формат ответа совпадает с GW-бриджем (active_tunnels, bytes_tx, bytes_rx),
#  поэтому пересчёт нагрузки — тот же код, что и для GW выше.
# ══════════════════════════════════════════════════════════════════════════

AWG_POLL_INTERVAL_SECONDS = 60
AWG_AGENT_PORT = 39744


async def get_awg_stats(host: str, token: str) -> dict | None:
    """Метрики с provisioning-агента AWG-ноды. None — недоступна/токена нет."""
    if not host or not token:
        return None

    cached = _STATS_CACHE.get(f"awg:{host}:{token}")
    now = time.monotonic()
    if cached and now - cached[0] < CACHE_TTL_SECONDS:
        return cached[1]

    stats: dict | None = None
    try:
        async with httpx.AsyncClient(timeout=REQUEST_TIMEOUT_SECONDS, verify=False) as client:
            resp = await client.get(
                f"https://{host}:{AWG_AGENT_PORT}/v1/stats",
                headers={"X-Auth-Token": token},
            )
        if resp.status_code == 200:
            data = resp.json()
            if isinstance(data.get("active_tunnels"), int):
                stats = {
                    "active_tunnels": data["active_tunnels"],
                    "bytes_tx": data.get("bytes_tx"),
                    "bytes_rx": data.get("bytes_rx"),
                }
    except Exception:
        pass

    _STATS_CACHE[f"awg:{host}:{token}"] = (now, stats)
    return stats


async def update_awg_load_once() -> None:
    """Опрос AWG-нод: пишет load% в SERVER_METRICS_STATE по ip ноды."""
    from src.db.v3.awg.server_pojo import ServerPojo as AwgServerPojo
    from src.db.v3.awg.agent_pojo import AgentPojo
    from src.utils.server_metrics import SERVER_METRICS_STATE, save_state

    try:
        servers = await AwgServerPojo.find_all()
    except Exception as e:
        logger.error(f"AWG load: cannot list servers: {e}")
        return

    # токены агентов: ip_address записи = ip агента (ключ в agents_awg)
    try:
        agents = {a.ip_address: a for a in await AgentPojo.find_all() if a.token}
    except Exception as e:
        logger.error(f"AWG load: cannot list agents: {e}")
        return

    now = time.time()
    changed = False

    for s in servers:
        if not s.ip_address:
            continue
        agent = agents.get(s.ip_address)
        if agent is None:
            # агента нет: по ноде нечем опросить — старую запись не трогаем
            continue
        host = s.ip_address
        try:
            stats = await get_awg_stats(host, agent.token)
        except Exception as e:
            logger.warning(f"AWG load FAILED: {s.ip_address} -> {e}")
            continue

        if stats is None:
            continue

        old = SERVER_METRICS_STATE.get(s.ip_address)
        old_load = (old or {}).get("load_percent", 1)

        tunnels = stats["active_tunnels"]
        bytes_tx = stats.get("bytes_tx")
        bytes_rx = stats.get("bytes_rx")

        entry = dict(old or {})
        entry["awg_tunnels"] = tunnels

        has_bytes = isinstance(bytes_tx, (int, float)) and isinstance(bytes_rx, (int, float))
        if has_bytes:
            prev_tx = (old or {}).get("awg_tx_bytes")
            prev_rx = (old or {}).get("awg_rx_bytes")
            prev_ts = (old or {}).get("awg_timestamp", 0)
            dt = now - prev_ts if prev_ts else 0

            raw_pct = None
            if old and prev_tx is not None and prev_rx is not None and 0 < dt < 600:
                if bytes_tx >= prev_tx and bytes_rx >= prev_rx:
                    tx_rate = (bytes_tx - prev_tx) / dt
                    rx_rate = (bytes_rx - prev_rx) / dt
                    max_rate = max(tx_rate, rx_rate)
                    raw_pct = _clamp_load(int((max_rate / MAX_BYTES_PER_SEC) * 100))

            load_pct = _ewma(raw_pct, old_load) if raw_pct is not None else old_load

            entry.update({
                "awg_timestamp": now,
                "awg_tx_bytes": bytes_tx,
                "awg_rx_bytes": bytes_rx,
                "load_percent": load_pct,
            })
        else:
            raw_pct = _clamp_load(int(tunnels * 100 / GW_MAX_TUNNELS))
            entry["load_percent"] = _ewma(raw_pct, old_load)

        SERVER_METRICS_STATE[s.ip_address] = entry
        changed = True
        logger.info(
            f"AWG load OK: {s.ip_address} -> load={entry['load_percent']}%, "
            f"tunnels={tunnels}, source={'bytes' if has_bytes else 'tunnels'}"
        )

    if changed:
        save_state()


async def update_awg_load_loop():
    logger.info(f"Starting AWG load loop (every {AWG_POLL_INTERVAL_SECONDS} sec)...")
    while True:
        try:
            await update_awg_load_once()
        except Exception as e:
            logger.error(f"Error in update_awg_load_loop: {e}")
        await asyncio.sleep(AWG_POLL_INTERVAL_SECONDS)
