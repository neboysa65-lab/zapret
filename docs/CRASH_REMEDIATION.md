# Firebase crash follow-up

Numbering matches the nine reports supplied for this task. These are stack
traces, not reproducible device cases. The old obfuscated classes cannot be
conclusively identified without the mapping file for each affected release.
No uncaught-exception swallowing or Crashlytics filtering was added.

| Report | Change / current status |
| --- | --- |
| 1 — missing `libflutter.so` | A native launcher checks extracted libraries and ABI splits **before** creating Flutter, using the process ABI. Incomplete side-loaded installations show a reinstall message instead of entering the failing engine. Distribute the complete universal APK or install the full Play split set; a missing binary cannot be repaired by Dart. |
| 2 — OOM in `DatagramSocket.receive` | Bounded SOCKS/DNS workers and queues, capped UDP associations, control-socket EOF detection and tracked socket/channel closure prevent abandoned sessions accumulating. Cache size and payload size are bounded. Stress tests cover shutdown and UDP EOF; the production heap fragmentation itself was not reproduced. |
| 3 — `registerReceiver(..., flags)` missing on Android 7 | MainActivity uses `ContextCompat.registerReceiver`, including its pre-26 fallback. This fix was already present on `master` and is preserved for VLEE/Hysteria; AWG uses an in-process status listener instead of adding a receiver. |
| 4 — NPE in `FrameLayout.onMeasure` | Ad initialization/show operations are single-flight; loaded/showing ads have separate ownership and teardown follows dismissal. Native Yandex 7.18.7 is pinned within the existing 7.x API. The trace contains framework frames only: an SDK/view-hierarchy race is possible, not proven. Device reproduction remains required. |
| 5 — `BinderInternal.GcWatcher.finalize` timeout | Socket/channel/worker cleanup reduces GC and descriptor pressure; no Android finalizer watchdog is disabled. This framework timeout is not safely fixable with a catch in application code. Confirm on the affected devices after rollout. |
| 6 — NPE in `ViewGroup.dispatchTouchEvent` | Same ad lifecycle hardening as #4, with the current stable Flutter embedding. No blanket NPE catch on touch dispatch: that would hide hierarchy corruption. The supplied stack does not establish a precise application source line. |
| 7 — input-channel file descriptors missing | Track and close accepted TCP sockets, UDP relays and SSH channels at VPN stop; release UDP associations on control EOF. This removes a concrete descriptor leak path. Binder/IME failures can also be OEM/system issues, so monitor the original device families. |
| 8 — executor rejects task after termination | Shutdown marks the server stopped before closing resources; accept and DNS submission handle bounded rejection; nested relay rejection unwinds through `finally` and closes both sides. Removed caller-runs behavior that could deadlock bidirectional relays under saturation. |
| 9 — cannot allocate JNI environment / create thread | Fixed upper bounds on SOCKS workers and a separate eight-worker DNS pool, bounded queue, deduplication and idle expiry avoid unbounded thread growth. Stress tests repeat start/stop with 150 simultaneous local sockets. |

DNS-over-TCP also now normalizes transaction IDs for cache/dedup keys, restores
the requesting ID, parses answer TTLs correctly, decrements cached TTLs and
closes timed-out SSH DNS channels. This preserves the anti-DNS-flood purpose of
the previous patch without caching an invalid response or leaking blocked reads.

## Before production release

- Build with `scripts/setup_checks.sh`; use the existing private release signing
  setup. Preserve the exact APK/AAB and R8 mapping for Crashlytics symbolication.
- Run `flutter test`, `flutter analyze`, Android unit tests for `app`,
  `amneziawg-tunnel` and `vlee_engine`, and the release R8 compilation.
- On Android 7 and current Android, exercise free/Premium ads, dismiss/back,
  background/foreground, cancellation, repeated connects, and AWG↔VLESS↔VLEE↔HY2.
- Run long traffic/connection stress on a low-memory phone; compare thread and
  descriptor counts before/after disconnect and watch the affected Firebase
  clusters by application version. Those checks cannot be replaced by JVM tests.

Yandex release notes: https://ads.yandex.com/helpcenter/en/dev/android7/changelog-android
7.18.7 includes memory/insets fixes; its notes do **not** assert that these exact
two framework NPEs are fixed. No such guarantee is made here either.
