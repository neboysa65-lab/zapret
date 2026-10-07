"""
Copy server / tariff records from a legacy MongoDB database into the
``RabochiyVPN`` database used by this API.

Intended for the bootstrap scenario where the same VPS already hosts a
legacy VPN backend (its own DB on the same MongoDB instance) and we
want the new Rabochiy service to start with the **same set of physical
servers and the same pricing**, without dragging over user accounts,
invoices, or traffic logs (those belong to the legacy product).

Usage on the VPS::

    python3 scripts/migrate_from_legacy.py \\
        --source-db SourceDbName       \\
        [--mongo-uri mongodb://127.0.0.1:27017]    \\
        [--wipe]                                   \\
        [--dry-run]

``--wipe`` drops the destination collection before inserting (clean
import). Without it the script does an ``_id``-keyed upsert so it is
safe to re-run.

What gets copied
----------------
Only collections that describe the **infrastructure** the user pays
for, plus tariff metadata:

    tariff_v3              # generic tariff catalogue (price / duration)
    tariffs_3xui_v3        # 3x-ui-specific tariff overrides
    servers_v3             # IKEv2 server pool
    servers_awg            # AmneziaWG server pool
    servers_3xui_v3        # 3x-ui (VLESS) server pool
    servers_hiddify_v3     # Hiddify server pool

What is **not** copied
----------------------
``users_v3``, ``users_*_v3``, ``invoices_v3``, ``invoices_3xui_v3``,
``traffic_v3`` — these contain personally identifying / financial
records that belong to the legacy product and must not be merged.

After the import you should still:
    1. SSH into each migrated server and verify that the inbound
       (3x-ui / Hiddify / WireGuard) responds.
    2. In MongoDB, double-check ``servers_*`` collections for any
       fields that reference the legacy DB name (rare, but possible
       in custom deployments).
    3. Restart the Rabochiy API container so the new server pool gets
       picked up::

           docker compose -f /opt/rabochiy/docker-compose.yml restart
"""

from __future__ import annotations

import argparse
import datetime as _dt
import sys
from typing import Any, Callable, Iterable

from pymongo import MongoClient, UpdateOne
from pymongo.errors import BulkWriteError, PyMongoError

# Collections we lift from the legacy database. Keys are the source
# collection names; we keep the same names in RabochiyVPN because the v3
# POJOs in this project read from identical names.
COLLECTIONS_TO_COPY: tuple[str, ...] = (
    "servers_v3",
    "servers_awg",
    "servers_3xui_v3",
    "servers_hiddify_v3",
)

# Older GW deployments stored the same data under un-versioned names
# (no `_v3` suffix). Map source → destination so we can lift the rows
# into the collection the Rabochiy API actually reads from. The schemas
# are intentionally identical between the two — the rename was the
# only thing that changed when v3 routes were introduced.
LEGACY_NAME_MAP: dict[str, str] = {
    "servers": "servers_v3",
    # 3x-ui / hiddify never shipped a pre-v3 schema, so no mapping.
}

# Required fields per destination collection. Used as a pre-flight
# sanity check on the first source document so we fail loudly when
# the legacy DB diverged from the current Rabochiy POJO instead of
# silently importing junk that the API can't read.
REQUIRED_FIELDS: dict[str, frozenset[str]] = {
    "servers_v3": frozenset({
        "country", "ip_address", "recommend", "priority",
        "user", "password", "ca_file", "created_at",
        "premium", "state", "status",
    }),
}

# Per-destination field-name remaps. Used only when copying from a
# legacy un-versioned collection (`tariff`, `servers`) which still
# carries camelCase / vendor-prefix field names from the GW era.
# `src_field → dst_field`.
FIELD_RENAMES_BY_DST: dict[str, dict[str, str]] = {
    "servers_v3": {
        "ipAddress": "ip_address",
        "caFile": "ca_file",
        "createdAt": "created_at",
        "u_nsm": "user",
        "p_nsm": "password",
    },
}


def _datetime_to_epoch_ms(value: Any) -> Any:
    """Coerce a `datetime` (BSON ISODate) into the millisecond epoch
    integer Rabochiy POJOs expect. Pass everything else through untouched
    so already-numeric `created_at` values from a partial migration are
    not corrupted on a re-run."""
    if isinstance(value, _dt.datetime):
        # BSON ISODate is naive UTC; treat it as UTC explicitly so the
        # result is the same regardless of the migrator host's TZ.
        if value.tzinfo is None:
            value = value.replace(tzinfo=_dt.timezone.utc)
        return int(value.timestamp() * 1000)
    return value


