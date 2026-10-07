import logging

from fastapi import APIRouter, Request

from src.db.v3.gw.server_pojo import ServerPojo

logger = logging.getLogger(__name__)
router = APIRouter()

_STR_FIELDS = (
    "name",
    "proxy_host",
    "proxy_scheme",
    "payload",
    "sni",
    "ssh_hostkey",
    "stats_token",
    "country",
    "country_code",
    "state",
)
_BOOL_FIELDS = ("premium", "recommend", "status")
_INT_FIELDS = ("ssh_port", "proxy_port", "priority")


async def _read_body(request: Request) -> dict:
    """The bot posts urlencoded forms; other clients may post JSON."""
    content_type = (request.headers.get("content-type") or "").lower()
    if "json" in content_type:
        try:
            return await request.json()
        except Exception:
            return {}
    try:
        return dict(await request.form())
    except Exception:
        try:
            return await request.json()
        except Exception:
            return {}


def _to_str(value, default=""):
    return default if value is None else str(value)


def _to_bool(value, default=False):
    if isinstance(value, bool):
        return value
    if value is None:
        return default
    return str(value).strip().lower() in ("1", "true", "yes", "on")


def _to_int(value, default=0):
    try:
        return int(value)
    except Exception:
        return default


@router.get("/vpn/api/v1/bot/servers_gw/all")
async def bot_get_all_servers_gw():
    try:
        servers = await ServerPojo.find_all()
        return {"success": 1, "data": [s.to_doc() for s in servers]}
    except Exception as e:
        logger.error(f"Error fetching all GW servers: {e}")
        return {"success": 0, "message": str(e)}


@router.get("/vpn/api/v1/bot/servers_gw/get")
async def bot_get_server_gw(server_ip: str = ""):
    if not server_ip:
        return {"success": 0, "message": "server_ip required"}
    try:
        server = ServerPojo(ip_address=server_ip)
        found = await server.find()
        if not found:
            return {"success": 0, "message": "not found"}
        return {"success": 1, "data": found.to_doc()}
    except Exception as e:
        logger.error(f"Error fetching GW server: {e}")
        return {"success": 0, "message": str(e)}


@router.post("/vpn/api/v1/bot/servers_gw/create")
async def bot_create_server_gw(request: Request):
    try:
        data = await _read_body(request)
        ip_address = _to_str(data.get("ip_address"), "").strip()
        if not ip_address:
            return {"success": 0, "message": "ip_address required"}

        server = ServerPojo(ip_address=ip_address)
        for f in _STR_FIELDS:
            setattr(server, f, _to_str(data.get(f), getattr(server, f)))
        for f in _BOOL_FIELDS:
            setattr(server, f, _to_bool(data.get(f), getattr(server, f)))
        for f in _INT_FIELDS:
            setattr(server, f, _to_int(data.get(f), getattr(server, f)))
        server.ssh_username = _to_str(data.get("ssh_username"), getattr(server, "ssh_username"))
        server.ssh_password = _to_str(data.get("ssh_password"), getattr(server, "ssh_password"))

        await server.insert()
        return {"success": 1, "message": "created"}
    except Exception as e:
        logger.error(f"Error creating GW server: {e}")
        return {"success": 0, "message": str(e)}


@router.post("/vpn/api/v1/bot/servers_gw/update")
async def bot_update_server_gw(request: Request):
    try:
        data = await _read_body(request)
        ip_address = _to_str(data.get("ip_address") or data.get("ipAddress"), "").strip()
        if not ip_address:
            return {"success": 0, "message": "ip_address required"}

        server = ServerPojo(ip_address=ip_address)
        found = await server.find()
        if not found:
            return {"success": 0, "message": "not found"}

        for f in _STR_FIELDS:
            if f in data:
                setattr(found, f, _to_str(data[f], getattr(found, f)))
        for f in _BOOL_FIELDS:
            if f in data:
                setattr(found, f, _to_bool(data[f], getattr(found, f)))
        for f in _INT_FIELDS:
            if f in data:
                setattr(found, f, _to_int(data[f], getattr(found, f)))
        if "ssh_username" in data:
            found.ssh_username = _to_str(data["ssh_username"], found.ssh_username)
        if "ssh_password" in data:
            found.ssh_password = _to_str(data["ssh_password"], found.ssh_password)

        await found.update()
        return {"success": 1, "message": "updated"}
    except Exception as e:
        logger.error(f"Error updating GW server: {e}")
        return {"success": 0, "message": str(e)}


@router.post("/vpn/api/v1/bot/servers_gw/delete")
async def bot_delete_server_gw(request: Request):
    try:
        data = await _read_body(request)
        ip_address = _to_str(data.get("ip_address"), "").strip()
        if not ip_address:
            return {"success": 0, "message": "ip_address required"}
        server = ServerPojo(ip_address=ip_address)
        await server.delete()
        return {"success": 1, "message": "deleted"}
    except Exception as e:
        logger.error(f"Error deleting GW server: {e}")
        return {"success": 0, "message": str(e)}
