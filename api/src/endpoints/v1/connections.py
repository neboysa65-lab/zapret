import logging
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Request

logger = logging.getLogger(__name__)

router = APIRouter()

from src.config import get_db  # central config
COLLECTION_NAME = "active_connections"


async def _get_collection():
    return get_db()[COLLECTION_NAME]


def _normalize_protocol(value: str) -> str:
    protocol = (value or "").strip().lower()
    if protocol in ("awg", "amneziawg", "amnezia_wg", "amnezia-wg"):
        return "awg"
    if protocol == "vless":
        return "vless"
    return "ikev2"


@router.post("/vpn/api/v1/user/connection/update")
async def update_connection(request: Request):
    body = await request.body()
    params: dict[str, str] = {}
    for pair in body.decode("utf-8").split("&"):
        parts = pair.split("=", 1)
        if len(parts) == 2:
            params[parts[0]] = parts[1]

    user_id = params.get("userId", "").strip()
    server_ip = params.get("serverIp", "").strip()
    protocol = _normalize_protocol(params.get("protocol", "ikev2"))
    action = params.get("action", "connect").strip()

    if not user_id:
        return {"success": 0, "message": "userId required"}

    if action in ("connect", "heartbeat") and not server_ip:
        return {"success": 0, "message": "serverIp required for connect/heartbeat"}

    collection = await _get_collection()
    now = datetime.now(timezone.utc)

    if action == "connect":
        await collection.update_one(
            {"userId": user_id},
            {"$set": {
                "userId": user_id,
                "serverIp": server_ip,
                "protocol": protocol,
                "connectedAt": now,
                "lastSeen": now,
            }},
            upsert=True,
        )
    elif action == "disconnect":
        await collection.delete_one({"userId": user_id})
    elif action == "heartbeat":
        await collection.update_one(
            {"userId": user_id},
            {"$set": {"lastSeen": now, "serverIp": server_ip, "protocol": protocol}},
        )

    return {"success": 1, "message": "ok"}


@router.get("/vpn/api/v1/bot/servers/stats")
async def get_servers_stats():
    from src.db.v3.servers.server_pojo import ServerPojo as Ikev2ServerPojo
    from src.db.v3.a3xui.server_pojo import ServerPojo as VlessServerPojo
    from src.db.v3.awg.server_pojo import ServerPojo as AwgServerPojo

    ikev2_servers = await Ikev2ServerPojo.find_all()
    vless_servers = await VlessServerPojo.find_all()
    awg_servers = await AwgServerPojo.find_all()
    
    collection = await _get_collection()

    now = datetime.now(timezone.utc)
    two_min_ago = now - timedelta(minutes=2)

    # Clean stale connections
    await collection.delete_many({"lastSeen": {"$lt": two_min_ago}})

    # Get all active connections without limit
    active = await collection.find({}).to_list(length=None)
    total_online = len(active)
    ikev2_online = sum(1 for c in active if _normalize_protocol(c.get("protocol", "")) == "ikev2")
    vless_online = sum(1 for c in active if _normalize_protocol(c.get("protocol", "")) == "vless")
    awg_online = sum(1 for c in active if _normalize_protocol(c.get("protocol", "")) == "awg")

    # Count by server
    server_counts_by_protocol = {
        "ikev2": {},
        "vless": {},
        "awg": {},
    }
    for c in active:
        ip = c.get("serverIp", "")
        if not ip:
            continue
        protocol = _normalize_protocol(c.get("protocol", ""))
        protocol_counts = server_counts_by_protocol[protocol]
        protocol_counts[ip] = protocol_counts.get(ip, 0) + 1

    # Build response
    ikev2_data = []
    for s in ikev2_servers:
        ikev2_data.append({
            "ipAddress": s.ip_address,
            "country": s.country,
            "countryCode": s.country_code,
            "status": s.status,
            "premium": s.premium,
            "onlineUsers": server_counts_by_protocol["ikev2"].get(s.ip_address, 0),
        })
        
    vless_data = []
    for s in vless_servers:
        # Vless domain or ip usually acts as the serverIp in active connections
        ip = s.server_domain_port_path.split(":")[0] if s.server_domain_port_path else s.server_ip
        vless_data.append({
            "ipAddress": s.server_ip,
            "domain": s.server_domain_port_path,
            "description": s.description,
            "onlineUsers": server_counts_by_protocol["vless"].get(
                ip,
                server_counts_by_protocol["vless"].get(s.server_ip, 0),
            ),
        })

    awg_data = []
    for s in awg_servers:
        awg_data.append({
            "ipAddress": s.ip_address,
            "country": s.country,
            "countryCode": s.country_code,
            "status": s.status,
            "premium": s.premium,
            "onlineUsers": server_counts_by_protocol["awg"].get(s.ip_address, 0),
        })

    return {
        "success": 1,
        "data": {
            "totalServers": len(ikev2_servers) + len(vless_servers) + len(awg_servers),
            "ikev2Servers": len(ikev2_servers),
            "vlessServers": len(vless_servers),
            "awgServers": len(awg_servers),
            "totalOnline": total_online,
            "ikev2Online": ikev2_online,
            "vlessOnline": vless_online,
            "awgOnline": awg_online,
            "servers": ikev2_data,
            "vlessServersList": vless_data,
            "awgServersList": awg_data,
        },
    }

@router.post("/vpn/api/v1/user/servers/load")
async def get_servers_load():
    """
    Returns server load as percentage to be used by Android clients.
    """
    from src.db.v3.servers.server_pojo import ServerPojo as Ikev2ServerPojo
    from src.db.v3.a3xui.server_pojo import ServerPojo as VlessServerPojo
    from src.db.v3.awg.server_pojo import ServerPojo as AwgServerPojo
    from src.utils.server_metrics import SERVER_METRICS_STATE

    ikev2_servers = await Ikev2ServerPojo.find_all()
    vless_servers = await VlessServerPojo.find_all()
    awg_servers = await AwgServerPojo.find_all()
    
    load_data = {}
    
    # Load all tracked metrics (which includes Remnawave VLESS servers and active IKEv2)
    for ip_or_host, data in SERVER_METRICS_STATE.items():
        load_data[ip_or_host] = data.get("load_percent", 1)

    # Ensure DB servers are also included with default 1% if they lack metrics
    for s in ikev2_servers:
        ip = s.ip_address
        if ip and ip not in load_data:
            load_data[ip] = 1
            
    for s in vless_servers:
        host = s.server_ip if s.server_ip else s.server_domain_port_path_sub.split(":")[0]
        if host and host not in load_data:
            load_data[host] = 1

    for s in awg_servers:
        ip = s.ip_address
        if ip and ip not in load_data:
            load_data[ip] = 1
            
    logger.debug(f"servers/load: SERVER_METRICS_STATE keys={list(SERVER_METRICS_STATE.keys())}")
    logger.debug(f"servers/load: load_data={load_data}")
            
    return {
        "success": 1,
        "data": load_data
    }
