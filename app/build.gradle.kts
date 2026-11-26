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
        versionCode = 59
        versionName = "6.6"
    }

    buildTypes {
        getByName("debug") {
            // Ofuscacao (R8): embaralha com.euler.catalog, preserva dadb/okio/WorkManager (ver
            // proguard-rules.pro). A CI gera assembleDebug com esta keystore, entao o APK de
            // producao tambem fica ofuscado. proguard-android.txt = ofuscar/encolher SEM otimizacao
            // agressiva (mais seguro p/ ADB/reflexao).
            // IMPORTANTE: o AGP novo NAO ofusca build 'debuggable'. Entao tiramos a flag de debug
            // (continua assinado com a mesma keystore -> os carros aceitam a atualizacao). Em producao
            // nem faz sentido ser debuggable.
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
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
