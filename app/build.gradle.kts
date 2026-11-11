import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    buildFeatures { buildConfig = true }
    namespace = "com.h96.hwstream"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.h96.hwstream"
        minSdk = 24          // box RK3566 = Android 11, ma teniamo basso per compat
        targetSdk = 30       // evita restrizioni foreground/boot di API 31+ sulle TV box
        versionCode = 1
        versionName = "0.1.0"
        // Config privata/opzionale da local.properties (mai nel repo), vedi README
        fun prop(k: String) = "\"" + localProps.getProperty(k, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        buildConfigField("String", "ADMIN_EMAIL", prop("admin.email"))
        buildConfigField("String", "HA_URL", prop("ha.url"))
        buildConfigField("String", "HA_TOKEN", prop("ha.token"))
        buildConfigField("String", "HA_LIGHTS", prop("ha.lights"))

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")   // telefoni/box ARM 64 e 32 bit
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    ndkVersion = "26.1.10909125"

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // server HTTP embedded leggero (un solo jar)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // WebSocket per il controllo PTZ in tempo reale (joystick) sulla stessa porta 8080
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
}
