# AmneziaWG native engine

Vendored from `nolvoprosov/vless`, branch `rabochiy`, commit
`59b35a6` (`android/amneziawg-tunnel`). Java sources and the four ABI sets of
`libwg-go.so`, `libwg.so`, `libwg-quick.so` retain the reference implementation.
This is the reference's **3.1 engine**, including AWG 2.0 parameters, S1–S4,
H1–H4 ranges and I1–I5 packet templates. Do not substitute the older Maven
AmneziaWG library: its parser/core do not implement the same protocol fields.

Local changes: Android VPN session branding, foreground notification and status
thread cleanup; unsupported always-on auto-restore is not advertised. Native
libraries and protocol parsing are unchanged. Flutter channels and lifecycle
adapter live in `app/src/main/kotlin/app/vless/vpn/Awg*.kt`. Copyright/SPDX
headers from the upstream WireGuard/Amnezia sources are preserved.
