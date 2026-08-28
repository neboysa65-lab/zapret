cmake_minimum_required(VERSION 3.10.2)
project("vyom-v2ray")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# 1. Tell the linker WHERE to look for the .so files (the search path)
# We use the short names: hysteria2core and hev-socks5-tunnel
link_directories(${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# 2. Create your bridge library
add_library(vyom-v2ray SHARED vyom-bridge.cpp)

# 3. Find the Android logging library
find_library(log-lib log)

# 4. LINK BY SHORT NAME
# The Hysteria2-capable Xray core is shipped as libhysteria2core.so
# (renamed from gwapk's libxray.so, SONAME patched to match) so it can
# never collide with flutter_v2ray_plus's own libxray.so inside the APK.
target_link_libraries(
        vyom-v2ray
        hysteria2core
        hev-socks5-tunnel
        ${log-lib}
)
