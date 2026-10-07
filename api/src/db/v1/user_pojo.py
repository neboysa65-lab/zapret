from datetime import datetime, timezone
from typing import Optional

import motor.motor_asyncio
from pydantic import BaseModel, Field

from src.config import MONGODB_URI, DB_NAME, get_db  # central config
COLLECTION_NAME = "users"

DATE_FORMAT = "%a %b %d %H:%M:%S %Z %Y"


class UserJson(BaseModel):
    """JSON response/request model (maps to Rust UserJson with timezone-aware dates)."""
    id: str = "0"
    is_anonymous: bool = Field(default=True, alias="isAnonymous")
    total_download: int = Field(default=0, alias="totalDownload")
    total_upload: int = Field(default=0, alias="totalUpload")
    device_id: str = Field(default="0", alias="deviceId")
    source_ip: str = Field(default="0", alias="sourceIp")
    country_code: str = Field(default="0", alias="countryCode")
    email: str = "0"
    created_at: str = Field(default="", alias="createdAt")
    last_login: str = Field(default="", alias="lastLogin")
    is_premium: bool = Field(default=False, alias="isPremium")
    premium_end: str = Field(default="", alias="premiumEnd")

    model_config = {"populate_by_name": True}

    def to_dict(self) -> dict:
        return self.model_dump(by_alias=True)

    @staticmethod
    def from_pojo(pojo: "UserPojo") -> "UserJson":
        fmt = lambda dt: dt.strftime(DATE_FORMAT) if isinstance(dt, datetime) else str(dt)
        return UserJson(
            id=pojo.id, is_anonymous=pojo.is_anonymous,
            total_download=pojo.total_download, total_upload=pojo.total_upload,
            device_id=pojo.device_id, source_ip=pojo.source_ip,
            country_code=pojo.country_code, email=pojo.email,
            created_at=fmt(pojo.created_at), last_login=fmt(pojo.last_login),
            is_premium=pojo.is_premium, premium_end=fmt(pojo.premium_end),
        )


class UserJson2(BaseModel):
    """JSON response/request model (maps to Rust UserJson2 with naive dates)."""
    id: str = "0"
    is_anonymous: bool = Field(default=True, alias="isAnonymous")
    total_download: int = Field(default=0, alias="totalDownload")
    total_upload: int = Field(default=0, alias="totalUpload")
    device_id: str = Field(default="0", alias="deviceId")
    source_ip: str = Field(default="0", alias="sourceIp")
    country_code: str = Field(default="0", alias="countryCode")
    email: str = "0"
    created_at: str = Field(default="", alias="createdAt")
    last_login: str = Field(default="", alias="lastLogin")
    is_premium: bool = Field(default=False, alias="isPremium")
    premium_end: str = Field(default="", alias="premiumEnd")

    model_config = {"populate_by_name": True}

    def to_dict(self) -> dict:
        return self.model_dump(by_alias=True)


