# Keep all native bridge classes — JNI calls reference them by exact
# fully-qualified name; ProGuard renaming would break libwg-go.so.
-keep class org.amnezia.awg.GoBackend { *; }
-keep class org.amnezia.awg.backend.GoBackend { *; }
-keep class org.amnezia.awg.backend.GoBackend$VpnService { *; }

# Config parser uses reflection-friendly value classes; preserve their
# constructors and fields.
-keep class org.amnezia.awg.config.** { *; }
-keep class org.amnezia.awg.crypto.** { *; }
-keep class org.amnezia.awg.util.** { *; }
