plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.euler.catalog"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore.jks")
            storePassword = "ubydstore"
            keyAlias = "ubyd"
            keyPassword = "ubydstore"
        }
    }

    defaultConfig {
        applicationId = "com.euler.catalog"
        minSdk = 24
        targetSdk = 34
        versionCode = 24
        versionName = "3.3"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
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
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // Cliente ADB: shell no próprio carro via 127.0.0.1:5555 (Device Owner sem notebook, cluster).
    implementation("dev.mobile:dadb:1.2.10")
}
