import os

"""Central runtime configuration for the Rabochiy VPN API.

Two storage backends share the same Mongo-style interface:
  - DB_BACKEND=appwrite  -> built-in Appwrite Databases (serverless, default)
  - DB_BACKEND=mongo     -> MongoDB via Motor (legacy Docker/VPS deploys)
Secrets are never stored in the repository.
"""

# MongoDB connection (legacy backend only).
MONGODB_URI = os.getenv(
    "MONGODB_URI",
    "mongodb://127.0.0.1:27017/?retryWrites=true&w=majority",
)
DB_NAME = os.getenv("MONGODB_DB", "RabochiyVPN")

DB_BACKEND = os.getenv("DB_BACKEND", "appwrite")

_client = None


def get_db():
    """Return the shared database handle for the active backend."""
    global _client
    if DB_BACKEND == "mongo":
        if _client is None:
            import motor.motor_asyncio

            _client = motor.motor_asyncio.AsyncIOMotorClient(MONGODB_URI)
        return _client[DB_NAME]
    # Appwrite backend: Mongo-style shim over the Databases REST API
    from src import aw_db

    return aw_db.get_aw_db()
