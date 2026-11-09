import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// local.properties não é versionado. Uma variável de ambiente/Gradle tem precedência
// para CI; a chave nunca deve ser escrita em código ou no controle de versão.
val graphHopperApiKey: String by lazy {
    val local = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use(local::load)
    providers.gradleProperty("GRAPHHOPPER_API_KEY").orNull
        ?: System.getenv("GRAPHHOPPER_API_KEY")
        ?: local.getProperty("GRAPHHOPPER_API_KEY")
        ?: ""
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

        // A chave é lida apenas do local.properties/variável Gradle da máquina.
        // Nunca adicione uma chave real ao repositório.
        buildConfigField("String", "GRAPHHOPPER_API_KEY", "\"$graphHopperApiKey\"")
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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // ── Android Core ──
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // ── Localização e reconhecimento de atividade ──
    implementation("com.google.android.gms:play-services-location:21.3.0")

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

    // Parser JSON para respostas do Overpass, Nominatim e GraphHopper.
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")

}