# Per-destination value coercions applied AFTER field renames. Keyed
# by the *destination* field name so callers can reason about the
# Rabochiy-side schema only.
FIELD_TRANSFORMS_BY_DST: dict[str, dict[str, Callable[[Any], Any]]] = {
    "servers_v3": {
        "created_at": _datetime_to_epoch_ms,
    },
}


def _remap_doc(doc: dict, dst_name: str) -> dict:
    """Return a new dict with legacy field names rewritten to the
    Rabochiy POJO's expected names and any registered value transforms
    applied. Unknown fields are preserved verbatim — Mongo is happy
    to store extras and the POJOs just ignore them."""
    renames = FIELD_RENAMES_BY_DST.get(dst_name)
    transforms = FIELD_TRANSFORMS_BY_DST.get(dst_name)
    if not renames and not transforms:
        return doc

    out: dict = {}
    for key, value in doc.items():
        new_key = renames.get(key, key) if renames else key
        if transforms and new_key in transforms:
            value = transforms[new_key](value)
        out[new_key] = value
    return out

# Collections we explicitly refuse to touch even if --wipe was passed.
# Defence-in-depth in case someone edits COLLECTIONS_TO_COPY by hand.
USER_DATA_COLLECTIONS: frozenset[str] = frozenset(
    {
        "users",
        "users_v3",
        "users_3xui_v3",
        "users_hiddify_v3",
        "invoices",
        "invoices_v3",
        "invoices_3xui_v3",
        "traffic_v3",
        "active_connections",
        "code_v3",
    }
)

DEST_DB = "RabochiyVPN"


def _bulk_upsert(
    src_docs: Iterable[dict],
    dest_collection,
    *,
    dst_name: str,
    dry_run: bool,
) -> tuple[int, int]:
    """
    Upsert ``src_docs`` into ``dest_collection`` in chunks, keyed by
    ``_id``. Each doc is run through ``_remap_doc`` so legacy
    camelCase fields are rewritten to the Rabochiy POJO's snake_case
    names before they hit Mongo. Returns ``(matched, upserted)``.
    """
    chunk: list[UpdateOne] = []
    matched = 0
    upserted = 0
    chunk_size = 500
    for raw in src_docs:
        doc = _remap_doc(raw, dst_name)
        chunk.append(
            UpdateOne({"_id": doc["_id"]}, {"$set": doc}, upsert=True)
        )
        if len(chunk) >= chunk_size:
            if not dry_run:
                res = dest_collection.bulk_write(chunk, ordered=False)
                matched += res.matched_count
                upserted += res.upserted_count
            chunk.clear()
    if chunk and not dry_run:
        res = dest_collection.bulk_write(chunk, ordered=False)
        matched += res.matched_count
        upserted += res.upserted_count
    return matched, upserted


def _schema_check(
    src_name: str,
    dst_name: str,
    sample: dict | None,
) -> bool:
    """
    Verify the first source document carries every field the Rabochiy POJO
    expects. Returns True if the schema looks compatible (or no check
    is configured for `dst_name`); False if required fields are
    missing — in which case the caller should skip the collection
    instead of importing broken docs.
    """
    required = REQUIRED_FIELDS.get(dst_name)
    if not required or sample is None:
        return True
    missing = required - sample.keys()
    if missing:
        print(
            f"    ! schema mismatch in {src_name}: missing fields "
            f"{sorted(missing)} — skipping (Rabochiy POJO needs them)"
        )
        return False
    return True