class UserPojo:
    def __init__(self, **kwargs):
        self.id: str = kwargs.get("id", "0")
        self.is_anonymous: bool = kwargs.get("is_anonymous", True)
        self.total_download: int = kwargs.get("total_download", 0)
        self.total_upload: int = kwargs.get("total_upload", 0)
        self.device_id: str = kwargs.get("device_id", "0")
        self.source_ip: str = kwargs.get("source_ip", "0")
        self.country_code: str = kwargs.get("country_code", "0")
        self.email: str = kwargs.get("email", "0")
        self.created_at: datetime = kwargs.get("created_at", datetime.now(timezone.utc))
        self.last_login: datetime = kwargs.get("last_login", datetime.now(timezone.utc))
        self.is_premium: bool = kwargs.get("is_premium", False)
        self.premium_end: datetime = kwargs.get("premium_end", datetime.now(timezone.utc))

    @staticmethod
    def now() -> datetime:
        return datetime.now(timezone.utc)

    def premium_end_millis(self) -> int:
        if isinstance(self.premium_end, datetime):
            return int(self.premium_end.timestamp() * 1000)
        if isinstance(self.premium_end, (int, float)):
            return int(self.premium_end)
        return 0

    @staticmethod
    def from_millis(ms: int) -> datetime:
        return datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc)

    @staticmethod
    async def get_collection():
        db = get_db()
        return db[COLLECTION_NAME]

    def clean_ids(self):
        self.device_id = self.device_id.replace("\r", "").replace("\n", "").strip()
        self.id = self.id.replace("\r", "").replace("\n", "").strip()

    def to_doc(self) -> dict:
        return {
            "_id": self.id,
            "isAnonymous": self.is_anonymous,
            "totalDownload": self.total_download,
            "totalUpload": self.total_upload,
            "deviceId": self.device_id,
            "sourceIp": self.source_ip,
            "countryCode": self.country_code,
            "email": self.email,
            "createdAt": self.created_at,
            "lastLogin": self.last_login,
            "isPremium": self.is_premium,
            "premiumEnd": self.premium_end,
        }

    @staticmethod
    def from_doc(doc: dict) -> "UserPojo":
        user = UserPojo()
        user.id = doc.get("_id", "0")
        user.is_anonymous = doc.get("isAnonymous", True)
        user.total_download = doc.get("totalDownload", 0)
        user.total_upload = doc.get("totalUpload", 0)
        user.device_id = doc.get("deviceId", "0")
        user.source_ip = doc.get("sourceIp", "0")
        user.country_code = doc.get("countryCode", "0")
        user.email = doc.get("email", "0")
        user.created_at = doc.get("createdAt", datetime.now(timezone.utc))
        user.last_login = doc.get("lastLogin", datetime.now(timezone.utc))
        user.is_premium = doc.get("isPremium", False)
        user.premium_end = doc.get("premiumEnd", datetime.now(timezone.utc))
        return user

    async def insert(self) -> bool:
        collection = await self.get_collection()
        if self.device_id.startswith("00") and len(self.device_id) > 128:
            self.device_id = self.device_id[2:]
        self.clean_ids()
        if await self.is_exist():
            return await self.update()
        await collection.insert_one(self.to_doc())
        return True

    async def is_exist(self) -> bool:
        return (await self.find()) is not None

    async def find_user_by_device_id(self) -> Optional["UserPojo"]:
        collection = await self.get_collection()
        doc = await collection.find_one({"deviceId": self.device_id})
        return UserPojo.from_doc(doc) if doc else None

    async def find(self) -> Optional["UserPojo"]:
        if self.device_id.startswith("00") and len(self.device_id) > 128:
            self.device_id = self.device_id[2:]
        return await self.tricky_find_user_by_device_id()

    async def tricky_find_user_by_device_id(self) -> Optional["UserPojo"]:
        collection = await self.get_collection()
        result = await self.find_user_by_device_id()
        if result is None:
            doc = await collection.find_one({"deviceId": f"{self.device_id}\n"})
            if doc:
                result = UserPojo.from_doc(doc)
        if result:
            result.clean_ids()
        return result

    async def update(self) -> bool:
        collection = await self.get_collection()
        self.clean_ids()
        result = await collection.replace_one({"deviceId": self.device_id}, self.to_doc())
        return result.modified_count >= 1

    @staticmethod
    async def find_all() -> list["UserPojo"]:
        collection = await UserPojo.get_collection()
        cursor = collection.find({})
        users = []
        async for doc in cursor:
            users.append(UserPojo.from_doc(doc))
        return users

    @staticmethod
    async def find_all_premium() -> list["UserPojo"]:
        collection = await UserPojo.get_collection()
        cursor = collection.find({"isPremium": True})
        users = []
        async for doc in cursor:
            users.append(UserPojo.from_doc(doc))
        return users

    @staticmethod
    async def find_all_not_premium() -> list["UserPojo"]:
        collection = await UserPojo.get_collection()
        cursor = collection.find({"isPremium": False})
        users = []
        async for doc in cursor:
            users.append(UserPojo.from_doc(doc))
        return users

    async def delete(self):
        collection = await self.get_collection()
        await collection.delete_one({"deviceId": self.device_id})
