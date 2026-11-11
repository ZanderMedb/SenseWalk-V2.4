package com.travessia.segura.road

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import com.travessia.segura.config.AppConfig

data class RoadDebugFrame(val labels: ByteArray?, val result: RoadSegmentationResult?, val state: AnalysisState,
    val inferenceMs: Long, val totalMs: Long, val dropped: Long, val reason: String,
    val frameWidth: Int = 1, val frameHeight: Int = 1,
    val segmentationFps: Float = 0f, val yoloFps: Float = 0f, val callbackFps: Float = 0f)

/** Não cria captura nem fila. CameraX KEEP_ONLY_LATEST / canal X5 de capacidade 1 fornecem frames. */
class InferenceCoordinator(
    private val context: Context, private val config: AppConfig,
    private val manual: () -> Boolean,
    private val onYoloState: (Boolean) -> Unit,
    private val onDebug: (RoadDebugFrame) -> Unit
) : AutoCloseable {
    @Volatile var running = false
    @Volatile var lastFrameArrivalMs = 0L; private set
    private var segmentor: RoadSegmentationManager? = null
    private var machine = AutomaticDetectionManager()
    private var lastSegmentation = 0L
    private var lastYolo = 0L
    private var dropped = 0L
    private var automaticYolo = false
    private var failure = false
    private var lastSegmentationCostMs = 0L
    private val telemetry = RoadPerformanceLogger(context)
    private var epoch = 0L
    private var captures = 0
    private var segments = 0
    private var yolos = 0
    @Volatile var successfulYoloFrames = 0L; private set
    @Volatile private var lastSuccessfulYoloAt = 0L
    val hasRecentYoloResult: Boolean get() = successfulYoloFrames > 0 && SystemClock.elapsedRealtime()-lastSuccessfulYoloAt in 0..1500
    fun start() { running = true; lastFrameArrivalMs = SystemClock.elapsedRealtime() }
    fun suspendMonitoring() { running = false }

    @Synchronized fun process(bitmap: Bitmap, rotation: Int, capturedAtMs: Long, yolo: () -> Unit) {
        val started = SystemClock.elapsedRealtime()
        lastFrameArrivalMs = started
        val cpuStarted = android.os.Debug.threadCpuTimeNanos()
        if (epoch == 0L) epoch = started
        captures++
        val beforeTick = machine.state
        machine.tick(started)
        if (machine.state != beforeTick) onDebug(RoadDebugFrame(null,null,machine.state,0,0,dropped,"confirmation_timeout"))
        var segMs = 0L
        var yoloMs = 0L
        if (started - capturedAtMs !in 0..1500) { dropped++; reportStaleResult(); return }
        if (running && config.automaticRoadDetection && !failure && machine.state != AnalysisState.AWAITING_CONFIRMATION) {
            if (machine.state == AnalysisState.STOPPED) {
                machine = AutomaticDetectionManager(config.roadConfirmationMs.toLong(), config.roadHoldMs.toLong(), config.roadCooldownMs.toLong())
                machine.start()
            }
            // Em análise intensificada, reservar ~80% do orçamento para YOLO, não prometer FPS inviável.
            val interval = if (automaticYolo || manual()) maxOf(1000L / config.roadSegmentationFps, lastSegmentationCostMs * 5)
                else 1000L / config.roadSegmentationFps
            if (started - lastSegmentation >= interval) {
                lastSegmentation = started
                var upright: Bitmap? = null
                try {
                    val engine = segmentor ?: RoadSegmentationManager(context).also { segmentor = it }
                    upright = if (rotation == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                        Matrix().apply { postRotate(rotation.toFloat()) }, true)
                    val labels = engine.segment(upright)
                    segments++; segMs = engine.lastInferenceMs
                    val finished = SystemClock.elapsedRealtime()
                    lastSegmentationCostMs = finished - started
                    if (!running) return
                    if (finished - capturedAtMs > 1500) {
                        dropped++
                        onDebug(RoadDebugFrame(null,null,AnalysisState.INCONCLUSIVE,engine.lastInferenceMs,
                            finished-capturedAtMs,dropped,"ffnet_stale"))
                        return
                    }
                    val result = RoadSceneAnalyzer.analyze(labels, 256, 128, capturedAtMs)
                    val state = machine.update(result, finished)
                    val seconds = ((finished - epoch) / 1000f).coerceAtLeast(1f)
                    val wanted = state == AnalysisState.ACTIVE_ANALYSIS
                    if (wanted != automaticYolo) {
                        automaticYolo = wanted
                        if (!manual()) onYoloState(wanted)
                    }
                    onDebug(RoadDebugFrame(if (config.roadDebugOverlay) labels else null, result, state,
                        engine.lastInferenceMs, finished - capturedAtMs, dropped,
                        if (result.possibleRoadBoundary) "boundary" else if (result.roadDetected) "road" else "inconclusive",
                        upright.width, upright.height, segments / seconds, yolos / seconds, captures / seconds))
                } catch (error: Exception) {
                    failure = true; machine.error()
                    Log.e("RoadInference", "FFNet indisponível", error)
                    // Preservar YOLO já ativo; a falha nunca é silenciosa.
                    onDebug(RoadDebugFrame(null, null, AnalysisState.ERROR, 0, 0, dropped, "model_error"))
                } finally { if (upright != null && upright !== bitmap) upright.recycle() }
            }
        }
        // Se a segmentação falhou ou foi desativada, não desligar análise manual.
        val enabled = manual() || (running && automaticYolo)
        val now = SystemClock.elapsedRealtime()
        if (enabled && now - capturedAtMs <= 1500 && now - lastYolo >= 1000 / config.roadYoloFps) {
            lastYolo = now
            if (!manual()) onYoloState(true)
            val yoloStarted = SystemClock.elapsedRealtime()
            yolo()
            yolos++; yoloMs = SystemClock.elapsedRealtime() - yoloStarted
        } else dropped++
        if (config.benchmarkEnabled) {
            val end = SystemClock.elapsedRealtime()
            val seconds = ((end-epoch)/1000f).coerceAtLeast(1f)
            runCatching { telemetry.record(end, machine.state, segMs, yoloMs, end-capturedAtMs,
                (android.os.Debug.threadCpuTimeNanos()-cpuStarted)/1_000_000, dropped,
                captures/seconds, segments/seconds, yolos/seconds) }
                .onFailure { Log.w("RoadInference", "Falha ao registrar métricas", it) }
        }
    }
    @Synchronized fun observeYolo(hasEvidence: Boolean) { machine.observeYolo(hasEvidence, SystemClock.elapsedRealtime()) }
    @Synchronized fun answerCrossing(accepted: Boolean): Boolean {
        if (!running) return false
        val started = machine.answer(accepted, SystemClock.elapsedRealtime())
        if (started) {
            successfulYoloFrames = 0
            lastSegmentation = 0L // validar um frame novo, sem rejeitar o sim por uma leitura antiga
            onDebug(RoadDebugFrame(null,null,AnalysisState.STARTING_ANALYSIS,0,0,dropped,"consent_received"))
        }
        Log.i("RoadInference", "Consent accepted=$accepted scheduled=$started state=${machine.state}")
        return started
    }
    @Synchronized fun completedYoloFrame() {
        successfulYoloFrames++
        lastSuccessfulYoloAt = SystemClock.elapsedRealtime()
        if (successfulYoloFrames == 1L) Log.i("RoadInference","First fresh YOLO result after consent/manual activation")
    }
    fun reportVideoError() {
        onDebug(RoadDebugFrame(null,null,AnalysisState.ERROR,0,0,dropped,"video_error"))
    }
    fun reportStaleResult() {
        onDebug(RoadDebugFrame(null,null,AnalysisState.INCONCLUSIVE,0,0,dropped,"stale_frame"))
    }
    @Synchronized fun release() {
        segmentor?.close(); segmentor = null
        machine.stop(); automaticYolo = false; failure = false; lastSegmentation = 0
        successfulYoloFrames = 0
        telemetry.close(); epoch = 0; captures = 0; segments = 0; yolos = 0; dropped = 0
    }
    override fun close() { running = false; release() }
}
