import hashlib
import hmac

from mnemonic import Mnemonic
from ecdsa import SECP256k1, SigningKey


async def generate_mnemonic() -> str:
    """
    Generate a BIP-39 mnemonic phrase. Returns 12 words (128-bit entropy)
    so that the resulting account ID is byte-compatible with the
    reference Android app — both apps use the same mnemonic length,
    HMAC-SHA512("Bitcoin seed", entropy) -> secp256k1 master derivation,
    and end up with the same 128 hex-uppercase user identifier.
    """
    m = Mnemonic("english")
    words = m.generate(strength=128)
    return words


def _mnemonic_to_user_id_sync(mnemonic_phrase: str) -> str:
    """
    Deterministically derive the 128-char hex-uppercase account ID from
    a BIP-39 mnemonic. Mirrors the EntropyHelper / Crypto pipeline used
    in the original Android client:

        mnemonic --(BIP-39)--> entropy
        entropy  --(HMAC-SHA512, key=b"Bitcoin seed")--> 64-byte master
        master[:32]                                    --> secp256k1 priv
        priv     --(secp256k1)-->                          64-byte pubkey (x||y)
        pubkey                                         --> hex.upper()

    Raises ``ValueError`` if the mnemonic is structurally invalid, so
    callers can surface a 400 error instead of silently issuing a bogus
    user_id.
    """
    m = Mnemonic("english")
    if not m.check(mnemonic_phrase):
        raise ValueError("invalid mnemonic")
    entropy = m.to_entropy(mnemonic_phrase)
    master = hmac.new(b"Bitcoin seed", bytes(entropy), hashlib.sha512).digest()
    secret_key = master[:32]
    sk = SigningKey.from_string(secret_key, curve=SECP256k1)
    vk = sk.get_verifying_key()
    return vk.to_string().hex().upper()


async def mnemonic_to_xprv(mnemonic_phrase: str) -> str:
    """
    Convert a mnemonic to the canonical user_id used everywhere on the
    backend (MongoDB ``users_v3.user_id``, Telegram bot, admin panel,
    QR codes shown to the user). Format: 128 hex chars uppercase.

    Kept ``async`` so the existing ``await crypto_user.mnemonic_to_xprv(...)``
    call sites in v3/client endpoints don't need a refactor.
    """
    return _mnemonic_to_user_id_sync(mnemonic_phrase)


def mnemonic_to_device_id(mnemonic_phrase: str) -> str | None:
    """
    Same derivation as :func:`mnemonic_to_xprv` but never raises — used
    by the Telegram-bot v1 endpoints that prefer a typed ``None`` for
    invalid input.
    """
    try:
        return _mnemonic_to_user_id_sync(mnemonic_phrase)
    except Exception:
        return None
