// AmneziaWG userspace tunnel module — ported from gwvpn (commit ~2026-04).
//
// The Java sources under `src/main/java/org/amnezia/awg/**` and the
// pre-built `libwg-go.so` / `libwg.so` / `libwg-quick.so` binaries
// under `src/main/jniLibs/**` are vendored as-is; only the build script
// is rewritten in Kotlin DSL to fit the rest of Rabochiy VPN's Gradle
// configuration.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.amnezia.awg.tunnel"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    buildTypes {
        release { isMinifyEnabled = false }
        debug   { isMinifyEnabled = false }
    }

    // The native loader and a few Go-runtime warnings trip these lints
    // even though everything works at runtime; matches the upstream
    // gwvpn settings.
    lint {
        disable += listOf("LongLogTag", "NewApi")
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.annotation:annotation:1.7.1")
    implementation("androidx.collection:collection:1.4.0")
    // Used only by `@NonNullForAll` package-info annotations at compile
    // time — not needed at runtime.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}
