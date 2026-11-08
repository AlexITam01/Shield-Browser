plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shieldbrowser.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shieldbrowser.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // WireGuard's native library: extract it to disk so it loads on any page-size device.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Document-start script injection (canvas shield).
    implementation("androidx.webkit:webkit:1.11.0")
    // WireGuard VPN client library.
    implementation("com.wireguard.android:tunnel:1.0.20230706")
}
