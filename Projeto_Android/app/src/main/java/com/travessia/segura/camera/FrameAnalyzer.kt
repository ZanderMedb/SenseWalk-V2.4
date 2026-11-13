package com.travessia.segura.camera

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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

class FrameAnalyzer(
    private val config: AppConfig,
    private val detector: YoloDetector,
    private val vehicleTracker: AdvancedVehicleTracker,
    private val tracker: Rastreador,
    private val stateMachine: StateMachine,
    private val benchmarkLogger: BenchmarkLogger,
    private val onStatusUpdate: (String, String, Int, String, Long, Long?) -> Unit,
    private val onOverlayUpdate: (List<OverlayView.BoxInfo>, Int, Int, Int, Long, Long?) -> Unit,
    private val coordinator: com.travessia.segura.road.InferenceCoordinator? = null,
    private val crossing: CrossingVerification? = null,
    /** Somente câmera nativa: orientação do celular = orientação desta câmera. */
    private val orientation: com.travessia.segura.orientation.OrientationCoverageTracker? = null,
    /** Memória de curto prazo (RAM) da sessão: histórico entre direções e estados estabilizados. */
    private val sessionMemory: com.travessia.segura.tracking.CrossingSessionMemory? = null
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FrameAnalyzer"
    }

    private var frameCount = 0L
    private val opticalFlow = SparseOpticalFlowEstimator()
    private val sceneEvidence = SceneEvidenceTracker()
    private var previousVehicles = emptyList<FloatArray>()
    private var pipelineToken: Long? = null

    /** Bitmap reutilizado no caminho RGBA (sem YUV -> JPEG -> Bitmap a cada frame). */
    private var rgbaBitmap: Bitmap? = null

    override fun analyze(imageProxy: ImageProxy) {
        val perf = com.travessia.segura.benchmark.Perf.stats
        perf.count("camera_frames")
        if ((stateMachine.estado == AppConfig.EST_INATIVO && coordinator?.running != true) || !detector.isReady) {
            imageProxy.close()
            return
        }
        // Nenhum modelo devido neste instante: descartar ANTES de converter (o frame mais novo virá a seguir).
        if (coordinator != null && !coordinator.wantsFrame()) {
            perf.count("skipped_not_due")
            imageProxy.close()
            return
        }

        var bitmap: Bitmap? = null
        try {
            // Com setOutputImageRotationEnabled(true) o CameraX já entrega a imagem em pé e informa 0 aqui.
            val rotation = imageProxy.imageInfo.rotationDegrees
            val capturedAt = imageProxy.imageInfo.timestamp / 1_000_000
            if (SystemClock.elapsedRealtime() - capturedAt !in 0..1500) { coordinator?.reportStaleResult(); return }
            val convertStarted = SystemClock.elapsedRealtimeNanos()
            bitmap = toBitmap(imageProxy) ?: error("CameraX image conversion failed")
            perf.record("convert_ms", (SystemClock.elapsedRealtimeNanos() - convertStarted) / 1e6f)
            val frame = bitmap
            if (coordinator != null) coordinator.process(frame, rotation, capturedAt) {
                processarFrame(frame, rotation, capturedAt)
            } else processarFrame(frame, rotation, capturedAt)
            perf.record("pipeline_ms", (SystemClock.elapsedRealtimeNanos() - convertStarted) / 1e6f)
            perf.record("frame_age_ms", (SystemClock.elapsedRealtime() - capturedAt).toFloat())
        } catch (e: Exception) {
            crossing?.invalidate()
            coordinator?.reportVideoError()
            Log.e(TAG, "Erro ao processar frame: ${e.message}", e)
        } finally {
            if (bitmap != null && bitmap !== rgbaBitmap) bitmap.recycle()
            imageProxy.close()
        }
    }

    /** RGBA_8888 (rápido, buffer reaproveitado) ou YUV legado (JPEG), conforme o formato configurado. */
    private fun toBitmap(imageProxy: ImageProxy): Bitmap? {
        if (imageProxy.format != android.graphics.PixelFormat.RGBA_8888) return ImageUtils.imageProxyToBitmap(imageProxy)
        val plane = imageProxy.planes[0]
        val w = imageProxy.width; val h = imageProxy.height
        if (plane.pixelStride != 4 || plane.rowStride != w * 4) return imageProxy.toBitmap() // linhas com padding
        val target = rgbaBitmap?.takeIf { !it.isRecycled && it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { rgbaBitmap?.recycle(); rgbaBitmap = it }
        val buffer = plane.buffer
        buffer.rewind()
        target.copyPixelsFromBuffer(buffer)
        return target
    }

    private fun processarFrame(bitmap: Bitmap, rotation: Int, capturedAtMs: Long) {
        val token = coordinator?.resultToken
        if (token != pipelineToken) { resetar(); pipelineToken = token }
        val frameStarted = SystemClock.elapsedRealtimeNanos()
        frameCount++
        val timestampMs = System.currentTimeMillis()
        val agora = capturedAtMs / 1000.0

        val wf = if (rotation == 90 || rotation == 270) bitmap.height else bitmap.width
        val hf = if (rotation == 90 || rotation == 270) bitmap.width else bitmap.height

        val flowMotion = if (config.opticalFlowEnabled) {
            opticalFlow.estimate(bitmap, rotation, previousVehicles)
        } else {
            SparseOpticalFlowEstimator.MotionEstimate()
        }
        val flowOk = config.opticalFlowEnabled && flowMotion.confidence >= config.opticalFlowMinConfidence
        // Câmera NATIVA: se o fluxo óptico falhou (noite/pouca textura) mas o sensor do celular mostra a câmera
        // parada, o movimento da câmera é zero com confiança. Assim o histórico dos veículos não é apagado a
        // cada quadro duvidoso. Webcam USB não tem esse sensor e mantém o comportamento conservador.
        val imuStill = !flowOk && orientation?.isStillAt(capturedAtMs) == true
        val motion = if (imuStill) SparseOpticalFlowEstimator.MotionEstimate(dx = 0f, dy = 0f, confidence = 1f,
            validPoints = flowMotion.validPoints, processingMs = flowMotion.processingMs, translationSupported = true,
            similaritySupported = true, model = "IMU_STILL") else flowMotion
        if (imuStill) com.travessia.segura.benchmark.Perf.stats.count("imu_still_frames")
        val useMotion = flowOk || imuStill
        val appliedDx = if (useMotion) motion.dx else 0f
        val appliedDy = if (useMotion) motion.dy else 0f

        // 1) YOLO. O modelo atual continua intocado para isolarmos o ganho do pipeline.
        val rawDetections = detector.detectar(bitmap, config.confMinima, rotation)
        if (SystemClock.elapsedRealtime() - capturedAtMs !in 0..1500) { crossing?.degrade(); coordinator?.reportStaleResult(); return }
        if (token != null && coordinator?.acceptsResult(token,capturedAtMs) != true) return
        val rawVeiculos = rawDetections.filter { config.categorizarClasse(it.className) == "veiculo" }
        previousVehicles = rawVeiculos.map { floatArrayOf(it.x1, it.y1, it.x2, it.y2) }
        val contexto = rawDetections.filter { config.categorizarClasse(it.className) != "veiculo" }
        val scene = sceneEvidence.update(contexto, capturedAtMs, wf)
        coordinator?.observeYolo(rawVeiculos.isNotEmpty() || contexto.isNotEmpty())

        // 2) Tracker V2.4 em duas etapas, já compensado pela translação da câmera.
        com.travessia.segura.benchmark.Perf.stats.record("flow_ms", motion.processingMs.toFloat())
        val trackStarted = SystemClock.elapsedRealtimeNanos()
        vehicleTracker.configure(config.trackerCenterDistancePct, config.trackerGhostFrames)
        val trackedVehicles = vehicleTracker.update(rawVeiculos, wf, hf, appliedDx, appliedDy, capturedAtMs)
        com.travessia.segura.benchmark.Perf.stats.record("tracker_ms", (SystemClock.elapsedRealtimeNanos() - trackStarted) / 1e6f)
        if (vehicleTracker.lastStats.recoveredAfterMiss > 0)
            com.travessia.segura.benchmark.Perf.stats.count("track_recoveries", vehicleTracker.lastStats.recoveredAfterMiss.toLong())
        com.travessia.segura.benchmark.Perf.stats.count("track_new", vehicleTracker.lastStats.newTracks.toLong())
        tracker.novoFrameCamera(motion.dx, motion.dy, motion.confidence, config.opticalFlowMinConfidence,
            motion.scale, motion.rotationDegrees, motion.parallaxSuspected, capturedAtMs / 1000.0)

        if (frameCount % 30L == 0L) {
            val stats = vehicleTracker.lastStats
            Log.d(
                TAG,
                "V2.4: yolo=${rawVeiculos.size}, tracked=${trackedVehicles.size}, ghosts=${stats.ghostCount}, " +
                    "lowRecoveries=${stats.lowConfidenceRecoveries}, cam=(${"%.1f".format(appliedDx)},${"%.1f".format(appliedDy)}) " +
                    "flowConf=${"%.2f".format(motion.confidence)}"
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

        // Ghosts estabilizam tracking/UI, mas não criam novos pontos de cinemática.
        for (det in trackedVehicles) {
            if (det.isGhost) continue
            // ROI é só apresentação; não esconder tráfego lateral da análise de perigo.
            tracker.atualizar(det.trackId, det.cx, det.cy, agora, det.bboxW.toInt())
        }
        tracker.limpar(agora)

        for (det in trackedVehicles) {
            if (det.isGhost) continue // predição não é uma nova observação de movimento
            val cx = det.cx
            val cy = det.cy

            val tid = det.trackId
            if (tid < 0) {
                nSemId++
                continue
            }

            // Estado bruto do Rastreador -> estado estabilizado na memória da sessão (3 observações, histerese;
            // conflito/movimento relevante imediatos; câmera não compensada invalida "aparentemente parado").
            val assessment = tracker.avaliar(tid, wf, hf)
            val vel = assessment.speedPxS
            val memStarted = SystemClock.elapsedRealtimeNanos()
            val classif = sessionMemory?.observe(tid, det.className, floatArrayOf(det.x1, det.y1, det.x2, det.y2),
                assessment.classification, assessment.confidence, capturedAtMs,
                crossing?.coverageDirection?.let { com.travessia.segura.tracking.CrossingSessionMemory.Direction.valueOf(it.name) },
                wf, hf, cameraMotionInvalid = !useMotion || tracker.cameraAndando())?.state ?: assessment.classification
            com.travessia.segura.benchmark.Perf.stats.record("memory_ms", (SystemClock.elapsedRealtimeNanos() - memStarted) / 1e6f)
            sessionMemory?.recordFor(tid)?.takeIf { it.lastTransitionMs == capturedAtMs }?.let { rec ->
                Log.i("SenseWalkVeh", "id=$tid mem=${rec.memoryId} state=${rec.state} raw=${assessment.classification} " +
                    "reason=${assessment.reason} w=${det.bboxW.toInt()}/$wf y2=${det.y2.toInt()}/$hf rel=${rec.relevance} dir=${rec.direction}")
            }
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
                "APROXIMANDO", "POTENCIAL_CONFLITO", "POSSIVEL_APROXIMACAO" -> OverlayView.COLOR_APROXIMANDO
                "EM_MOVIMENTO" -> OverlayView.COLOR_EM_MOVIMENTO
                "MOVIMENTO_INCERTO", "POSSIVEL_AFASTAMENTO" -> OverlayView.COLOR_OUTROS
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
        if (overlayBoxes != null) onOverlayUpdate(overlayBoxes, wf, hf, 0, capturedAtMs, token)

        // Cobertura da direção pedida neste frame: sensor do celular + estabilidade + amostra próxima do frame.
        val coverageDirection = crossing?.coverageDirection
        val coverage = if (orientation != null && coverageDirection != null)
            orientation.evaluate(com.travessia.segura.orientation.OrientationCoverageTracker.Direction.valueOf(coverageDirection.name),
                capturedAtMs) else null
        if (coverage != null && frameCount % 15 == 0L)
            Log.i("SenseWalkOrientation", "dir=$coverageDirection status=${coverage.status} yaw=${coverage.relativeYawDeg}")
        val risk = CrossingRiskAnalyzer.analyze(veiculos.values, wf, hf, scene.crosswalkBox,
            SystemClock.elapsedRealtime() - capturedAtMs,
            coverageVerified = coverage?.covered == true, cameraReliable = useMotion,
            // Rastreio perdido só conta quando relevante (veículo grande ou perto). Carro minúsculo e distante
            // que "pisca" na detecção não bloqueia a direção inteira; continua no histórico/overlay.
            unobservedVehicles = trackedVehicles.count { it.isGhost && (it.bboxW >= wf * .05f || it.y2 >= hf * .55f) })
        crossing?.observe(risk, scene, coordinator?.lastRoadResult, capturedAtMs, motion, wf,
            wrongDirection = coverage?.status == com.travessia.segura.orientation.OrientationCoverageTracker.Status.WRONG_DIRECTION,
            coverageAvailable = orientation != null && orientation.available && orientation.calibrated)

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
                    source = "phone",
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
        sessionMemory?.clear() // IDs do tracker recomeçam: histórico antigo não pode ser reassociado
        vehicleTracker.reset()
        tracker.resetar()
        opticalFlow.reset()
        sceneEvidence.reset()
        previousVehicles = emptyList()
        frameCount = 0L
    }
}
