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
        versionCode = 131
        versionName = "19.2"
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        error += "MissingTranslation"
        warning += "ExtraTranslation"
        // A string, an array or an animation that nothing uses is the
        // wreckage of a feature that was taken out — thirty-four of them
        // had piled up, including the labels of a mode switch removed
        // several versions ago. They cost nothing to run and a great deal
        // to read: somebody looking for how a screen is worded finds two
        // candidates and no way to tell which one the app says.
        error += "UnusedResources"
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
    // The clock's arithmetic — when an alarm next rings, which day a
    // repeating reminder lands on, what a Roman numeral looks like — runs on
    // the JVM with no Android in it, so it can be tested without a device.
    // Two of the bugs this suite pins down had each cost a whole release to
    // find by hand.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
