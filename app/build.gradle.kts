plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.firdaus.rekab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.firdaus.rekab"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("rekab") {
            storeFile = rootProject.file("keystore/rekab.jks")
            storePassword = "rekab123"
            keyAlias = "rekab"
            keyPassword = "rekab123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rekab")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("rekab")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
