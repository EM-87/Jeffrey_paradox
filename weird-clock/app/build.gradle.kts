plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.em87.weirdclock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.em87.weirdclock"
        minSdk = 24
        targetSdk = 35
        versionCode = 62
        versionName = "11.8"
    }

    // Shared signing key committed to the repo, so every APK — built on any
    // machine — is signed identically and always installs over the previous
    // version. Fine for a hobby app distributed as an APK; a Play Store
    // release would need a private key instead.
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // A string that never reaches values-es/ used to be invisible: the app
    // simply spoke English at that one spot. Now the build says so.
    lint {
        error += "MissingTranslation"
        warning += "ExtraTranslation"
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
