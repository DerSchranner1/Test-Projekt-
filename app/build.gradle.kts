plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.laermwaechter"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.laermwaechter"
        minSdk = 26
        targetSdk = 35
        // GitHub sets the run number, so every build installs over the previous one.
        versionCode = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0." + (System.getenv("BUILD_NUMBER") ?: "0")
    }

    signingConfigs {
        // Fixed key kept in the repository, so new APKs install over older ones.
        // Fine for a personal sideloaded app; a Play Store app would keep its key secret.
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = "laermwaechter"
            keyAlias = "laermwaechter"
            keyPassword = "laermwaechter"
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

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
