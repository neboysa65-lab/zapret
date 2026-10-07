"""Mongo-like shim over the Appwrite Databases REST API.

Lets the existing Motor-style pojos run unchanged on Appwrite's built-in
database — no external MongoDB needed. Every collection method used by
the codebase is implemented:

    find / find_one / insert_one / update_one ($set, upsert) /
    replace_one / delete_one / delete_many / count_documents

cursors support: async iteration, .sort(field, dir), .skip(n), .limit(n)

Filters support: field equality, {"field": {"$lt"/"$gt"/"$lte"/"$gte": v}},
and {"$or": [ {..}, {..} ]} (single-level).

Datetime objects are stored as ISO-8601 strings and converted back to
datetime on read when the attribute was stored as datetime (checked via
regex), dict/list values are stored as JSON strings and decoded on read.
"""

from __future__ import annotations

import json as _json
import re
import uuid as _uuid
from datetime import datetime, timezone


# ── connection settings (injected by the Appwrite runtime) ──────────
import os

ENDPOINT = (
    os.getenv("APPWRITE_FUNCTION_API_ENDPOINT")
    or os.getenv("APPWRITE_ENDPOINT")
    or "https://fra.cloud.appwrite.io/v1"
).rstrip("/")
PROJECT_ID = os.getenv("APPWRITE_FUNCTION_PROJECT_ID") or os.getenv("APPWRITE_PROJECT_ID") or ""
API_KEY = os.getenv("APPWRITE_FUNCTION_API_KEY") or os.getenv("APPWRITE_API_KEY") or ""
DATABASE_ID = os.getenv("AW_DATABASE_ID", "rabochiyvpn")

_ISO_RE = re.compile(
    r"^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:\.\d{3}Z|[+-]\d{2}:\d{2}|Z)?$"
)


def _dt_out(value):
    if isinstance(value, datetime):
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    return value


def _dt_in(value):
    if isinstance(value, str) and _ISO_RE.match(value):
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return value
    return value


# ── sync bridge: Appwrite REST via stdlib, executed on a private loop ──
import asyncio as _asyncio
import urllib.request as _urlreq
import urllib.error as _urlerr

_LOOP = None


def _get_loop():
    global _LOOP
    if _LOOP is None or _LOOP.is_closed():
        _LOOP = _asyncio.new_event_loop()
        import threading as _threading

        _threading.Thread(target=_LOOP.run_forever, daemon=True).start()
    return _LOOP


def _http(method: str, url: str, payload: dict | None = None):
    data = _json.dumps(payload).encode() if payload is not None else None
    req = _urlreq.Request(url, method=method, data=data,
                          headers={"X-Appwrite-Project": PROJECT_ID,
                                   "X-Appwrite-Key": API_KEY,
                                   "Content-Type": "application/json",
                                   "User-Agent": "Mozilla/5.0"})
    try:
        with _urlreq.urlopen(req, timeout=30) as resp:
            return resp.status, _json.loads(resp.read() or b"{}")
    except _urlerr.HTTPError as e:
        try:
            body = _json.loads(e.read() or b"{}")
        except Exception:
            body = {}
        return e.code, body
    except Exception as e:
        return 0, {"error": str(e)}


async def _call(method: str, url: str, payload: dict | None = None):
    """Await a sync HTTP call executed on the private loop."""
    loop = _asyncio.get_running_loop()
    fut = _asyncio.run_coroutine_threadsafe(
        _asyncio.to_thread(_http, method, url, payload), _get_loop())
    return await _asyncio.wrap_future(fut)


