# AWG in VLESS VPN

The Android client now has a separate AmneziaWG engine from `rabochiy@59b35a6`.
VLESS/Shadowsocks still use flutter_v2ray_plus; Hysteria2 keeps its own engine.
AWG 2.0/3.1 configs are passed intact to the reference Go library, including
S1–S4, H1–H4 ranges, I1–I5 and the existing peer/address/DNS/MTU settings.

The new authenticated catalogue is `GET /api/v1/awg/servers` on the existing
API base URL. Deploy branch `api` and its migration `0006` first; configure nodes
and provisioning agents through **👽 Серверы AWG** in the admin bot. See
`docs/AWG.md` on branch `api` for import format and rollout. No production nodes,
agent tokens or user peers are automatically copied between applications.

The list labels AWG versions, preserves location/city, marks locked Premium and
unavailable nodes, and excludes them from automatic selection. UDP endpoints
are not TCP-pinged or assigned another VLESS node's load. Private AWG configs
are not persisted in SharedPreferences. Account changes clear selection;
connecting rechecks AWG access/config with the backend.

## Local checks

Prerequisites on Debian/Ubuntu: `curl git unzip xz-utils libglu1-mesa openjdk-17-jdk-headless`.

```bash
bash scripts/setup_checks.sh
export PATH="$PWD/.tools/flutter/bin:$PATH"
flutter test
flutter analyze
bash scripts/setup_android.sh
flutter build apk --debug
(cd android && ./gradlew :amneziawg-tunnel:testDebugUnitTest)
```

SDK is pinned to Flutter 3.44.0 to match existing locked packages. The previously
missing Firebase Remote Config lock entries are included; no dependency upgrade
is needed. The native AWG library requires Android 7 / API 24; Gradle enforces it.
Release signing still uses your existing `android/key.properties` (not committed).

`tool/server_catalogue_preview.dart` runs the actual server-list/card widgets with
sample nodes, without keys, ads, Firebase or a VPN connection. Managed Preview
uses this target. It verifies UI only, not Android's VPN service or live traffic.

Before a release, test real AWG 2.0/3.1 peers and VLESS on an Android phone,
including backgrounding, UDP/DNS traffic, split tunneling and protocol switching.
Native tests verify parser → userspace fields; they cannot establish a real
handshake without a configured AWG server/device. Reference binary provenance
and local lifecycle changes are documented in `android/amneziawg-tunnel/PORT.md`.
