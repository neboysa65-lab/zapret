// Package main builds libxray.so — the Go half of the app's Xray engine.
//
// It exports exactly the four C symbols that vyom-tun-sdk's JNI bridge
// (src/main/cpp/vyom-bridge.cpp) declares:
//
//	int   XrayMain(const char *configJson, const char *assetPath);
//	void  XrayStop();
//	char *XrayTestConfig(const char *configJson, const char *assetPath);
//	void  XrayFreeString(char *str);
//
// The binary previously shipped in vyom-tun-sdk/src/main/jniLibs was an
// untagged "(devel)" build with no source in this repository, which made the
// Xray core impossible to audit or update. This file restores that source so
// libxray.so is reproducible from a pinned upstream tag — see
// .github/workflows/build-libxray.yml and xray-version.properties.
//
// Build (per Android ABI, from this directory):
//
//	CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
//	CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang \
//	CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
//	go build -buildmode=c-shared -trimpath -ldflags="-s -w" -o libxray.so .
//
// The max-page-size flag is required: Android 15+ uses 16 KB pages, and the
// previously shipped binary was linked the same way.
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"bytes"
	"os"
	"sync"
	"unsafe"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"

	// Registers every protocol and transport (vless, reality,
	// xtls-rprx-vision, hysteria, ws, grpc, ...). Without this blank import
	// core.New rejects any real-world config with "unknown protocol".
	_ "github.com/xtls/xray-core/main/distro/all"
)

// Where xray-core looks for geoip.dat / geosite.dat. The Android side passes
// filesDir, which is where AssetUtils.copyAssets puts them.
const assetEnvKey = "xray.location.asset"

var (
	mu       sync.Mutex
	instance *core.Instance
)

// Return codes for XrayMain. 0 means the instance is started and its inbounds
// are bound; the Kotlin side additionally probes the local SOCKS port before
// building a tun, so a non-zero code here can never masquerade as a live
// tunnel.
const (
	rcOK           = 0
	rcBadConfig    = -1
	rcCreateFailed = -2
	rcStartFailed  = -3
)

func applyAssetPath(assetPath *C.char) {
	if assetPath == nil {
		return
	}
	if p := C.GoString(assetPath); p != "" {
		_ = os.Setenv(assetEnvKey, p)
	}
}

func loadConfig(configJSON string) (*core.Config, error) {
	return serial.LoadJSONConfig(bytes.NewReader([]byte(configJSON)))
}

//export XrayMain
func XrayMain(configJSON *C.char, assetPath *C.char) C.int {
	mu.Lock()
	defer mu.Unlock()

	// Starting twice must not leak the previous instance: its inbounds would
	// keep the local SOCKS port bound and the new one would fail to listen.
	if instance != nil {
		_ = instance.Close()
		instance = nil
	}

	applyAssetPath(assetPath)

	cfg, err := loadConfig(C.GoString(configJSON))
	if err != nil {
		return C.int(rcBadConfig)
	}

	inst, err := core.New(cfg)
	if err != nil {
		return C.int(rcCreateFailed)
	}

	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return C.int(rcStartFailed)
	}

	instance = inst
	return C.int(rcOK)
}

//export XrayStop
func XrayStop() {
	mu.Lock()
	defer mu.Unlock()

	if instance != nil {
		_ = instance.Close()
		instance = nil
	}
}

// XrayTestConfig returns NULL when the config is valid, otherwise a C string
// owned by the caller, who must release it with XrayFreeString. The Kotlin
// wrapper (NativeEngine.validateConfig) relies on that NULL-means-OK contract.
//
//export XrayTestConfig
func XrayTestConfig(configJSON *C.char, assetPath *C.char) *C.char {
	applyAssetPath(assetPath)

	if _, err := loadConfig(C.GoString(configJSON)); err != nil {
		return C.CString(err.Error())
	}
	return nil
}

//export XrayFreeString
func XrayFreeString(str *C.char) {
	if str != nil {
		C.free(unsafe.Pointer(str))
	}
}

func main() {}
