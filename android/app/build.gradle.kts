plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.outsiderdev.outsidercast"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.outsiderdev.outsidercast"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            storeFile = file("outsidercast.jks")
            storePassword = "outsidercast"
            keyAlias = "outsidercast"
            keyPassword = "outsidercast"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.getstream:stream-webrtc-android:1.1.1")
}
