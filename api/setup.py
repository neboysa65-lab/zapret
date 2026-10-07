"""One-shot provisioning for the Appwrite built-in database.

Creates the `rabochiyvpn` database, every collection the API uses, and
string attributes matching the fields the pojos actually read/write
(plus any extra keys discovered in code defaults). Idempotent — safe to
run on every deploy; existing collections/attributes are skipped.

Deployed as function `rabochiy-setup` (entrypoint: setup.py), executed
manually after deploys or schema changes.
"""

import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

import json
import urllib.request

ENDPOINT = (
    os.getenv("APPWRITE_FUNCTION_API_ENDPOINT")
    or os.getenv("APPWRITE_ENDPOINT")
    or "https://fra.cloud.appwrite.io/v1"
).rstrip("/")
PROJECT_ID = os.getenv("APPWRITE_FUNCTION_PROJECT_ID") or ""
API_KEY = os.getenv("APPWRITE_API_KEY") or os.getenv("APPWRITE_FUNCTION_API_KEY") or ""
DATABASE_ID = os.getenv("AW_DATABASE_ID", "rabochiyvpn")

HDRS = {
    "X-Appwrite-Project": PROJECT_ID,
    "X-Appwrite-Key": API_KEY,
    "Content-Type": "application/json",
}

# collection id -> {attr: max_len}. Derived from the fields the pojos
# write; sizes are generous because Appwrite indexes strings by length.
SCHEMA: dict[str, dict[str, int]] = {
    "users": {
        "user_id": 128, "device_id": 256, "total_download": 64, "total_upload": 64,
        "source_ip": 64, "created_at": 40, "last_login": 40,
        "is_premium": 8, "premium_end": 40, "mnemonic": 256, "bundle_id": 128,
        "device_name": 128, "app_version": 32, "platform": 32,
    },
    "users_v3": {
        "user_id": 128, "total_download": 64, "total_upload": 64, "source_ip": 64,
        "created_at": 64, "last_login": 64, "is_premium": 8, "premium_end": 64,
        "mnemonic": 512, "traffic_updated_at": 64,
    },
    "servers": {
        "server_id": 128, "name": 256, "host": 256, "ip_address": 64, "port": 32,
        "username": 128, "password": 256, "ca_file": 512, "enabled": 8,
        "premium_only": 8, "country_code": 16, "city": 128, "load": 32,
        "created": 64,
    },
    "servers_vless": {
        "server_id": 128, "name": 256, "server_ip": 64, "port": 32,
        "enabled": 8, "premium_only": 8, "country_code": 16, "city": 128,
        "created": 64, "config": 9000,
    },
    "servers_awg": {
        "server_id": 128, "name": 256, "endpoint": 256, "public_key": 256,
        "psk": 256, "allowed_ips": 128, "dns": 256, "enabled": 8,
        "premium_only": 8, "country_code": 16, "city": 128, "created": 64,
    },
    "tariff": {
        "name": 256, "technical_name": 128, "description": 512,
        "price": 32, "enabled": 8, "duration": 64,
    },
    "tariff_v3": {
        "name": 256, "technical_name": 128, "description": 512,
        "price": 32, "enabled": 8, "duration": 64,
    },
    "tariffs_vless": {
        "name": 256, "technical_name": 128, "description": 512,
        "price": 32, "enabled": 8, "duration": 64,
    },
    "invoices": {
        "invoice_id": 128, "user_id": 128, "amount": 64, "currency": 32,
        "status": 64, "pay_url": 9000, "created": 64, "updated": 64,
        "fk_operation_id": 256, "p_email": 256, "p_phone": 64,
        "payer_account": 256, "commission": 64, "plan": 128,
    },
    "invoices_v3": {
        "invoice_id": 128, "operation_id": 256, "user_id": 128, "amount": 64,
        "status": 8, "pay_url": 9000, "created": 64, "updated": 64,
        "p_email": 256, "p_phone": 64, "commission": 64, "plan": 128,
        "provider": 64,
    },
    "invoices_vless": {
        "invoice_id": 128, "invoice_amount": 64, "tg_id": 128,
        "tariff_name": 128, "enable": 9000, "expired_at": 9000,
        "delnotif": 8, "litenotif0": 8, "litenotif1": 8, "litenotif2": 8,
        "litenotif3": 8, "pronotif0": 8, "pronotif1": 8, "pronotif2": 8,
        "pronotif3": 8,
    },
    "otps": {
        "email": 256, "code": 32, "used": 8, "created_at": 40, "expires_at": 40,
        "createdAt": 40, "expiresAt": 40,
    },
    "codes": {
        "user_id": 128, "code": 256, "created": 64, "duration": 64, "pay_url": 9000,
        "activated": 8,
    },
    "admins": {"login": 128, "password_hash": 256, "created": 64},
    "bot_admins": {"tg_id": 64, "name": 256, "created": 64},
    "support_users": {"user_id": 128, "email": 256, "name": 256, "created": 64},
    "hiddify_configs": {"config_id": 128, "name": 256, "data": 9000},
    "hiddify_users": {"user_id": 128, "uuid": 128, "created": 64, "expired": 64},
    "hiddify_servers": {"server_id": 128, "name": 256, "host": 256, "port": 32},
    "active_connections": {
        "userId": 128, "serverIp": 64, "protocol": 32,
        "connectedAt": 40, "lastSeen": 40,
    },
    "traffic_history": {
        "user_id": 128, "bucket": 64, "download": 64, "upload": 64, "created": 64,
    },
    "payments_tariffs": {
        "name": 256, "technical_name": 128, "description": 512,
        "price": 32, "enabled": 8, "duration": 64,
    },
}

