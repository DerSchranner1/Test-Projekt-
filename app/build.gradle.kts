plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.schranner.tastatur"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.schranner.tastatur"
        minSdk = 29
        targetSdk = 34
        versionCode = 6
        versionName = "0.6"
    }

    signingConfigs {
        // Fester Debug-Schlüssel im Repo, damit Updates sich ohne Deinstallieren installieren lassen.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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

    testOptions {
        unitTests {
            // Für die Bild-Tests (Tastatur wird ohne Handy gerendert)
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "2g" }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