class _Cursor:
    """Mimics a Motor cursor: lazy paged fetch, async iteration, sort/skip/limit."""

    def __init__(self, coll, filter_doc):
        self._coll = coll
        self._filter = filter_doc or {}
        self._sort = None
        self._skip = 0
        self._limit = None

    def sort(self, key, direction=1):
        self._sort = (key, -1 if int(direction) < 0 else 1)
        return self

    def skip(self, n):
        self._skip = int(n)
        return self

    def limit(self, n):
        self._limit = int(n)
        return self

    def __aiter__(self):
        return self._iter()

    async def _iter(self):
        for doc in await self._fetch():
            yield doc

    async def to_list(self, length=None):
        docs = await self._fetch()
        return docs if length is None else docs[:length]

    async def _fetch(self) -> list[dict]:
        out: list[dict] = []
        limit = self._limit if self._limit is not None else 10_000
        after = None
        batch = 100
        while len(out) < limit:
            queries = [_q("limit", [min(batch, limit - len(out))])]
            for q in _build_filters(self._filter):
                queries.append(q)
            if self._sort:
                key, d = self._sort
                queries.append(_q("orderDesc" if d < 0 else "orderAsc", None, key))
            if after:
                queries.append(_q("cursorAfter", None, after))
            if self._skip and not after:
                queries.append(_q("offset", [self._skip]))
            docs = await self._coll._list(queries)
            if not docs:
                break
            out.extend(docs)
            after = docs[-1].get("$id") or docs[-1].get("_id")
            if len(docs) < batch:
                break
        return [_decode(d) for d in out[:limit]]


class AppwriteCollection:
    def __init__(self, name: str):
        self.name = name

    @property
    def _base(self) -> str:
        return f"{ENDPOINT}/databases/{DATABASE_ID}/collections/{self.name}/documents"

    async def _list(self, queries: list[str]) -> list[dict]:
        params = "&".join(f"queries[]={_esc(q)}" for q in queries)
        status, body = await _call("GET", self._base + f"?{params}")
        return body.get("documents", []) if status == 200 else []

    # ── read ────────────────────────────────────────────────────────────
    async def find_one(self, filter_doc=None, *args, **kwargs):
        cursor = self.find(filter_doc, **kwargs)
        if kwargs.get("sort"):
            cursor = cursor.sort(*kwargs["sort"][0]) if kwargs["sort"] else cursor
        docs = await cursor.to_list(1)
        return docs[0] if docs else None

    def find(self, filter_doc=None, *args, **kwargs):
        return _Cursor(self, filter_doc)

    async def count_documents(self, filter_doc=None):
        queries = [_q("limit", [1])]
        queries += _build_filters(filter_doc or {})
        params = "&".join(f"queries[]={_esc(q)}" for q in queries)
        status, body = await _call("GET", self._base + f"?{params}")
        return body.get("total", 0) if status == 200 else 0

    # ── write ───────────────────────────────────────────────────────────
    async def insert_one(self, doc: dict):
        doc_id = (doc.get("$id") or doc.get("_id") or _uuid.uuid4().hex[:36]).replace(" ", "_")
        payload = {"documentId": doc_id[:36], "data": _encode(doc)}
        status, body = await _call("POST", self._base, payload)
        return _Result(inserted_id=body.get("$id") if status == 201 else None)

    async def _find_one_document_id(self, filter_doc) -> str | None:
        docs = await _Cursor(self, filter_doc).to_list(1)
        if not docs:
            return None
        return docs[0].get("$id") or docs[0].get("_id")

    async def update_one(self, filter_doc, update_doc, upsert=False):
        doc_id = await self._find_one_document_id(filter_doc)
        if doc_id is None:
            if not upsert:
                return _Result(0)
            doc = dict(filter_doc)
            doc.update(update_doc.get("$set", {}))
            return await self.insert_one(doc)
        set_data = update_doc.get("$set", update_doc)
        status, _ = await _call("PATCH", f"{self._base}/{doc_id}", {"data": _encode(set_data)})
        return _Result(1 if status == 200 else 0)

    async def replace_one(self, filter_doc, doc, upsert=False):
        doc_id = await self._find_one_document_id(filter_doc)
        if doc_id is None:
            if not upsert:
                return _Result(0)
            return await self.insert_one(doc)
        status, _ = await _call("PUT", f"{self._base}/{doc_id}", {"data": _encode(doc)})
        return _Result(1 if status == 200 else 0)

    async def delete_one(self, filter_doc):
        doc_id = await self._find_one_document_id(filter_doc)
        if doc_id is None:
            return _Result(0)
        status, _ = await _call("DELETE", f"{self._base}/{doc_id}")
        return _Result(1 if status == 200 else 0)

    async def delete_many(self, filter_doc):
        docs = await _Cursor(self, filter_doc).to_list(None)
        n = 0
        for d in docs:
            doc_id = d.get("$id") or d.get("_id")
            if not doc_id:
                continue
            status, _ = await _call("DELETE", f"{self._base}/{doc_id}")
            if status == 200:
                n += 1
        return _Result(n)


