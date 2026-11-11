plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.planetarium"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.planetarium"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
        // Real phones and the x86_64 emulator; dropping 32-bit keeps the native libraries small.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // The model is copied out of the APK once; compressing it only slows the build and that copy.
        noCompress += "onnx"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    val media3 = "1.8.0"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")

    // Runs the depth estimation model for "photo to spatial wallpaper".
    // Pinned on purpose: newer releases (seen in 1.31.1) bundle a telemetry client that adds the
    // INTERNET permission and a start-up provider. Check the merged manifest before upgrading.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