def migrate_collection(
    source_db,
    dest_db,
    src_name: str,
    *,
    dst_name: str | None = None,
    wipe: bool,
    dry_run: bool,
) -> None:
    """
    Copy ``source_db[src_name]`` into ``dest_db[dst_name]``. When
    ``dst_name`` is None the destination keeps the source name (the
    common ``foo_v3 → foo_v3`` case); pass an explicit ``dst_name``
    for legacy renames such as ``tariff → tariff_v3``.
    """
    dst_name = dst_name or src_name

    # Refuse user-data collections on either side of the rename.
    if src_name in USER_DATA_COLLECTIONS or dst_name in USER_DATA_COLLECTIONS:
        print(f"  ! refusing to migrate user-data collection: {src_name}")
        return

    src = source_db[src_name]
    dst = dest_db[dst_name]
    src_count = src.estimated_document_count()
    label = src_name if src_name == dst_name else f"{src_name} → {dst_name}"
    print(f"  • {label}: {src_count} docs in source")

    if src_count == 0:
        print(f"    (nothing to copy)")
        return

    # Pull a sample doc once and run schema verification *after* the
    # legacy → v3 field rename so e.g. ``ipAddress`` is checked as
    # ``ip_address``. find_one() is cheap and stops bad imports early.
    sample = src.find_one({})
    remapped_sample = _remap_doc(sample, dst_name) if sample else None
    if not _schema_check(src_name, dst_name, remapped_sample):
        return

    if wipe and not dry_run:
        deleted = dst.delete_many({}).deleted_count
        print(f"    wiped destination ({deleted} docs)")

    matched, upserted = _bulk_upsert(
        src.find({}),
        dst,
        dst_name=dst_name,
        dry_run=dry_run,
    )
    if dry_run:
        print(f"    [dry-run] would upsert {src_count} docs")
    else:
        print(f"    upserted={upserted}, matched={matched}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-db",
        required=True,
        help="Name of the legacy MongoDB database to read from",
    )
    parser.add_argument(
        "--mongo-uri",
        default="mongodb://127.0.0.1:27017/?retryWrites=true&w=majority",
        help="MongoDB connection string (default: localhost)",
    )
    parser.add_argument(
        "--wipe",
        action="store_true",
        help=(
            "Drop the destination collection before importing. By "
            "default the script does an _id-keyed upsert and is "
            "idempotent."
        ),
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print actions without writing to MongoDB",
    )
    args = parser.parse_args(argv)

    if args.source_db == DEST_DB:
        print(
            f"refusing to migrate {DEST_DB} onto itself",
            file=sys.stderr,
        )
        return 2

    print(
        f"connecting to {args.mongo_uri}\n"
        f"  source: {args.source_db}\n"
        f"  dest:   {DEST_DB}\n"
        f"  wipe:   {args.wipe}\n"
        f"  dry-run:{args.dry_run}\n"
    )

    client = MongoClient(args.mongo_uri, serverSelectionTimeoutMS=5_000)
    try:
        client.admin.command("ping")
    except PyMongoError as exc:
        print(f"cannot reach MongoDB: {exc}", file=sys.stderr)
        return 1

    src_dbs = set(client.list_database_names())
    if args.source_db not in src_dbs:
        print(
            f"source DB {args.source_db!r} does not exist. Available: "
            f"{sorted(src_dbs)}",
            file=sys.stderr,
        )
        return 1

    source_db = client[args.source_db]
    dest_db = client[DEST_DB]

    print("Migrating collections:")
    try:
        # Same-name copies first (the v3-suffixed pool).
        for coll in COLLECTIONS_TO_COPY:
            migrate_collection(
                source_db,
                dest_db,
                coll,
                wipe=args.wipe,
                dry_run=args.dry_run,
            )
        # Legacy un-versioned names → their v3 counterparts. Skipped
        # if the source DB does not have the un-versioned collection,
        # so this is a safe no-op for clean v3 deployments.
        existing = set(source_db.list_collection_names())
        for legacy_name, dst_name in LEGACY_NAME_MAP.items():
            if legacy_name not in existing:
                continue
            migrate_collection(
                source_db,
                dest_db,
                legacy_name,
                dst_name=dst_name,
                wipe=args.wipe,
                dry_run=args.dry_run,
            )
    except BulkWriteError as exc:
        print(f"bulk write failed: {exc.details}", file=sys.stderr)
        return 1
    except PyMongoError as exc:
        print(f"MongoDB error: {exc}", file=sys.stderr)
        return 1

    print("\nDone.")
    if not args.dry_run:
        print(
            "Restart the API so it picks up the new pool, e.g.:\n"
            "  sudo systemctl restart rabochiy-api\n"
            "  # or, when running under Docker:\n"
            "  docker compose -f /opt/rabochiy/docker-compose.yml restart"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
