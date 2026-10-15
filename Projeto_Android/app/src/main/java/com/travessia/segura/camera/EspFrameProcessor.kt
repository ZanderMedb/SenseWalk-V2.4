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

/** Processa frames da câmera externa usando o mesmo pipeline V2.4 da câmera local. */
class EspFrameProcessor(
    private val config: AppConfig,
    private val detector: YoloDetector,
    private val vehicleTracker: AdvancedVehicleTracker,
    private val tracker: Rastreador,
    private val stateMachine: StateMachine,
    private val benchmarkLogger: BenchmarkLogger,
    private val onStatusUpdate: (String, String, Int, String) -> Unit,
    private val onOverlayUpdate: (List<OverlayView.BoxInfo>, Int, Int, Int) -> Unit
) {
    companion object {
        private const val TAG = "EspFrameProcessor"
    }

    private var frameCount = 0L
    private val opticalFlow = SparseOpticalFlowEstimator()

    fun processarFrame(bitmap: Bitmap) {
        if (stateMachine.estado == AppConfig.EST_INATIVO || !detector.isReady) return
        try {
            processarInterno(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar frame externo: ${e.message}", e)
        }
    }

    private fun processarInterno(bitmap: Bitmap) {
        val frameStarted = SystemClock.elapsedRealtimeNanos()
        frameCount++
        val timestampMs = System.currentTimeMillis()
        val agora = timestampMs / 1000.0
        val wf = bitmap.width
        val hf = bitmap.height

        val motion = if (config.opticalFlowEnabled) {
            opticalFlow.estimate(bitmap, 0)
        } else {
            SparseOpticalFlowEstimator.MotionEstimate()
        }
        val useMotion = config.opticalFlowEnabled && motion.confidence >= config.opticalFlowMinConfidence
        val appliedDx = if (useMotion) motion.dx else 0f
        val appliedDy = if (useMotion) motion.dy else 0f

        val rawDetections = detector.detectar(bitmap, config.confMinima, 0)
        val rawVeiculos = rawDetections.filter { config.categorizarClasse(it.className) == "veiculo" }
        val contexto = rawDetections.filter { config.categorizarClasse(it.className) != "veiculo" }

        vehicleTracker.configure(config.trackerCenterDistancePct, config.trackerGhostFrames)
        val trackedVehicles = vehicleTracker.update(rawVeiculos, wf, hf, appliedDx, appliedDy)
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
        var faixaDet = false
        var faixaCentroX = -1f
        var semCorFrame = "NENHUM"
        var nSemId = 0
        val overlayBoxes = mutableListOf<OverlayView.BoxInfo>()

        for (det in contexto) {
            val cx = det.cx
            val cy = det.cy
            if (!config.dentroRoi(cx, cy, wf, hf)) continue

            when (config.categorizarClasse(det.className)) {
                "faixa" -> {
                    faixaDet = true
                    faixaCentroX = cx
                    overlayBoxes.add(
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
                    if (cor != "DESCONHECIDO") semCorFrame = cor
                    val semColor = when (cor) {
                        "VERMELHO" -> OverlayView.COLOR_SEM_VERMELHO
                        "VERDE" -> OverlayView.COLOR_SEM_VERDE
                        "AMARELO" -> OverlayView.COLOR_SEM_AMARELO
                        else -> OverlayView.COLOR_SEM_OUTRO
                    }
                    overlayBoxes.add(
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
            if (!config.dentroRoi(det.cx, det.cy, wf, hf)) continue
            tracker.atualizar(det.trackId, det.cx, det.cy, agora, det.bboxW.toInt())
        }
        tracker.limpar(agora)

        for (det in trackedVehicles) {
            val cx = det.cx
            val cy = det.cy
            if (!config.dentroRoi(cx, cy, wf, hf)) continue

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
                tid = tid
            )

            val boxColor = when (classif) {
                "APROXIMANDO" -> OverlayView.COLOR_APROXIMANDO
                "EM_MOVIMENTO" -> OverlayView.COLOR_EM_MOVIMENTO
                else -> OverlayView.COLOR_PARADO
            }
            overlayBoxes.add(
                OverlayView.BoxInfo(
                    x1 = det.x1, y1 = det.y1, x2 = det.x2, y2 = det.y2,
                    label = "${config.nomeVeiculo(det.className)} ${config.sufixoMovimento(classif)}",
                    confidence = det.confidence,
                    color = boxColor,
                    isGhost = det.isGhost
                )
            )
        }

        onOverlayUpdate(overlayBoxes, wf, hf, config.espPreviewRotation)

        stateMachine.processar(
            veiculosReais = veiculos,
            nSemId = nSemId,
            semCorFrame = semCorFrame,
            faixaDet = faixaDet,
            faixaCx = faixaCentroX,
            wf = wf,
            hf = hf
        )

        onStatusUpdate(
            stateMachine.estado,
            stateMachine.situacaoAtual,
            veiculos.size,
            stateMachine.semCorAtual
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
                    situation = stateMachine.situacaoAtual
                )
            )
        }
    }

    fun resetar() {
        vehicleTracker.reset()
        tracker.resetar()
        opticalFlow.reset()
        frameCount = 0L
    }
}
