import logging

from fastapi import APIRouter, Request

from src.db.v3.gw.server_pojo import ServerPojo
from src.db.v3.user.user_pojo import UserPojo
from src.utils.crypto_vlee import (
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


@router.post("/vpn/api/v1/user/server_vlee")
async def get_servers_vlee(request: Request):
    """Per-user encrypted GW (SSH-tunnel) server catalogue.

    Contract is 1:1 with the production GW backend: the client sends
    userId + device secp256k1 pubkey (+ optional v=2 for the compact
    wire scheme); every node is encrypted to that pubkey individually.
    """
    try:
        body = await request.body()
        params_map: dict = {}
        for pair in body.decode("utf-8", errors="replace").split("&"):
            parts = pair.split("=", 1)
            if len(parts) == 2:
                params_map[parts[0]] = parts[1]

        user_id = params_map.get("userId", "").replace(chr(13), "").replace(chr(10), "").strip()
        pubkey_raw = params_map.get("pubkey", "").strip()
        if not user_id or not pubkey_raw:
            return {"success": 0, "message": "userId and pubkey required", "data": []}

        # v=2 — клиент понимает компактную схему полей конверта (k0..ka)
        compact_mode = params_map.get("v", "").strip() == "2"

        pub_hex = normalize_user_pubkey(pubkey_raw)
        if not pub_hex:
            return {"success": 0, "message": "invalid pubkey", "data": []}

        user_ip = request.client.host if request.client else ""
        user = UserPojo()
        user.user_id = user_id
        user.source_ip = user_ip

        if await user.is_exist():
            user = await user.find_by_user_id()
        else:
            await user.insert()
            user = await user.find_by_user_id()

        servers = await ServerPojo.find_enabled()
        is_premium = bool(getattr(user, "is_premium", False))
        data = []
        for s in servers:
            try:
                secret = _secret_view(s)
                if compact_mode:
                    secret = to_compact(secret)
                # Premium nodes stay visible for free users (the client
                # gates the connection itself) — same behaviour as the
                # AWG catalogue which ships them with stripped configs.
                if s.premium and not is_premium:
                    secret["locked"] = True
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