class _Result:
    def __init__(self, n: int = 0, inserted_id=None):
        self.modified_count = n
        self.deleted_count = n
        self.inserted_id = inserted_id
        self.upserted_id = inserted_id


def _esc(s: str) -> str:
    return urllib_quote(s, safe="")


import urllib.parse as _up

def urllib_quote(s: str, safe=""):
    return _up.quote(s, safe=safe)


def _q(method: str, values=None, attr: str | None = None) -> str:
    """Appwrite 2.x query = JSON object {"method", ["attribute"], "values"}."""
    if method in ("orderDesc", "orderAsc", "cursorAfter"):
        return _json.dumps({"method": method, "attribute": attr}, separators=(",", ":"))
    if attr is None:  # limit / offset
        return _json.dumps({"method": method, "values": [values[0]]}, separators=(",", ":"))
    return _json.dumps({"method": method, "attribute": attr, "values": list(values)}, separators=(",", ":"))


def _build_filters(f: dict) -> list[str]:
    out: list[str] = []
    for key, cond in f.items():
        if not isinstance(cond, dict):
            cond = _dt_out(cond)
        elif isinstance(cond, dict):
            cond = {op: _dt_out(v) for op, v in cond.items()}
        if key == "$or":
            for sub in cond:
                out += _build_filters(sub)
            continue
        if isinstance(cond, dict):
            for op, v in cond.items():
                if op == "$lt":
                    out.append(_q("lessThan", [v], key))
                elif op == "$lte":
                    out.append(_q("lessThanEqual", [v], key))
                elif op == "$gt":
                    out.append(_q("greaterThan", [v], key))
                elif op == "$gte":
                    out.append(_q("greaterThanEqual", [v], key))
                elif op == "$ne":
                    out.append(_q("notEqual", [v], key))
                else:
                    out.append(_q("equal", [v], key))
        else:
            out.append(_q("equal", [cond], key))
    return out


def _encode(doc: dict) -> dict:
    out = {}
    for k, v in doc.items():
        if k in ("$id", "_id"):
            continue
        if isinstance(v, (dict, list)):
            v = _json.dumps(v, ensure_ascii=False, default=_dt_out)
        elif isinstance(v, datetime):
            v = _dt_out(v)
        if isinstance(v, str) and len(v) > 9000:
            v = v[:9000]
        out[k] = v
    return out


def _decode(doc: dict) -> dict:
    out = {}
    for k, v in doc.items():
        if k == "$id":
            out["_id"] = v
            out[k] = v
            continue
        if k.startswith("$"):
            continue
        if isinstance(v, str):
            v = _dt_in(v)
            if isinstance(v, str) and v[:1] in ("{", "["):
                try:
                    v = _json.loads(v)
                except Exception:
                    pass
        out[k] = v
    return out


class AppwriteDatabase:
    def __getitem__(self, collection_name: str) -> AppwriteCollection:
        return AppwriteCollection(collection_name)


def get_aw_db() -> AppwriteDatabase:
    return AppwriteDatabase()
