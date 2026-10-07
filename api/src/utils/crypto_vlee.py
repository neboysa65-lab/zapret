import base64
import hashlib
import json
import os
from typing import Any, Dict

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

AAD = b"gw-config-v1"
KEY_LABEL = b"gw|key"

# ─── Компактная схема полей (wire-формат v=2) ──────────────────────────────
# Внутренний JSON расшифрованного конверта не содержит говорящих имён
# ("ssh_password", "proxy_host", ...) — только нейтральные k0..ka.
# Клиенты с v=2 получают ТОЛЬКО компактные ключи; старые клиенты —
# legacy-имена (обратная совместимость до полной миграции парка).
COMPACT_KEY_MAP = {
    "id": "k0",
    "ip_address": "k1",
    "ssh_port": "k2",
    "ssh_username": "k3",
    "ssh_password": "k4",
    "proxy_host": "k5",
    "proxy_port": "k6",
    "proxy_scheme": "k7",
    "payload": "k8",
    "sni": "k9",
    "ssh_hostkey": "ka",
}


def to_compact(payload_dict: Dict[str, Any]) -> Dict[str, Any]:
    return {COMPACT_KEY_MAP[k]: v for k, v in payload_dict.items() if k in COMPACT_KEY_MAP}


def _load_user_pubkey(pub_hex: str) -> ec.EllipticCurvePublicKey:
    raw = bytes.fromhex(pub_hex)
    if len(raw) == 65 and raw[0] == 0x00:
        # Знаковый нулевой байт от BigInteger.toByteArray(): "00||x||y".
        raw = b"\x04" + raw[1:]
    if len(raw) == 64:
        raw = b"\x04" + raw
    if len(raw) not in (33, 65):
        raise ValueError("unsupported pubkey length")
    return ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256K1(), raw)


def _derive_key(shared_x: bytes) -> bytes:
    return hashlib.sha256(shared_x + KEY_LABEL).digest()


def encrypt_config_to_user(pub_hex: str, plaintext: Dict[str, Any]) -> Dict[str, str]:
    user_pub = _load_user_pubkey(pub_hex)
    eph_priv = ec.generate_private_key(ec.SECP256K1())
    shared_x = eph_priv.exchange(ec.ECDH(), user_pub)
    key = _derive_key(shared_x)
    iv = os.urandom(12)
    pt = json.dumps(plaintext, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    ct = AESGCM(key).encrypt(iv, pt, AAD)
    eph_pub = eph_priv.public_key().public_bytes(
        encoding=serialization.Encoding.X962,
        format=serialization.PublicFormat.CompressedPoint,
    )
    return {
        "eph": eph_pub.hex(),
        "ct": base64.b64encode(ct).decode("ascii"),
        "iv": base64.b64encode(iv).decode("ascii"),
    }


def decrypt_config_with_priv(priv_hex: str, envelope: Dict[str, str]) -> Dict[str, Any]:
    priv = ec.derive_private_key(int(priv_hex, 16), ec.SECP256K1())
    eph_pub_bytes = bytes.fromhex(envelope["eph"])
    if len(eph_pub_bytes) == 64:
        eph_pub_bytes = b"\x04" + eph_pub_bytes
    eph_pub = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256K1(), eph_pub_bytes)
    shared_x = priv.exchange(ec.ECDH(), eph_pub)
    key = _derive_key(shared_x)
    iv = base64.b64decode(envelope["iv"])
    ct = base64.b64decode(envelope["ct"])
    pt = AESGCM(key).decrypt(iv, ct, AAD)
    return json.loads(pt.decode("utf-8"))


def normalize_user_pubkey(pub_raw: str) -> str | None:
    """Принимает 128/130 hex-символов (x||y или 04||x||y) либо 66 (сжатый).
    Возвращает нормализованный hex для шифрования или None."""
    pub = pub_raw.strip().lower()
    if pub.startswith("0x"):
        pub = pub[2:]
    # Java-клиент выводит pubkey через BigInteger.toByteArray(): при старшем
    # бите координаты x спереди оказывается знаковый нулевой байт, и ключ
    # приходит как "00||x||y" (130 hex). Каноничная форма — x||y (64 байта).
    # Раньше такая строка проходила проверку длины и рвала from_encoded_point:
    # конверт не шифровался ни на одну ноду, GW-раздел у аккаунта пустел.
    if len(pub) == 130 and pub.startswith("00"):
        pub = pub[2:]
    if len(pub) == 128:
        pub = "04" + pub
    if len(pub) not in (66, 130):
        return None
    try:
        bytes.fromhex(pub)
    except ValueError:
        return None
    return pub


def public_server_view(server: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "id": server.get("id"),
        "name": server.get("name"),
        "country": server.get("country"),
        "country_code": server.get("country_code"),
        "state": server.get("state"),
        "premium": server.get("premium", False),
        "recommend": server.get("recommend", False),
        "priority": server.get("priority", 0),
        "status": server.get("status", True),
    }
