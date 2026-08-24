import logging

from fastapi import APIRouter, Request

from src.db.v1.user_pojo import UserPojo
from src.db.v3.gw.server_pojo import ServerPojo
from src.utils.crypto_gw import (
    encrypt_config_to_user,
    normalize_user_pubkey,
    public_server_view,
    to_compact,
)

logger = logging.getLogger(__name__)
router = APIRouter()


def _secret_view(s) -> dict:
    return {
        "id": s.id,
        "ip_address": s.ip_address,
        "ssh_port": s.ssh_port,
        "ssh_username": s.ssh_username,
        "ssh_password": s.ssh_password,
        "proxy_host": s.proxy_host,
        "proxy_port": s.proxy_port,
        "proxy_scheme": s.proxy_scheme,
        "payload": s.payload,
        "sni": s.sni,
        "ssh_hostkey": s.ssh_hostkey,
    }


@router.post("/vpn/api/v1/user/server_gw")
async def get_servers_gw(request: Request):
    try:
        body = await request.body()
        params_map: dict[str, str] = {}
        for pair in body.decode("utf-8", errors="replace").split("&"):
            parts = pair.split("=", 1)
            if len(parts) == 2:
                params_map[parts[0]] = parts[1]

        user_id = params_map.get("userId", "").replace("\r", "").replace("\n", "").strip()
        pubkey_raw = params_map.get("pubkey", "").strip()
        if not pubkey_raw and user_id:
            pubkey_raw = user_id
        if not user_id or not pubkey_raw:
            return {"success": 0, "message": "userId and pubkey required", "data": []}

        # v=2 — клиент понимает компактную схему полей конверта (k0..ka)
        # и получает её БЕЗ legacy-имён ("ssh_password" и т.п.).
        compact_mode = params_map.get("v", "").strip() == "2"

        pub_hex = normalize_user_pubkey(pubkey_raw)
        if not pub_hex:
            return {"success": 0, "message": "invalid pubkey", "data": []}

        user_ip = request.client.host if request.client else ""
        user = UserPojo()
        user.id = user_id
        user.device_id = user_id
        user.source_ip = user_ip
        user.email = user_id

        if await user.is_exist():
            user = await user.find()
        else:
            await user.insert()
            user = await user.find()

        servers = await ServerPojo.find_enabled()
        data = []
        for s in servers:
            if s.premium and not user.is_premium:
                continue
            try:
                secret = _secret_view(s)
                if compact_mode:
                    secret = to_compact(secret)
                enc = encrypt_config_to_user(pub_hex, secret)
            except Exception as e:
                logger.error(f"GW encrypt failed for {s.ip_address}: {e}")
                continue
            data.append({"meta": public_server_view(s.to_doc()), "enc": enc})

        return {
            "success": 1,
            "error": 0,
            "message": "success",
            "data": data,
        }
    except Exception as e:
        logger.error(f"Error fetching GW servers for user: {e}")
        return {"success": 0, "message": str(e), "data": []}
