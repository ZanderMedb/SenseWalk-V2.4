package com.travessia.segura.benchmark

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File

/**
 * Somente em APK de depuração: grava no armazenamento interno do app os logs do próprio processo
 * (o Android só permite que um app leia os próprios logs), para análise depois de testes de rua sem adb.
 * Arquivo rotativo: files/sensewalk_logs/sensewalk.log (+ .1 .. .3), até ~8 MB no total.
 * Recuperar: adb shell run-as com.travessia.segura tar -C files -cf - sensewalk_logs > logs.tar
 */
object DebugFileLog {
    private var process: Process? = null
    private val TAGS = listOf("SenseWalkSeq", "SenseWalkVeh", "SenseWalkOrientation", "SenseWalkYaw", "SenseWalkPerf", "RoadInference",
        "MainActivity", "VoiceCmd", "VoiceSystem", "YoloDetector", "AndroidRuntime")

    @Synchronized fun start(context: Context) {
        if (process != null) return
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        runCatching {
            val dir = File(context.filesDir, "sensewalk_logs").apply { mkdirs() }
            val file = File(dir, "sensewalk.log")
            val cmd = mutableListOf("logcat", "-v", "time", "-f", file.absolutePath, "-r", "2048", "-n", "3")
            cmd += TAGS.map { "$it:V" }
            cmd += "*:S"
            process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            Log.i("MainActivity", "debug_file_log=${file.absolutePath}")
        }.onFailure { Log.w("MainActivity", "debug_file_log_failed ${it.message}") }
    }

    @Synchronized fun stop() { process?.destroy(); process = null }
}
