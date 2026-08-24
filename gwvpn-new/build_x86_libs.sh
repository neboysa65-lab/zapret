#!/bin/bash
set -e

STRONGSWAN_DIR="/mnt/c/Users/admin/Music/gwvpn/temp_strongswan/strongswan-master"
JNI_DIR="$STRONGSWAN_DIR/src/frontends/android/app/src/main/jni"
NDK_PATH="/mnt/c/Users/admin/AppData/Local/Android/Sdk/ndk/30.0.14904198"
OUTPUT_DIR="/mnt/c/Users/admin/Music/gwvpn/app/src/main/jniLibs/x86"
OPENSSL_VERSION="3.0.16"
WORK_DIR="$HOME/strongswan-build"

echo "=== Step 1: Install dependencies ==="
apt-get update -qq
apt-get install -y -qq make perl jq wget

echo "=== Step 2: Create Android.common.mk ==="
sed "s/@PACKAGE_VERSION@/6.0.5/" "$STRONGSWAN_DIR/Android.common.mk.in" > "$STRONGSWAN_DIR/Android.common.mk"
echo "Created Android.common.mk"

echo "=== Step 3: Download and build OpenSSL for x86 ==="
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

if [ ! -d "openssl-$OPENSSL_VERSION" ]; then
    echo "Downloading OpenSSL $OPENSSL_VERSION..."
    wget -q "https://www.openssl.org/source/openssl-$OPENSSL_VERSION.tar.gz"
    tar xzf "openssl-$OPENSSL_VERSION.tar.gz"
fi

cd "openssl-$OPENSSL_VERSION"

export ANDROID_NDK_ROOT="$NDK_PATH"
export PATH="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
export ANDROID_NDK_HOME="$NDK_PATH"

MIN_SDK=21
ABI=x86
OPTIONS="android-x86 no-shared no-ct no-cast no-comp no-dgram no-dsa no-gost no-idea no-rmd160 no-seed no-sm2 no-sm3 no-sm4 no-sock no-srp no-srtp no-err no-engine no-dso no-hw no-stdio no-ui-console -fPIC -DOPENSSL_PIC -ffast-math -O3 -funroll-loops -Wno-macro-redefined -D__ANDROID_API__=${MIN_SDK}"

echo "Building OpenSSL libcrypto.a for x86..."
make distclean >/dev/null 2>&1 || true
./Configure $OPTIONS
make -j$(nproc) build_generated >/dev/null 2>&1
make -j$(nproc) libcrypto.a >/dev/null 2>&1

OPENSSL_OUT="$JNI_DIR/openssl"
mkdir -p "$OPENSSL_OUT/x86"
cp libcrypto.a "$OPENSSL_OUT/x86/"
cp -R include/ "$OPENSSL_OUT/"

if [ ! -f "$OPENSSL_OUT/Android.mk" ]; then
    cat > "$OPENSSL_OUT/Android.mk" << 'MKEOF'
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libcrypto_static
LOCAL_SRC_FILES := $(TARGET_ARCH_ABI)/libcrypto.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/include
include $(PREBUILT_STATIC_LIBRARY)
MKEOF
fi

echo "OpenSSL x86 built successfully"

echo "=== Step 4: Build strongSwan native libs for x86 ==="
cd "$JNI_DIR"

"$NDK_PATH/ndk-build" \
    APP_ABI=x86 \
    APP_PLATFORM=android-21 \
    NDK_PROJECT_PATH=. \
    APP_BUILD_SCRIPT=Android.mk \
    APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true \
    -j$(nproc)

echo "=== Step 5: Copy .so files to jniLibs/x86 ==="
mkdir -p "$OUTPUT_DIR"

cp libs/x86/*.so "$OUTPUT_DIR/"

echo "=== Done! Built libraries: ==="
ls -la "$OUTPUT_DIR/"
