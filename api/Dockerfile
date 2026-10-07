FROM python:3.11-slim

WORKDIR /app

# System deps:
#   - build-essential / cmake / pkg-config / autoconf / libtool — needed
#     to build native crypto wheels (coincurve / secp256k1) when no
#     prebuilt wheel is available on PyPI for the current Python.
#   - libsecp256k1-dev — system copy of the curve lib (skips the
#     vendored build inside coincurve).
#   - curl — used by HEALTHCHECK.
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    cmake \
    pkg-config \
    autoconf \
    automake \
    libtool \
    libsecp256k1-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ ./src/

EXPOSE 3003

# Health probe — uvicorn answers any path; we hit a public read-only one.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:3003/vpn/api/v3/client/tariff/get || exit 1

CMD ["python", "-m", "src.main"]
