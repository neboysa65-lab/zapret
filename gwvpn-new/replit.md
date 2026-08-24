# GreyWebVPN (Strong VPN) — Android

Multi-protocol Android VPN client. Kotlin/Java, Gradle, Clean Architecture with Hilt DI.

**This is a native Android app — it does not run in the Replit preview pane.** The output is an APK
built from the command line.

## Modules

| Module | Purpose |
| --- | --- |
| `app` | Application: UI, account/billing, server selection, ads. Package `app.greywebs.vpn`, namespace `com.witvpn.ikev2` |
| `vyom-tun-sdk` | Xray / VLESS / Socks5 tunneling. Has a native CMake build (`src/main/cpp`) |
| `amneziawg-tunnel` | AmneziaWG (WireGuard-derived) protocol |

IKEv2/IPSec is provided by bundled strongSwan sources under
`app/src/main/java/org/strongswan/android` with prebuilt `.so` libraries in `app/src/main/jniLibs`.

## Building

The Replit container has no Android SDK by default. It was installed to `/home/runner/android-sdk`
with `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`, `ndk;27.0.12077973`, `cmake;3.22.1`.

Android Gradle Plugin needs JDK 17 — the container's default `java` is GraalVM 19, so `JAVA_HOME`
must be set explicitly:

```bash
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(ls /nix/store/*openjdk-17*/bin/javac | head -1))))
export ANDROID_HOME=/home/runner/android-sdk
./gradlew :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`.

### Files required locally but not in git

Both are listed in `.gitignore` and must be supplied per environment:

- `local.properties` — must contain `sdk.dir=/home/runner/android-sdk`
- `app/google-services.json` — Firebase config (Crashlytics, Analytics, Messaging).
  A placeholder exists locally purely so the build can run; **replace it with the real file
  from the Firebase console before producing any build for distribution.**

### Release builds

`signingConfigs.release` reads the environment variables `ANDROID_KEY_STOREFILE`,
`SIGNING_PASSWORD`, and `SIGNING_KEY_ALIAS`. Without them, only debug builds work.

## Ads — CAS.AI (CleverAdsSolutions)

Ads are served through CAS.AI mediation. The previous direct Yandex Mobile Ads integration
was removed.

- Wrapper: `app/src/main/java/com/witvpn/ikev2/presentation/CasAds.kt`
- Initialized from `MyApp.onCreate()`, main process only
- One interstitial, shown to **non-premium** users on VPN connect/disconnect from
  `ConnectFragment` (placements `vpn_connect` / `vpn_disconnect`)
- CAS ID is the build config field `CAS_ID` in `app/build.gradle` (currently `app.greywebs.vpn`)
- The SDK and network adapters are contributed by the `com.cleveradssolutions.gradle-plugin`
  plugin via the `cas { }` block; `includeVPNCompliantAds = true` selects the network set that
  complies with VPN traffic policies
- Mediated network Maven repositories live in the root `build.gradle` `allprojects` block
- Debug builds run in CAS test ad mode, so development never generates billable impressions

Note: the CAS VPN-compliant bundle includes **Yango Ads**, which is Yandex's ad network. Yandex
classes therefore still appear in the APK, but only as one mediated source inside CAS rather than
a direct integration.

### Configuration is fetched at build time, not committed

The CAS Gradle plugin downloads the app's mediation config from the CAS dashboard during the build
and embeds it as a raw resource (`cas_settings*.json` under `app/build/generated/res/`). That config
carries the real AdMob application ID and every network's ad unit IDs, so:

- **Do not add a `cas_settings.json` by hand.** The plugin generates it.
- **Do not declare `com.google.android.gms.ads.APPLICATION_ID` in the manifest.** The plugin injects
  it from the downloaded config; declaring it manually makes the plugin emit a conflict warning.

A network connection to the CAS servers is therefore required at build time.

## Toolchain notes

Kotlin is on `2.1.21` (raised from `1.9.23`), Hilt/Dagger on `2.56.2`, because every CAS SDK 4.x
artifact is compiled against Kotlin 2.x metadata and a 1.9 compiler cannot read it. Kotlin 2.x
also promotes some deprecations to errors — `String.toLowerCase(Locale)` became
`String.lowercase(Locale)` in `presentation/utils/Utils.kt`.
