plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Cada build de GitHub Actions lleva su propio número; así la versión nueva se instala encima de la vieja
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.example.dualcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.dualcam"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
        // solo celulares de 64 bits (como el Edge 50 Ultra): la app pesa menos
        ndk { abiFilters += "arm64-v8a" }
    }

    // Firma fija (siempre la misma llave): cada versión nueva se instala encima de la anterior sin desinstalar
    signingConfigs {
        create("fixed") {
            storeFile = file("keystore/dualcam.jks")
            storePassword = "dualcam123"
            keyAlias = "dualcam"
            keyPassword = "dualcam123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // el modelo de profundidad se lee directo del APK (sin comprimir)
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    // IA en el celular para "Foto 3D" (profundidad con Depth Anything V2)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
