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
        versionCode = 13
        versionName = "2.4.8-quiet-transitions"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
    implementation("com.herohan:UVCAndroid:1.0.13")
    // ── Android Core ──
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // LiteRT 1.x mantém org.tensorflow.lite.Interpreter e fornece JNI alinhado a 16 KB.
    // Não incluir tensorflow-lite antigo junto: ambos fornecem as mesmas classes.
    implementation("com.google.ai.edge.litert:litert:1.4.2")

    // ── CameraX ──
    // Série compatível com compileSdk 35 e bibliotecas nativas alinhadas a 16 KB.
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ── Coroutines ──
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

}
