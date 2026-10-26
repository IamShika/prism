plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.prism.musicplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prism.musicplayer"
        minSdk = 26
        targetSdk = 35
        // On GitHub every build gets a higher number automatically, which Android needs
        // before it lets a new version install over an old one.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.0"
    }

    // The permanent signing key is read from environment variables that the GitHub
    // workflow fills in from your repository secrets. See README ("Signing").
    val keystorePath: String? = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                // trim() ignores stray spaces or line breaks pasted into the secret
                keyAlias = System.getenv("KEY_ALIAS")?.trim().takeUnless { it.isNullOrEmpty() } ?: "prism"
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Permanent key when available, otherwise the temporary debug key.
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    // Audio engine (ExoPlayer): playback, speed, pitch and effects
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    // Notification, lock screen and Bluetooth controls (MediaSession)
    implementation("androidx.media3:media3-session:1.4.1")
}
