package com.travessia.segura.camera

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.travessia.segura.benchmark.BenchmarkLogger
import com.travessia.segura.config.AppConfig
import com.travessia.segura.detection.AdvancedVehicleTracker
import com.travessia.segura.detection.VeiculoInfo
import com.travessia.segura.detection.YoloDetector
import com.travessia.segura.logic.StateMachine
import com.travessia.segura.tracking.Rastreador
import com.travessia.segura.ui.OverlayView
import com.travessia.segura.vision.SparseOpticalFlowEstimator
import com.travessia.segura.road.SceneEvidenceTracker
import com.travessia.segura.road.SignalApplicability
import com.travessia.segura.road.CrosswalkStatus
import com.travessia.segura.road.CrossingRiskAnalyzer
import com.travessia.segura.road.CrossingVerification

/** Processes external camera frames through the same vision pipeline as CameraX. */
class ExternalFrameProcessor(
    private val config: AppConfig,
    private val detector: YoloDetector,
    private val vehicleTracker: AdvancedVehicleTracker,
    private val tracker: Rastreador,
    private val stateMachine: StateMachine,
    private val benchmarkLogger: BenchmarkLogger,
    private val onStatusUpdate: (String, String, Int, String, Long, Long?) -> Unit,
    private val onOverlayUpdate: (List<OverlayView.BoxInfo>, Int, Int, Int, Long, Long?) -> Unit,
    private val coordinator: com.travessia.segura.road.InferenceCoordinator? = null,
    private val crossing: CrossingVerification? = null
) {
    companion object {
        private const val TAG = "ExternalFrameProcessor"
    }

    private var frameCount = 0L
    private val opticalFlow = SparseOpticalFlowEstimator()
    private val sceneEvidence = SceneEvidenceTracker()
    private var previousVehicles = emptyList<FloatArray>()
    private var pipelineToken: Long? = null

    fun processarFrame(bitmap: Bitmap, overlayRotation: Int = 0, capturedAtMs: Long = SystemClock.elapsedRealtime()) {
        if ((stateMachine.estado == AppConfig.EST_INATIVO && coordinator?.running != true) || !detector.isReady) return
        try {
            if (coordinator != null) coordinator.process(bitmap, 0, capturedAtMs) { processarInterno(bitmap, overlayRotation, capturedAtMs) }
            else processarInterno(bitmap, overlayRotation, capturedAtMs)
        } catch (e: Exception) {
            crossing?.invalidate()
            coordinator?.reportVideoError()
            Log.e(TAG, "Erro ao processar frame externo: ${e.message}", e)
        }
    }

    private fun processarInterno(bitmap: Bitmap, overlayRotation: Int, capturedAtMs: Long) {
        val token = coordinator?.resultToken
        if (token != pipelineToken) { resetar(); pipelineToken = token }
        if (SystemClock.elapsedRealtime() - capturedAtMs !in 0..1500) { crossing?.invalidate(); coordinator?.reportStaleResult(); return }
        val frameStarted = SystemClock.elapsedRealtimeNanos()
        frameCount++
        val timestampMs = System.currentTimeMillis()
        val agora = capturedAtMs / 1000.0
        val wf = bitmap.width
        val hf = bitmap.height

        val motion = if (config.opticalFlowEnabled) {
            opticalFlow.estimate(bitmap, 0, previousVehicles)
        } else {
            SparseOpticalFlowEstimator.MotionEstimate()
        }
        val useMotion = config.opticalFlowEnabled && motion.confidence >= config.opticalFlowMinConfidence
        val appliedDx = if (useMotion) motion.dx else 0f
        val appliedDy = if (useMotion) motion.dy else 0f

        val rawDetections = detector.detectar(bitmap, config.confMinima, 0)
        if (SystemClock.elapsedRealtime() - capturedAtMs !in 0..1500) { crossing?.invalidate(); coordinator?.reportStaleResult(); return }
        if (token != null && coordinator?.acceptsResult(token,capturedAtMs) != true) return
        val rawVeiculos = rawDetections.filter { config.categorizarClasse(it.className) == "veiculo" }
        previousVehicles = rawVeiculos.map { floatArrayOf(it.x1, it.y1, it.x2, it.y2) }
        val contexto = rawDetections.filter { config.categorizarClasse(it.className) != "veiculo" }
        val scene = sceneEvidence.update(contexto, capturedAtMs, wf)
        coordinator?.observeYolo(rawVeiculos.isNotEmpty() || contexto.isNotEmpty())

        vehicleTracker.configure(config.trackerCenterDistancePct, config.trackerGhostFrames)
        val trackedVehicles = vehicleTracker.update(rawVeiculos, wf, hf, appliedDx, appliedDy, capturedAtMs)
        tracker.novoFrameCamera(motion.dx, motion.dy, motion.confidence, config.opticalFlowMinConfidence)

        if (frameCount % 30L == 0L) {
            val stats = vehicleTracker.lastStats
            Log.d(
                TAG,
                "V2.4 externo: yolo=${rawVeiculos.size}, tracked=${trackedVehicles.size}, ghosts=${stats.ghostCount}, " +
                    "lowRecoveries=${stats.lowConfidenceRecoveries}, cam=(${"%.1f".format(appliedDx)},${"%.1f".format(appliedDy)})"
            )
        }

        val veiculos = mutableMapOf<Int, VeiculoInfo>()
        val faixaDet = scene.crosswalk == CrosswalkStatus.CONFIRMED
        val faixaCentroX = scene.crosswalkBox?.cx ?: -1f
        val semCorFrame = when (scene.signal) {
            SignalApplicability.PEDESTRIAN_GREEN -> if (scene.signalAssociationVerified) "VERDE" else "NENHUM"
            SignalApplicability.PEDESTRIAN_RED -> "VERMELHO"
            else -> "NENHUM"
        }
        var nSemId = trackedVehicles.count { it.isGhost }
        val overlayBoxes = if (config.mostrarOverlay || config.roadDebugOverlay) mutableListOf<OverlayView.BoxInfo>() else null

        for (det in contexto) {
            val cx = det.cx
            val cy = det.cy
            if (!config.dentroRoi(cx, cy, wf, hf)) continue

            when (config.categorizarClasse(det.className)) {
                "faixa" -> {
                    overlayBoxes?.add(
                        OverlayView.BoxInfo(
                            x1 = det.x1, y1 = det.y1, x2 = det.x2, y2 = det.y2,
                            label = config.tr("object_crosswalk"),
                            confidence = det.confidence,
                            color = OverlayView.COLOR_FAIXA
                        )
                    )
                }

                "semaforo" -> {
                    val cor = config.inferirCorSemaforo(det.className)
                    val semColor = when (cor) {
                        "VERMELHO" -> OverlayView.COLOR_SEM_VERMELHO
                        "VERDE" -> OverlayView.COLOR_SEM_VERDE
                        "AMARELO" -> OverlayView.COLOR_SEM_AMARELO
                        else -> OverlayView.COLOR_SEM_OUTRO
                    }
                    overlayBoxes?.add(
                        OverlayView.BoxInfo(
                            x1 = det.x1, y1 = det.y1, x2 = det.x2, y2 = det.y2,
                            label = config.nomeCorSemaforo(cor).replaceFirstChar { it.uppercase() },
                            confidence = det.confidence,
                            color = semColor
                        )
                    )
                }
            }
        }

        for (det in trackedVehicles) {
            if (det.isGhost) continue
            // ROI é só apresentação; não esconder tráfego lateral da análise de perigo.
            tracker.atualizar(det.trackId, det.cx, det.cy, agora, det.bboxW.toInt())
        }
        tracker.limpar(agora)

        for (det in trackedVehicles) {
            if (det.isGhost) continue
            val cx = det.cx
            val cy = det.cy

            val tid = det.trackId
            if (tid < 0) {
                nSemId++
                continue
            }

            val (classif, vel) = tracker.classificar(tid, wf, hf)
            veiculos[tid] = VeiculoInfo(
                nome = det.className,
                conf = det.confidence,
                cx = cx,
                cy = cy,
                classif = classif,
                vel = vel,
                bbox = floatArrayOf(det.x1, det.y1, det.x2, det.y2),
                bboxW = det.bboxW,
                tid = tid,
                trajectory = tracker.trajectory(tid,wf,hf)
            )

            val boxColor = when (classif) {
                "APROXIMANDO", "POTENCIAL_CONFLITO" -> OverlayView.COLOR_APROXIMANDO
                "EM_MOVIMENTO" -> OverlayView.COLOR_EM_MOVIMENTO
                "MOVIMENTO_INCERTO" -> OverlayView.COLOR_OUTROS
                else -> OverlayView.COLOR_PARADO
            }
            overlayBoxes?.add(
                OverlayView.BoxInfo(
                    x1 = det.x1, y1 = det.y1, x2 = det.x2, y2 = det.y2,
                    label = "${config.nomeVeiculo(det.className)} ${config.sufixoMovimento(classif)}",
                    confidence = det.confidence,
                    color = boxColor,
                    isGhost = det.isGhost
                )
            )
        }

        if (token != null && coordinator?.acceptsResult(token,capturedAtMs) != true) return
        if (overlayBoxes != null) onOverlayUpdate(overlayBoxes, wf, hf, overlayRotation, capturedAtMs, token)

        val risk = CrossingRiskAnalyzer.analyze(veiculos.values, wf, hf, scene.crosswalkBox,
            SystemClock.elapsedRealtime() - capturedAtMs,
            coverageVerified = false, cameraReliable = useMotion,
            unobservedVehicles = vehicleTracker.lastStats.unobservedTracks)
        crossing?.observe(risk, scene, coordinator?.lastRoadResult, capturedAtMs, motion)

        stateMachine.processar(
            veiculosReais = veiculos,
            nSemId = nSemId,
            semCorFrame = semCorFrame,
            faixaDet = faixaDet,
            faixaCx = faixaCentroX,
            wf = wf,
            hf = hf,
            risk = risk,
            monitorHazards = coordinator?.running == true,
            guidedVerification = crossing?.stage?.let { it != CrossingVerification.Stage.IDLE } == true
        )

        coordinator?.completedYoloFrame(capturedAtMs)
        onStatusUpdate(
            stateMachine.estado,
            stateMachine.situacaoAtual,
            veiculos.size,
            stateMachine.semCorAtual,
            capturedAtMs, token
        )

        if (config.benchmarkEnabled) {
            benchmarkLogger.logFrame(
                BenchmarkLogger.FrameMetrics(
                    source = "external",
                    frameIndex = frameCount,
                    timestampMs = timestampMs,
                    totalMs = (SystemClock.elapsedRealtimeNanos() - frameStarted) / 1_000_000L,
                    inferenceMs = detector.lastInferenceTimeMs,
                    opticalFlowMs = motion.processingMs,
                    rawVehicleCount = rawVeiculos.size,
                    contextCount = contexto.size,
                    trackedCount = trackedVehicles.size,
                    ghostCount = trackedVehicles.count { it.isGhost },
                    cameraDx = motion.dx,
                    cameraDy = motion.dy,
                    motionConfidence = motion.confidence,
                    trackerStats = vehicleTracker.lastStats,
                    state = stateMachine.estado,
                    situation = stateMachine.situacaoAtual,
                    frameAgeMs = SystemClock.elapsedRealtime() - capturedAtMs
                )
            )
        }
    }

    fun resetar() {
        vehicleTracker.reset()
        tracker.resetar()
        opticalFlow.reset()
        sceneEvidence.reset()
        previousVehicles = emptyList()
        frameCount = 0L
    }
}
