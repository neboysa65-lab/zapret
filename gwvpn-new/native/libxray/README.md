# libxray.so — source of the bundled Xray engine

`vyom-tun-sdk/src/main/jniLibs/*/libxray.so` used to be a binary blob with no
source in this repository. Inspecting the Go build info showed it was an
**untagged `github.com/xtls/xray-core (devel)`** build, so there was no way to
tell which upstream revision was shipped, audit it, or update it.

This directory restores that source. `main.go` exports exactly the four C
symbols the JNI bridge (`vyom-tun-sdk/src/main/cpp/vyom-bridge.cpp`) declares:

| symbol | contract |
| --- | --- |
| `int XrayMain(const char *configJson, const char *assetPath)` | `0` on success, negative on failure |
| `void XrayStop()` | idempotent |
| `char *XrayTestConfig(const char *configJson, const char *assetPath)` | `NULL` when valid, else an owned error string |
| `void XrayFreeString(char *str)` | frees what `XrayTestConfig` returned |

`NativeEngine.validateConfig` depends on the `NULL`-means-valid contract, so
keep it if you modify this file.

## Version pin

| | release tag | Go module version |
| --- | --- | --- |
| Target (this pin) | `v26.3.27` (2026-03-27) | `v1.260327.0` |

Upstream publishes both: `v26.3.27` is the GitHub release, `v1.260327.0` is the
semver-compatible module version Go resolves (the module path has no `/vN`
suffix, so date-based `v26.*` tags are not usable as module versions).

### What the previously shipped blob contained

Read out of its embedded Go build info, for comparison:

| dependency | old blob | this pin |
| --- | --- | --- |
| `xtls/xray-core` | `(devel)`, untagged | `v1.260327.0` |
| `xtls/reality` | `2025-10-14` | `2026-03-22` |
| `refraction-networking/utls` | `v1.8.2` | `v1.8.3-0.20260301…` |
| `apernet/quic-go` | `v0.57.2-0.20260111…` | `v0.59.1-0.20260217…` |
| `gvisor.dev/gvisor` | `2026-01-09` | `2026-01-22` |
| Go toolchain | `go1.25.6` | `go1.26.1` |

The old blob's newest dependency pins date to mid-January 2026, i.e. roughly
`v1.260113.0` — about eight releases behind. `xtls/reality` in particular is
five months newer here, which matters for REALITY interoperability against a
server running a current Xray.

## Building

Use the `Build libxray.so (Xray-core)` GitHub Actions workflow
(`.github/workflows/build-libxray.yml`) — it builds all three ABIs the app
ships and uploads them as a `libxray-jniLibs` artifact. Copy that artifact's
contents into `vyom-tun-sdk/src/main/jniLibs/`.

Locally, per ABI (requires the Android NDK and cgo):

```sh
NDK=/path/to/android-ndk-r27c
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin

CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
  CC=$TOOLCHAIN/aarch64-linux-android24-clang \
  CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
  go build -buildmode=c-shared -trimpath -ldflags="-s -w" -o libxray.so .
```

`-Wl,-z,max-page-size=16384` is required for Android 15+ (16 KB pages) and is
how the previously shipped binary was linked.

## Verification status

`serial.LoadJSONConfig` → `core.New` → `Instance.Start` → `Instance.Close` was
exercised against `v1.260327.0` using the exact config the app's `VlessParser`
generates for the production subscription, and all four steps succeeded. That
validates this wrapper's API usage.

It does **not** prove that a given REALITY server interoperates: that needs a
real device on a network without a TLS-terminating middlebox. Build the ABIs,
drop them in, and confirm traffic flows before shipping.
