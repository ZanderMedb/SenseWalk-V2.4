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
    val segmentationFps: Float = 0f, val yoloFps: Float = 0f, val callbackFps: Float = 0f,
    val sessionToken: Long = -1L)

/** Não cria captura nem fila. CameraX KEEP_ONLY_LATEST / canal USB de capacidade 1 fornecem frames. */
class InferenceCoordinator(
    private val context: Context, private val config: AppConfig,
    private val manual: () -> Boolean,
    /** Rua confirmada pela FFNet: inicia UMA análise (token da sessão de resultados). */
    private val onRoadConfirmed: (Long) -> Unit,
    private val onDebug: (RoadDebugFrame) -> Unit,
    private val onInvalidated: () -> Unit = {},
    /** Frame atrasado isolado: não encerra a sequência guiada. */
    private val onStale: () -> Unit = onInvalidated,
    /** Injeção para testes; padrão carrega a FFNet real sob demanda. */
    private val segmenter: ((Bitmap) -> ByteArray)? = null,
    /** Thread da FFNet (testes injetam execução direta para serem determinísticos). */
    private val ffnetExecutor: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor()
) : AutoCloseable {
    private val ffnetBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val segmentLock = Any()
    @Volatile var running = false
    @Volatile var lastFrameArrivalMs = 0L; private set
    private var segmentor: RoadSegmentationManager? = null
    private var machine = AutomaticDetectionManager()
    @Volatile private var lastSegmentation = 0L
    @Volatile private var lastYolo = 0L
    private var dropped = 0L
    /** ANALYZING: YOLO ativo, FFNet suspensa. Volátil para suspender a FFNet imediatamente. */
    @Volatile var analyzing = false; private set
    @Volatile var ffnetInferences = 0L; private set
    val phase: MonitorPhase get() = when {
        analyzing -> MonitorPhase.ANALYZING
        running && config.automaticRoadDetection && !failure -> MonitorPhase.WAITING_FOR_ROAD
        else -> MonitorPhase.STOPPED
    }
    @Volatile private var failure = false
    private val telemetry = RoadPerformanceLogger(context)
    private var epoch = 0L
    private var captures = 0
    private var segments = 0
    private var yolos = 0
    @Volatile var successfulYoloFrames = 0L; private set
    @Volatile var lastRoadResult: RoadSegmentationResult? = null; private set
    @Volatile private var lastSuccessfulYoloAt = 0L
    private val validity = com.travessia.segura.camera.FrameValidity()
    val resultToken: Long get() = validity.generation
    fun invalidateSession() {
        validity.invalidate(SystemClock.elapsedRealtime())
        lastRoadResult = null
        lastSuccessfulYoloAt = 0
        successfulYoloFrames = 0
        onInvalidated()
    }
    fun acceptsResult(token: Long, capturedAtMs: Long): Boolean =
        HazardMonitoringPolicy.enabled(manual() || analyzing,running) && validity.accepts(token,capturedAtMs,SystemClock.elapsedRealtime())
    val hasRecentYoloResult: Boolean get() = successfulYoloFrames > 0 && SystemClock.elapsedRealtime()-lastSuccessfulYoloAt in 0..1500
    /**
     * Algum modelo vai usar um frame agora? Se não, o analisador descarta o ImageProxy ANTES da conversão.
     * Mesmas condições de [process]: não reduz a frequência do YOLO, só evita converter frames que
     * seriam descartados.
     */
    fun wantsFrame(now: Long = SystemClock.elapsedRealtime()): Boolean {
        val yoloDue = HazardMonitoringPolicy.enabled(manual() || analyzing, running) &&
            now - lastYolo >= 1000 / config.roadYoloFps
        val ffnetDue = !analyzing && !manual() && running && config.automaticRoadDetection && !failure &&
            now - lastSegmentation >= 1000L / config.roadSegmentationFps
        return yoloDue || ffnetDue
    }
    fun start() {
        if (!running) invalidateSession()
        running = true; lastFrameArrivalMs = SystemClock.elapsedRealtime()
    }
    /** Parar: sem FFNet, sem YOLO, sem reinício automático até novo comando de início. */
    fun suspendMonitoring() { running = false; analyzing = false; invalidateSession() }
    private fun ensureMachine() {
        if (machine.state != AnalysisState.STOPPED) return
        machine = AutomaticDetectionManager(config.roadConfirmationMs.toLong(), config.roadHoldMs.toLong(), config.roadCooldownMs.toLong())
        machine.start()
    }
    private fun segment(upright: Bitmap): ByteArray = segmenter?.invoke(upright) ?: run {
        val engine = segmentor ?: RoadSegmentationManager(context).also { segmentor = it }
        engine.segment(upright)
    }

    @Synchronized fun process(bitmap: Bitmap, rotation: Int, capturedAtMs: Long, yolo: () -> Unit) {
        val started = SystemClock.elapsedRealtime()
        val token = resultToken
        lastFrameArrivalMs = started
        val cpuStarted = android.os.Debug.threadCpuTimeNanos()
        if (epoch == 0L) epoch = started
        captures++
        var segMs = 0L
        var yoloMs = 0L
        if (started - capturedAtMs !in 0..1500) { dropped++; reportStaleResult(); return }
        if (!acceptsResult(token,capturedAtMs)) { dropped++; return }
        // FFNet primeiro (só despacha a cópia do frame mais novo para a thread própria); depois o YOLO.
        // FFNet somente em WAITING_FOR_ROAD; suspensa por completo durante ANALYZING (manual ou automática).
        // Roda numa thread própria com CÓPIA do frame: o YOLO não espera os ~300 ms da FFNet.
        // No máximo uma inferência FFNet em andamento (sem fila): frames enquanto ocupada são ignorados.
        if (!analyzing && !manual() && running && config.automaticRoadDetection && !failure && !ffnetBusy.get()) {
            ensureMachine()
            if (started - lastSegmentation >= 1000L / config.roadSegmentationFps) {
                lastSegmentation = started
                val upright = if (rotation == 0) bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                if (upright != null) {
                    ffnetBusy.set(true)
                    try {
                        ffnetExecutor.execute {
                            try { runSegmentation(upright, capturedAtMs, token) }
                            finally { upright.recycle(); ffnetBusy.set(false) }
                        }
                    } catch (rejected: java.util.concurrent.RejectedExecutionException) {
                        upright.recycle(); ffnetBusy.set(false)
                    }
                }
            }
        }
        // YOLO: alertas de perigo disponíveis durante a análise e também na espera por rua.
        if (HazardMonitoringPolicy.enabled(manual() || analyzing,running) && started - lastYolo >= 1000 / config.roadYoloFps) {
            lastYolo = started
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
    private fun runSegmentation(upright: Bitmap, capturedAtMs: Long, token: Long) {
        try {
            val segStarted = SystemClock.elapsedRealtimeNanos()
            val labels = synchronized(segmentLock) { if (!running || token != resultToken) return; segment(upright) }
            val segMs = (SystemClock.elapsedRealtimeNanos() - segStarted) / 1_000_000L
            com.travessia.segura.benchmark.Perf.stats.record("ffnet_ms", (SystemClock.elapsedRealtimeNanos() - segStarted) / 1e6f)
            synchronized(this) {
                ffnetInferences++
                segments++
                val finished = SystemClock.elapsedRealtime()
                // Análise iniciada durante a inferência (ex.: início manual): descartar a máscara.
                if (analyzing || manual() || !running || token != resultToken) return
                // A FFNet só responde "há rua?" (gatilho), nunca decisão de segurança: aceita máscara de até 4 s.
                if (finished - capturedAtMs > FFNET_TRIGGER_MAX_AGE_MS) {
                    dropped++
                    lastRoadResult = null
                    publish(RoadDebugFrame(null,null,AnalysisState.INCONCLUSIVE,segMs,
                        finished-capturedAtMs,dropped,"ffnet_stale"),token)
                    return
                }
                val result = RoadSceneAnalyzer.analyze(labels, 256, 128, capturedAtMs)
                lastRoadResult = result
                val before = machine.state
                val state = machine.update(result, finished)
                val seconds = ((finished - epoch) / 1000f).coerceAtLeast(1f)
                publish(RoadDebugFrame(if (config.roadDebugOverlay) labels else null, result, state,
                    segMs, finished - capturedAtMs, dropped,
                    if (result.possibleRoadBoundary) "boundary" else if (result.roadDetected) "road" else "inconclusive",
                    upright.width, upright.height, segments / seconds, yolos / seconds, captures / seconds),token)
                if (state == AnalysisState.ACTIVE_ANALYSIS && before != AnalysisState.ACTIVE_ANALYSIS) {
                    // Uma única análise por confirmação; FFNet suspensa a partir daqui.
                    analyzing = true
                    successfulYoloFrames = 0
                    Log.i("RoadInference", "Road confirmed by FFNet; YOLO analysis takes over")
                    onRoadConfirmed(token)
                }
            }
        } catch (error: Exception) {
            synchronized(this) {
                if (token != resultToken) return
                failure = true; machine.error()
                lastRoadResult = null
                Log.e("RoadInference", "FFNet indisponível", error)
                // Preservar YOLO e o início manual; a falha nunca é silenciosa.
                publish(RoadDebugFrame(null, null, AnalysisState.ERROR, 0, 0, dropped, "model_error"),token)
            }
        }
    }
    companion object { const val FFNET_TRIGGER_MAX_AGE_MS = 4000L }

    @Synchronized fun observeYolo(hasEvidence: Boolean) { machine.observeYolo(hasEvidence, SystemClock.elapsedRealtime()) }
    /** Início da análise (manual ou após rua confirmada). Não depende da FFNet. */
    fun beginAnalysis(): Boolean {
        val wasAnalyzing = analyzing
        analyzing = true
        // O watchdog de vídeo passa a contar a partir do início da análise (também no início manual).
        if (!wasAnalyzing) lastFrameArrivalMs = SystemClock.elapsedRealtime()
        synchronized(this) { ensureMachine(); machine.beginAnalysis(); successfulYoloFrames = 0 }
        if (!wasAnalyzing) publish(RoadDebugFrame(null,null,AnalysisState.ACTIVE_ANALYSIS,0,0,dropped,"analysis_started"))
        return !wasAnalyzing
    }
    /** Encerramento normal: volta a procurar rua (após o intervalo de rearme) se o monitoramento estiver ativo. */
    fun finishAnalysis() {
        synchronized(this) {
            machine.finishAnalysis(SystemClock.elapsedRealtime())
            lastRoadResult = null; lastSegmentation = 0L
            analyzing = false
        }
        publish(RoadDebugFrame(null,null,if (running) AnalysisState.MONITORING else AnalysisState.STOPPED,0,0,dropped,"analysis_finished"))
    }
    @Synchronized fun completedYoloFrame(capturedAtMs: Long) {
        successfulYoloFrames++
        lastSuccessfulYoloAt = capturedAtMs
        if (successfulYoloFrames == 1L) Log.i("RoadInference","First fresh YOLO result after consent/manual activation")
    }
    fun reportVideoError() {
        invalidateSession()
        publish(RoadDebugFrame(null,null,AnalysisState.ERROR,0,0,dropped,"video_error"))
    }
    fun reportStaleResult() {
        onStale()
        lastRoadResult = null
        publish(RoadDebugFrame(null,null,AnalysisState.INCONCLUSIVE,0,0,dropped,"stale_frame"))
    }
    private fun publish(frame: RoadDebugFrame, token: Long = resultToken) = onDebug(frame.copy(sessionToken=token))
    @Synchronized fun release() {
        validity.invalidate(SystemClock.elapsedRealtime())
        // Não fechar o intérprete durante uma inferência FFNet em outra thread.
        synchronized(segmentLock) { segmentor?.close(); segmentor = null }
        machine.stop(); analyzing = false; failure = false; lastSegmentation = 0; ffnetInferences = 0
        successfulYoloFrames = 0
        lastSuccessfulYoloAt = 0; lastYolo = 0
        lastRoadResult = null
        telemetry.close(); epoch = 0; captures = 0; segments = 0; yolos = 0; dropped = 0
    }
    override fun close() { running = false; release(); (ffnetExecutor as? java.util.concurrent.ExecutorService)?.shutdown() }
}
