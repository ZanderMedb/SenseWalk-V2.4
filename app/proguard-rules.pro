# ══════════════════════════════════════════════════════════════════════════════
# SENSEWALK — Regras ProGuard/R8
# ══════════════════════════════════════════════════════════════════════════════

# ─────────────────────────────────────────────────────────────────────────────
# 1. TENSORFLOW LITE — Necessário para evitar crashes em runtime
# ─────────────────────────────────────────────────────────────────────────────

# Manter todas as classes do TFLite (usa JNI e reflexão internamente)
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# Manter native methods (JNI)
-keepclasseswithmembernames class * {
    native <methods>;
}

# TFLite GPU delegate
-keep class org.tensorflow.lite.gpu.GpuDelegate { *; }
-keep class org.tensorflow.lite.gpu.GpuDelegateFactory { *; }

# Evitar warnings do TFLite
-dontwarn org.tensorflow.lite.**

# ─────────────────────────────────────────────────────────────────────────────
# 2. CAMERAX — Usa reflexão para instanciar componentes
# ─────────────────────────────────────────────────────────────────────────────

-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Camera2 interop
-keep class android.hardware.camera2.** { *; }

# ─────────────────────────────────────────────────────────────────────────────
# 3. DATA CLASSES DO PROJETO — Usadas em Maps e coleções
# ─────────────────────────────────────────────────────────────────────────────

# Manter data classes do pacote detection (usadas em estruturas dinâmicas)
-keep class com.travessia.segura.detection.RawDetection { *; }
-keep class com.travessia.segura.detection.TrackedDetection { *; }
-keep class com.travessia.segura.detection.VeiculoInfo { *; }
-keep class com.travessia.segura.detection.GhostData { *; }
-keep class com.travessia.segura.detection.VeiculoSessao { *; }
-keep class com.travessia.segura.detection.CinematicaResult { *; }

# Manter a OverlayView.BoxInfo (usada em listas passadas entre threads)
-keep class com.travessia.segura.ui.OverlayView$BoxInfo { *; }

# ─────────────────────────────────────────────────────────────────────────────
# 4. ANDROID TEXT-TO-SPEECH — Usa interface callbacks
# ─────────────────────────────────────────────────────────────────────────────

-keep class android.speech.tts.** { *; }

# ─────────────────────────────────────────────────────────────────────────────
# 5. KOTLIN — Manter metadata para reflexão limitada
# ─────────────────────────────────────────────────────────────────────────────

# Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Kotlin intrinsics (null checks, etc.)
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNullParameter(...);
    static void checkNotNullExpressionValue(...);
    static void checkParameterIsNotNull(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkNotNull(...);
}

# ─────────────────────────────────────────────────────────────────────────────
# 6. DEBUGGING — Manter info para stack traces legíveis
# ─────────────────────────────────────────────────────────────────────────────

# Manter nomes de arquivo e números de linha (essencial para crash reports)
-keepattributes SourceFile,LineNumberTable

# Se manter LineNumberTable, ocultar nome original do source file
-renamesourcefileattribute SourceFile

# Manter anotações (algumas libs dependem delas)
-keepattributes *Annotation*

# ─────────────────────────────────────────────────────────────────────────────
# 7. ENUMS — Android usa reflexão para serializar enums
# ─────────────────────────────────────────────────────────────────────────────

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─────────────────────────────────────────────────────────────────────────────
# 8. SERIALIZABLE — Se alguma classe implementar Serializable
# ─────────────────────────────────────────────────────────────────────────────

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ─────────────────────────────────────────────────────────────────────────────
# 9. VIEW CLASSES — Instanciadas via XML (reflexão pelo LayoutInflater)
# ─────────────────────────────────────────────────────────────────────────────

# OverlayView é referenciada no XML do layout
-keep class com.travessia.segura.ui.OverlayView { *; }

# ─────────────────────────────────────────────────────────────────────────────
# 10. SHAREDPREFERENCES — AppConfig usa reflexão indireta
# ─────────────────────────────────────────────────────────────────────────────

-keep class com.travessia.segura.config.AppConfig { *; }

# ─────────────────────────────────────────────────────────────────────────────
# 11. REMOVER LOGS EM RELEASE (otimização de performance)
# ─────────────────────────────────────────────────────────────────────────────

# Remove chamadas Log.d() e Log.v() no release (mantém Log.w, Log.e, Log.i)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}