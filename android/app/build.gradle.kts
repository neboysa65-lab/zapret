import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    // START: FlutterFire Configuration
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    // END: FlutterFire Configuration
    id("org.jetbrains.kotlin.android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "app.vless.vpn"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by flutter_local_notifications (uses java.time APIs)
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "app.vless.vpn"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion // Lowered for better compatibility
        targetSdk = 35
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        
        // Явно указываем архитектуры для native библиотек V2Ray
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }
    
    // Правильная обработка native библиотек для AAB
    packaging {
        jniLibs {
            useLegacyPackaging = true  // Критично для VPN - извлекает .so файлы
        }
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt")
        }
    }

    signingConfigs {
        create("release") {
            val keystoreProperties = Properties()
            val keystorePropertiesFile = rootProject.file("key.properties")
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // Включаем минификацию с proguard правилами для защиты V2Ray классов
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Отключаем автоматическую загрузку маппингов Crashlytics, так как она падает с 502 Bad Gateway
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
    }
    
    // Дополнительные настройки для корректной работы с AAB
    bundle {
        language {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true  // Split по архитектурам, но с правильными native libs
        }
    }
}

dependencies {
    // Заменяем устаревший Play Core на новые разделенные библиотеки
    implementation("com.google.android.play:feature-delivery:2.1.0")
    implementation("com.google.android.play:asset-delivery:2.2.2")
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:review:2.0.1")

    // Core library desugaring for flutter_local_notifications (java.time backport)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Separate Hysteria2 engine (ported from gwapk). VLESS keeps using
    // flutter_v2ray_plus; only Hysteria2 servers are routed through this module.
    implementation(project(":hysteria2_engine"))
}

flutter {
    source = "../.."
}

// Workaround for "Task 'wrapper' not found in project ':app'"
tasks.register("wrapper") {
    description = "Delegates wrapper task to root project"
    group = "Build Setup"
    dependsOn(rootProject.tasks.named("wrapper"))
}

// Workaround for "Task 'prepareKotlinBuildScriptModel' not found in project ':app'"
try {
    tasks.register("prepareKotlinBuildScriptModel") {
        // This is an empty task to satisfy the IDE sync if the plugin fails to register it
    }
} catch (e: Exception) {
    // Ignore if task already exists
}


