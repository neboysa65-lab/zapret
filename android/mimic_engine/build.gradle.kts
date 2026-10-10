plugins {
    id("com.android.library")
}

android {
    namespace = "app.vless.vpn.mimic"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Go-движок (gomobile bind): классы + libgojni.so на каждый ABI.
    // Пересобирается workflow .github/workflows/build-mimic-aar.yml.
    api(files("libs/classes.jar"))
}