COLLECTION_ALIASES = {
    "users": "users_v1", "invoices": "invoices_v1", "otps": "otps_v1",
    "codes": "codes_v1", "admins": "admins_v1", "support_users": "support_v1",
}


def _discover_schemas(log) -> dict[str, dict[str, str]]:
    """Walk src/db pojos, build {collection_id: {attr: type}} from to_doc defaults."""
    import importlib
    import pkgutil
    import src.db as _dbpkg

    schemas: dict[str, dict[str, str]] = {}
    for mod_info in pkgutil.walk_packages(_dbpkg.__path__, _dbpkg.__name__ + "."):
        try:
            mod = importlib.import_module(mod_info.name)
        except Exception:
            continue
        for name in dir(mod):
            cls = getattr(mod, name)
            if not isinstance(cls, type) or name.startswith("_"):
                continue
            if not (hasattr(cls, "to_doc") and hasattr(cls, "get_collection")):
                continue
            try:
                inst = cls()
                doc = inst.to_doc()
                coll = await_coll_name(inst)
            except Exception as e:
                log(f"  skip {mod_info.name}.{name}: {e}")
                continue
            if not coll or not isinstance(doc, dict):
                continue
            types = {}
            for k, v in doc.items():
                if k in ("$id", "_id"):
                    continue
                if isinstance(v, bool):
                    types[k] = "boolean"
                elif isinstance(v, (int, float)):
                    types[k] = "float"
                elif isinstance(v, (dict, list)):
                    types[k] = "json"
                else:
                    types[k] = "string"
            schemas[coll] = types
    return schemas


def await_coll_name(inst) -> str:
    mod = sys.modules.get(type(inst).__module__)
    name = getattr(mod, "COLLECTION_NAME", None)
    return str(name) if name else ""


# collections not backed by a pojo (raw endpoint code)
EXTRA_SCHEMAS = {
    "active_connections": {
        "userId": "string", "serverIp": "string", "protocol": "string",
        "connectedAt": "string", "lastSeen": "string",
    },
}


class _Rest:
    def __init__(self):
        pass

    def call(self, method: str, path: str, body: dict | None = None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(ENDPOINT + path, method=method, data=data, headers=HDRS)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as e:
            try:
                return {"__status": e.code, "__body": e.read().decode()[:300]}
            except Exception:
                return {"__status": e.code, "__body": ""}


def _ensure_database(rest: _Rest, log) -> bool:
    d = rest.call("GET", f"/databases/{DATABASE_ID}")
    if "__status" in d:
        d = rest.call("POST", "/databases", {"databaseId": DATABASE_ID, "name": "RabochiyVPN"})
        if "__status" in d:
            log(f"database create failed: {d}")
            return False
        log(f"database {DATABASE_ID} created")
    return True


def _attr_payloads(types: dict[str, str]) -> list[dict]:
    out = []
    for attr, typ in types.items():
        if typ == "boolean":
            out.append({"key": attr, "type": "boolean", "required": False})
        elif typ == "float":
            out.append({"key": attr, "type": "float", "required": False})
        elif typ == "json":
            out.append({"key": attr, "type": "string", "size": 9000, "required": False})
        else:
            out.append({"key": attr, "type": "string", "size": 1024, "required": False})
    return out


async def _run(log) -> bool:
    rest = _Rest()
    if not _ensure_database(rest, log):
        return False

    schemas = dict(EXTRA_SCHEMAS)
    schemas.update(_discover_schemas(log))
    log(f"discovered {len(schemas)} collections")

    for real_id, types in sorted(schemas.items()):
        d = rest.call("GET", f"/databases/{DATABASE_ID}/collections/{real_id}")
        if "__status" in d and d.get("__status") != 200:
            d = rest.call("POST", f"/databases/{DATABASE_ID}/collections",
                          {"collectionId": real_id, "name": real_id,
                           "documentSecurity": True})
            if "__status" in d and d.get("__status") != 409:
                log(f"collection {real_id} create failed: {d}")
                continue
            log(f"collection {real_id} created")

        existing = set()
        page = rest.call("GET", f"/databases/{DATABASE_ID}/collections/{real_id}/attributes?limit=100")
        for a in page.get("attributes", []):
            existing.add(a.get("key"))
        for payload in _attr_payloads(types):
            if payload["key"] in existing:
                continue
            typ = payload.pop("type")
            key = payload.pop("key")
            r = rest.call("POST",
                          f"/databases/{DATABASE_ID}/collections/{real_id}/attributes/{typ}",
                          {"key": key, **payload})
            if "__status" in r and r.get("__status") != 409:
                log(f"  attr {real_id}.{key}({typ}): {r.get('__body', '')[:120]}")
        log(f"collection {real_id}: {len(types)} attributes ensured")

    log("provisioning complete")
    return True


async def main(context):
    ok = await _run(context.log)
    return context.res.json({"ok": ok})
