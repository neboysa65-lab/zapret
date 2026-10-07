import time
from typing import Any, Dict, List, Optional

from src.config import get_db

COLLECTION_NAME = "servers_gw"


def _collection():
    return get_db()[COLLECTION_NAME]


class ServerPojo:
    """Одна GW-нода: SSH-endpoint (direct-tcpip) + HTTP/WS-фронт (CDN/прокси)
    + параметры payload для клиента. Ключ — ip_address."""

    def __init__(
        self,
        id: str = "",
        name: str = "",
        ip_address: str = "",
        ssh_port: int = 22,
        ssh_username: str = "gw",
        ssh_password: str = "",
        proxy_host: str = "",
        proxy_port: int = 80,
        proxy_scheme: str = "http",
        payload: str = "",
        sni: str = "",
        ssh_hostkey: str = "",
        stats_token: str = "",
        country: str = "0",
        country_code: str = "0",
        state: str = "0",
        premium: bool = False,
        recommend: bool = False,
        priority: int = 0,
        status: bool = True,
        created_at: int = 0,
        updated_at: int = 0,
    ) -> None:
        self.id = id or ip_address
        self.name = name
        self.ip_address = ip_address
        self.ssh_port = ssh_port
        self.ssh_username = ssh_username
        self.ssh_password = ssh_password
        self.proxy_host = proxy_host
        self.proxy_port = proxy_port
        self.proxy_scheme = proxy_scheme
        self.payload = payload
        self.sni = sni
        self.ssh_hostkey = ssh_hostkey
        self.stats_token = stats_token
        self.country = country
        self.country_code = country_code
        self.state = state
        self.premium = premium
        self.recommend = recommend
        self.priority = priority
        self.status = status
        now_ms = int(time.time() * 1000)
        self.created_at = created_at or now_ms
        self.updated_at = updated_at or now_ms

    def to_doc(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "ip_address": self.ip_address,
            "ssh_port": self.ssh_port,
            "ssh_username": self.ssh_username,
            "ssh_password": self.ssh_password,
            "proxy_host": self.proxy_host,
            "proxy_port": self.proxy_port,
            "proxy_scheme": self.proxy_scheme,
            "payload": self.payload,
            "sni": self.sni,
            "ssh_hostkey": self.ssh_hostkey,
            "stats_token": self.stats_token,
            "country": self.country,
            "country_code": self.country_code,
            "state": self.state,
            "premium": self.premium,
            "recommend": self.recommend,
            "priority": self.priority,
            "status": self.status,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
        }

    @staticmethod
    def from_doc(doc: Dict[str, Any]) -> "ServerPojo":
        return ServerPojo(
            id=doc.get("id", ""),
            name=doc.get("name", ""),
            ip_address=doc.get("ip_address", ""),
            ssh_port=doc.get("ssh_port", 22),
            ssh_username=doc.get("ssh_username", "gw"),
            ssh_password=doc.get("ssh_password", ""),
            proxy_host=doc.get("proxy_host", ""),
            proxy_port=doc.get("proxy_port", 80),
            proxy_scheme=doc.get("proxy_scheme", "http"),
            payload=doc.get("payload", ""),
            sni=doc.get("sni", ""),
            ssh_hostkey=doc.get("ssh_hostkey", ""),
            stats_token=doc.get("stats_token", ""),
            country=doc.get("country", "0"),
            country_code=doc.get("country_code", "0"),
            state=doc.get("state", "0"),
            premium=doc.get("premium", False),
            recommend=doc.get("recommend", False),
            priority=doc.get("priority", 0),
            status=doc.get("status", True),
            created_at=doc.get("created_at", 0),
            updated_at=doc.get("updated_at", 0),
        )

    @staticmethod
    async def is_exist(ip_address: str) -> bool:
        doc = await _collection().find_one({"ip_address": ip_address})
        return doc is not None

    async def insert(self) -> None:
        self.id = self.id or self.ip_address
        self.updated_at = int(time.time() * 1000)
        await _collection().update_one(
            {"ip_address": self.ip_address},
            {"$set": self.to_doc()},
            upsert=True,
        )

    async def update(self) -> None:
        await self.insert()

    async def find(self) -> Optional["ServerPojo"]:
        doc = await _collection().find_one({"ip_address": self.ip_address})
        return ServerPojo.from_doc(doc) if doc else None

    @staticmethod
    async def find_all() -> List["ServerPojo"]:
        cursor = _collection().find({})
        docs = [d async for d in cursor]
        docs.sort(key=lambda d: d.get("priority", 0) or 0)
        return [ServerPojo.from_doc(d) for d in docs]

    @staticmethod
    async def find_enabled() -> List["ServerPojo"]:
        cursor = _collection().find({"status": True})
        docs = [d async for d in cursor]
        docs.sort(key=lambda d: d.get("priority", 0) or 0)
        return [ServerPojo.from_doc(d) for d in docs]

    async def delete(self) -> None:
        await _collection().delete_one({"ip_address": self.ip_address})
