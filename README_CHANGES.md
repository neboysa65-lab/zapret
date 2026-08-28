# vlees — Hysteria2 engine + in-app payment WebView

Complete change set for the two tasks. Every file is stored at the **same
relative path it has in the `vlees` repo**, so you can extract this zip over the
repo root and the files land in the right place.

Base commit these changes apply on top of: `2f29e6b`
(`Remove manual payment check button`, current `main` HEAD).

---

## What changed

### 1. Hysteria2 support (new separate engine, `flutter_v2ray_plus` untouched)

**Problem.** The subscription the app loads (backend `api-vlessvpn-app.vpnhub.xyz`,
which proxies `sub.gw-vpn.click`) contains Hysteria2 nodes. The app could **not**
use them: `flutter_v2ray_plus`' bundled Xray core has no `hysteria` outbound, so
Hysteria2 configs were mis-parsed as empty VLESS entries and never connected.

**Fix.** A brand-new, self-contained Android library module
`android/hysteria2_engine/`, **ported from the gwapk project's `vyom-tun-sdk`**
and trimmed to exactly what Hysteria2 needs. `flutter_v2ray_plus` is not touched
— VLESS / Shadowsocks / raw-JSON servers keep going through it.

New module (`android/hysteria2_engine/`):
- `src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/`
  - `libhysteria2core.so` — the Hysteria2-capable Xray core. **Renamed from
    gwapk's `libxray.so`** (its SONAME was patched to match) so it can never
    collide with `flutter_v2ray_plus`' own `libxray.so` inside the APK.
  - `libhev-socks5-tunnel.so` — TUN ↔ SOCKS bridge.
- `src/main/cpp/` — `vyom-bridge.cpp` + `CMakeLists.txt` build `libvyom-v2ray.so`
  (JNI). CMake now links `hysteria2core` instead of `xray`.
- Kotlin (`io.github.vyomtunnel.*`, package names kept **exactly** — the native
  `.so` JNI symbols depend on them, likewise `hev.htproxy.TProxyService`):
  `NativeEngine`, `TProxyService`, `VyomVpnService` (its own `VpnService` in
  process `:hysteria2_process`), `VyomVpnManager` (trimmed), `VyomPermissionActivity`,
  `VyomState`, `AssetUtils`, and a **Hysteria2-only** `LinkParser` that builds the
  Xray JSON with the SOCKS port (20808) the TUN layer expects.
- Dropped as junk (not needed for Hysteria2): boot receiver, app-inventory,
  traffic formatter, IP/profile diagnostics, and the ~30 MB geoip/geosite `.dat`
  assets (the config uses no geo rules).

Wiring into the app:
- `android/settings.gradle.kts` — `include(":hysteria2_engine")` + library plugin.
- `android/app/build.gradle.kts` — `implementation(project(":hysteria2_engine"))`.
- `android/app/.../MainActivity.kt` — MethodChannel `app.vless.vpn/hysteria2`
  (connect / disconnect / isConnected) + EventChannel forwarding engine state and
  traffic to Dart.
- `lib/services/hysteria_engine_service.dart` — **new** Dart bridge to the engine.
- `lib/models/vless_config_model.dart` — Hysteria2 awareness: `isHysteria2`,
  `obfs` / `obfsPassword` / `allowInsecure`, a canonical `hysteria2://` URL
  builder, `fromHysteria2Url(...)`, and detection of the backend's
  `protocol:"hysteria"` JSON in `fromJsonConfig(...)`.
- `lib/services/api_service.dart` — accepts `hysteria2://` / `hy2://` lines.
- `lib/services/vpn_service.dart` — routes by protocol: VLESS → `flutter_v2ray_plus`
  (unchanged), Hysteria2 → the new engine; merges status and synthesises speed
  from the engine's cumulative traffic counters.

### 2. Tariff selection back inside the app (WebView)

- `lib/screens/pay_web_screen.dart` — "Купить подписку" again loads the tariff
  page (`api-vlessvpn-app.vpnhub.xyz/pay?uid=...`) in an **in-app WebView**.
  Only when it navigates to the real checkout (`payment.wata.pro/pay-form/...`
  — any host other than ours, or a non-http bank SBP deep link like
  `bank100000000111://`) is the URL handed to the **external browser**. This keeps
  the reason the previous commit moved everything out (WebView can't open bank
  deep links → `ERR_UNKNOWN_URL_SCHEME`) while restoring in-app tariff selection.
  Premium polling unchanged (every 3 s + on resume, 10 min timeout).
- `lib/screens/subscription_screen.dart` — comment update for the new flow.
- `pubspec.yaml` — re-adds `webview_flutter: ^4.4.2`.

---

## How to apply

Option A — extract over the repo (recommended, includes the native libs):
```
unzip vlees_hysteria2_changes.zip
cp -r files/. /path/to/vlees/
cd /path/to/vlees && flutter pub get && flutter build apk
```

Option B — text patch only (no binaries; you still need the `.so` files from
`files/android/hysteria2_engine/src/main/jniLibs/`):
```
cd /path/to/vlees
git apply /path/to/CHANGES.patch
```

---

## Testing

`validate_hy2.py` fetches the real test subscription
`https://sub.gw-vpn.click/jzp2_Fc1rqXDrBu1`, extracts its 3 Hysteria2 nodes, and
reproduces the exact Dart parsing + Kotlin `LinkParser` JSON build. Result:

```
Subscription nodes: 6 total | vless=3 | hysteria2=3
[1] gwapp-vless-de01.freenets.store:443  → all checks PASS, server TCP+TLS OK
[2] gwapp-pl01.freenets.store:443        → all checks PASS, server TCP+TLS OK
[3] vleeapp-ru01.freenets.store:443      → all checks PASS, server TCP+TLS OK
RESULT: ALL PARSE/CONFIG CHECKS PASSED
```

Each node yields a valid `hysteria` (version 2) Xray outbound with `auth`, `sni`
and the SOCKS inbound on 20808 (matching the engine's TUN layer). A live QUIC
handshake can't be run outside an Android device (and the build sandbox blocks
UDP), so on-device connect testing is the final step to run on real hardware.
