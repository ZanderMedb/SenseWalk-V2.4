package com.travessia.segura.benchmark

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.travessia.segura.detection.AdvancedVehicleTracker
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BenchmarkLogger(private val context: Context) {

    data class FrameMetrics(
        val source: String,
        val frameIndex: Long,
        val timestampMs: Long,
        val totalMs: Long,
        val inferenceMs: Long,
        val opticalFlowMs: Long,
        val rawVehicleCount: Int,
        val contextCount: Int,
        val trackedCount: Int,
        val ghostCount: Int,
        val cameraDx: Float,
        val cameraDy: Float,
        val motionConfidence: Float,
        val trackerStats: AdvancedVehicleTracker.TrackerStats,
        val state: String,
        val situation: String
    )

    data class SessionResult(
        val sessionName: String,
        val frameCsv: File,
        val summaryFile: File
    )

    private var writer: BufferedWriter? = null
    private var sessionDir: File? = null
    private var sessionStartedMs: Long = 0L
    private var frameCount: Long = 0
    private var totalInferenceMs: Long = 0
    private var totalFrameMs: Long = 0
    private var totalFlowMs: Long = 0
    private var totalRawVehicles: Long = 0
    private var totalTracked: Long = 0
    private var totalGhosts: Long = 0
    private var totalNewTracks: Long = 0
    private var totalRemovedTracks: Long = 0
    private var totalLowRecoveries: Long = 0
    private var maxCameraMotion: Float = 0f
    private var currentSource: String = "unknown"

    @Synchronized
    fun startSession(source: String) {
        stopSession()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks")
        root.mkdirs()
        val dir = File(root, "session_${stamp}_${source}")
        dir.mkdirs()
        val csv = File(dir, "frames.csv")
        writer = BufferedWriter(FileWriter(csv, false)).apply {
            write(
                "timestamp_ms,frame,source,total_ms,inference_ms,optical_flow_ms," +
                    "raw_vehicles,context_objects,tracked,ghosts,camera_dx,camera_dy," +
                    "motion_confidence,active_tracks,new_tracks,removed_tracks," +
                    "high_conf_matches,low_conf_recoveries,state,situation\n"
            )
            flush()
        }
        sessionDir = dir
        sessionStartedMs = System.currentTimeMillis()
        currentSource = source
        frameCount = 0
        totalInferenceMs = 0
        totalFrameMs = 0
        totalFlowMs = 0
        totalRawVehicles = 0
        totalTracked = 0
        totalGhosts = 0
        totalNewTracks = 0
        totalRemovedTracks = 0
        totalLowRecoveries = 0
        maxCameraMotion = 0f
    }

    @Synchronized
    fun logFrame(m: FrameMetrics) {
        val w = writer ?: return
        val stats = m.trackerStats
        w.write(
            listOf(
                m.timestampMs,
                m.frameIndex,
                csv(m.source),
                m.totalMs,
                m.inferenceMs,
                m.opticalFlowMs,
                m.rawVehicleCount,
                m.contextCount,
                m.trackedCount,
                m.ghostCount,
                "%.3f".format(Locale.US, m.cameraDx),
                "%.3f".format(Locale.US, m.cameraDy),
                "%.3f".format(Locale.US, m.motionConfidence),
                stats.activeTracks,
                stats.newTracks,
                stats.removedTracks,
                stats.highConfidenceMatches,
                stats.lowConfidenceRecoveries,
                csv(m.state),
                csv(m.situation)
            ).joinToString(",")
        )
        w.newLine()
        if (m.frameIndex % 20L == 0L) w.flush()

        frameCount++
        totalInferenceMs += m.inferenceMs
        totalFrameMs += m.totalMs
        totalFlowMs += m.opticalFlowMs
        totalRawVehicles += m.rawVehicleCount
        totalTracked += m.trackedCount
        totalGhosts += m.ghostCount
        totalNewTracks += stats.newTracks
        totalRemovedTracks += stats.removedTracks
        totalLowRecoveries += stats.lowConfidenceRecoveries
        val mag = kotlin.math.hypot(m.cameraDx, m.cameraDy)
        if (mag > maxCameraMotion) maxCameraMotion = mag
    }

    @Synchronized
    fun stopSession(): SessionResult? {
        val dir = sessionDir ?: return null
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null

        val end = System.currentTimeMillis()
        val durationMs = (end - sessionStartedMs).coerceAtLeast(1L)
        val avgInference = if (frameCount > 0) totalInferenceMs.toDouble() / frameCount else 0.0
        val avgFrame = if (frameCount > 0) totalFrameMs.toDouble() / frameCount else 0.0
        val avgFlow = if (frameCount > 0) totalFlowMs.toDouble() / frameCount else 0.0
        val effectiveFps = frameCount * 1000.0 / durationMs.toDouble()
        val summary = File(dir, "summary.txt")
        summary.writeText(
            buildString {
                appendLine("SenseWalk V2.4 - Benchmark Session")
                appendLine("source=$currentSource")
                appendLine("frames=$frameCount")
                appendLine("duration_ms=$durationMs")
                appendLine("effective_fps=${"%.2f".format(Locale.US, effectiveFps)}")
                appendLine("avg_inference_ms=${"%.2f".format(Locale.US, avgInference)}")
                appendLine("avg_total_frame_ms=${"%.2f".format(Locale.US, avgFrame)}")
                appendLine("avg_optical_flow_ms=${"%.2f".format(Locale.US, avgFlow)}")
                appendLine("avg_raw_vehicles_per_frame=${if (frameCount > 0) "%.2f".format(Locale.US, totalRawVehicles.toDouble() / frameCount) else "0.00"}")
                appendLine("avg_tracked_per_frame=${if (frameCount > 0) "%.2f".format(Locale.US, totalTracked.toDouble() / frameCount) else "0.00"}")
                appendLine("ghost_frames_total=$totalGhosts")
                appendLine("new_tracks_total=$totalNewTracks")
                appendLine("removed_tracks_total=$totalRemovedTracks")
                appendLine("low_confidence_recoveries_total=$totalLowRecoveries")
                appendLine("max_camera_motion_px=${"%.2f".format(Locale.US, maxCameraMotion)}")
                appendLine()
                appendLine("Observacao: falsos positivos/negativos exigem ground truth manual e nao sao inferidos automaticamente.")
            }
        )

        val result = SessionResult(dir.name, File(dir, "frames.csv"), summary)
        sessionDir = null
        return result
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    companion object {
        fun latestSessionFiles(context: Context): List<File> {
            val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks")
            val latest = root.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() }
                ?: return emptyList()
            return listOf(File(latest, "summary.txt"), File(latest, "frames.csv")).filter { it.exists() }
        }

        fun shareLatest(context: Context): Boolean {
            val files = latestSessionFiles(context)
            if (files.isEmpty()) return false
            val authority = "${context.packageName}.fileprovider"
            val uris = ArrayList(files.map { FileProvider.getUriForFile(context, authority, it) })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("SenseWalk benchmark", uris.first())
            }
            context.startActivity(Intent.createChooser(intent, "Compartilhar benchmark SenseWalk"))
            return true
        }
    }
}
