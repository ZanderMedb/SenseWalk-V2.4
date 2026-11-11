package com.travessia.segura.road

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.io.BufferedWriter

/** Telemetria local sem imagens. CPU é tempo da thread; não é utilização global nem GPU. */
class RoadPerformanceLogger(private val context: Context) : AutoCloseable {
    private var writer: BufferedWriter? = null
    fun record(time: Long, state: AnalysisState, segMs: Long, yoloMs: Long, frameMs: Long,
               cpuMs: Long, skipped: Long, captureFps: Float, segFps: Float, yoloFps: Float) {
        if (writer == null) {
            val dir = File(context.getExternalFilesDir(null), "benchmarks").apply { mkdirs() }
            writer = File(dir,"road_${System.currentTimeMillis()}.csv").bufferedWriter().apply {
                write("elapsed_ms,state,ffnet_ms,yolo_pipeline_ms,frame_age_ms,thread_cpu_ms,scheduler_skips,capture_callback_fps,ffnet_fps,yolo_fps,java_heap_bytes,thermal_status\n")
            }
        }
        val heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val thermal = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java).currentThermalStatus else -1
        writer?.write("$time,$state,$segMs,$yoloMs,$frameMs,$cpuMs,$skipped,$captureFps,$segFps,$yoloFps,$heap,$thermal\n")
    }
    override fun close() { writer?.close(); writer = null }
}
