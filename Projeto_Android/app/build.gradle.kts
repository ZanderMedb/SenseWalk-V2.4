plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.travessia.segura"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.travessia.segura"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.4-vision-benchmark"
    }

    // ══════════════════════════════════════════════════════════════
    // BUILD TYPES — Configuração R8 para release
    // ══════════════════════════════════════════════════════════════
    buildTypes {
        release {
            // Habilita otimização de código (tree shaking, inlining, obfuscação)
            isMinifyEnabled = true

            // Habilita shrinking de recursos (remove drawables, layouts não usados)
            isShrinkResources = true

            proguardFiles(
                // Regras padrão do Android com otimizações habilitadas
                getDefaultProguardFile("proguard-android-optimize.txt"),
                // Regras específicas do projeto
                "proguard-rules.pro"
            )
        }

        debug {
            // Debug NÃO usa R8 para facilitar depuração
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Garante que o model.tflite não seja comprimido (necessário para mmap)
    androidResources {
        noCompress += listOf("tflite")
    }
}

dependencies {
    // ── Android Core ──
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // ── TensorFlow Lite ──
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    // ── CameraX ──
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ── Coroutines ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

}