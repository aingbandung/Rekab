plugins {
    id("com.android.application")
}

android {
    namespace = "com.firdaus.rekab"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.firdaus.rekab"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("releaseConfig") {
            val keystoreFile = rootProject.file("keystore/rekab.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "rekab123"
                keyAlias = "rekab"
                keyPassword = "rekab123"
            } else {
                storeFile = file("${rootDir}/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("releaseConfig")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
