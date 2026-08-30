plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// VLEE engine — 1:1 port of the proven GW protocol module (gwvpn-new/gw-tunnel):
// SSH (sshj) over an HTTP-payload-injected socket -> local SOCKS5 ->
// hev-socks5-tunnel -> TUN. Only identifiers/branding were renamed; the
// obfuscated constants (payload template, UA, wire keys) are byte-identical.
android {
    namespace = "com.witvpn.vlee"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Транзитивные bc*.jar (sshj -> bcpkix/bcutil) тащат дубли манифестов —
    // без этого mergeReleaseJavaResource падает на "4 files found".
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/9/OSGI-INF/*",
            )
        }
    }
}

dependencies {
    // hev.htproxy.TProxyService + libhev-socks5-tunnel.so live in the
    // hysteria2_engine module; the class is packaged once via the app module.
    compileOnly(project(":hysteria2_engine"))
    implementation("com.hierynomus:sshj:0.39.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
