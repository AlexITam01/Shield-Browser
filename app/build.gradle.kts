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
        versionCode = 1
        versionName = "0.1.0"
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
    // Only dependency: needed for document-start script injection (canvas shield).
    implementation("androidx.webkit:webkit:1.11.0")
}
