plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.em87.weirdclock.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.em87.weirdclock"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "8.2"
    }

    // Same shared key as the phone app, so the pair installs together.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("signing/weirdclock.keystore")
            storePassword = "weirdclock"
            keyAlias = "weirdclock"
            keyPassword = "weirdclock"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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
}
