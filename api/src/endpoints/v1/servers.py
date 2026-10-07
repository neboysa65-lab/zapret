import logging

from fastapi import APIRouter, Request

from src.db.v1.server_pojo import ServerJson
from src.db.v3.servers.server_pojo import ServerPojo
from src.db.v3.user.user_pojo import UserPojo

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/vpn/api/v1/user/server")
async def get_servers(request: Request):
    body = await request.body()
    request_body = body.decode("utf-8")
    user_ip = request.client.host if request.client else ""

    params_map: dict[str, str] = {}
    for pair in request_body.split("&"):
        parts = pair.split("=", 1)
        if len(parts) == 2:
            params_map[parts[0]] = parts[1]

    user_id = params_map["userId"].replace("\r", "").replace("\n", "").strip()

    user = UserPojo()
    user.user_id = user_id
    user = await user.find_by_user_id()
    
    is_premium = False
    if user:
        is_premium = user.is_premium

    if is_premium:
        servers = await ServerPojo.find_all()
    else:
        servers = await ServerPojo.all_without_premium()

    servers_json = []
    for server in servers:
        if server.status:
            sj = ServerJson(
                id=server.ip_address,
                country=server.country,
                ip_address=server.ip_address,
                recommend=server.recommend,
                priority=server.priority,
                u_nsm=server.user,
                p_nsm=server.password,
                ca_file_name="",
                ca_file=server.ca_file,
                created_at=server.created_at,
                premium=server.premium,
                country_code=server.country_code,
                state=server.state,
                status=server.status,
            )
            servers_json.append(sj.to_dict())

    return {
        "success": 1,
        "error": 0,
        "message": "success",
        "data": servers_json,
    }
