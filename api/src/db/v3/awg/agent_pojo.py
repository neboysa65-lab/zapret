from typing import Optional

from src.config import get_db

COLLECTION_NAME = "awg_agents"


class AgentPojo:
    """
    Провижининг-агент на AWG-сервере (выдаёт персональные пиры).
    Документы регистрируются один раз (перенесены из GreyWebVPN.awg_agents).
    """

    def __init__(self):
        self.ip_address: str = ""
        self.port: int = 39744
        self.token: str = ""
        self.scheme: str = "https"
        self.enabled: bool = True
        self.updated_at: int = 0

    @staticmethod
    async def get_collection():
        return get_db()[COLLECTION_NAME]

    def to_doc(self) -> dict:
        return {
            "ip_address": self.ip_address,
            "port": self.port,
            "token": self.token,
            "scheme": self.scheme,
            "enabled": self.enabled,
            "updated_at": self.updated_at,
        }

    def to_public_doc(self) -> dict:
        """Без токена — для выдачи наружу (списки и т.п.)."""
        doc = self.to_doc()
        doc["token"] = "***"
        return doc

    @staticmethod
    def from_doc(doc: dict) -> "AgentPojo":
        a = AgentPojo()
        a.ip_address = doc.get("ip_address", "")
        a.port = int(doc.get("port", 39744))
        a.token = doc.get("token", "")
        a.scheme = doc.get("scheme", "https")
        a.enabled = bool(doc.get("enabled", True))
        a.updated_at = doc.get("updated_at", 0)
        return a

    @staticmethod
    async def find_by_ip(ip_address: str) -> Optional["AgentPojo"]:
        collection = await AgentPojo.get_collection()
        doc = await collection.find_one({"ip_address": ip_address})
        return AgentPojo.from_doc(doc) if doc else None

    @staticmethod
    async def find_all() -> list["AgentPojo"]:
        collection = await AgentPojo.get_collection()
        cursor = collection.find({})
        agents = []
        async for doc in cursor:
            agents.append(AgentPojo.from_doc(doc))
        return agents
