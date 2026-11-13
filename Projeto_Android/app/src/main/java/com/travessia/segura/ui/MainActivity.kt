package com.travessia.segura.ui

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.travessia.segura.R
import com.travessia.segura.camera.ExternalFrameProcessor
import com.travessia.segura.camera.FrameAnalyzer
import com.travessia.segura.camera.VideoFrame
import com.travessia.segura.config.AppConfig
import com.travessia.segura.config.LanguageManager
import com.travessia.segura.config.AppConfig.Companion.EST_INATIVO
import com.travessia.segura.config.AppConfig.Companion.EST_RECONHECENDO
import com.travessia.segura.detection.AdvancedVehicleTracker
import com.travessia.segura.benchmark.BenchmarkLogger
import com.travessia.segura.detection.YoloDetector
import com.travessia.segura.logic.StateMachine
import com.travessia.segura.tracking.Rastreador
import com.travessia.segura.road.CrossingVerification
import com.travessia.segura.road.RoadText
import com.travessia.segura.voice.VoicePriority
import com.travessia.segura.voice.VoiceSystem
import com.travessia.segura.voice.VoiceCommandController
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.util.Size
import android.view.View
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val CAMERA_PERMISSION_CODE = 100
        private const val MICROPHONE_PERMISSION_CODE = 101
        private const val ROAD_MICROPHONE_PERMISSION_CODE = 103
        private const val USB_CAMERA_PERMISSION_CODE = 104
        private val FINISH_TOKEN = Any()
        /** Tempo para ouvir o resultado / dizer "analisar novamente" antes de voltar à busca de rua. */
        private const val RESULT_HOLD_MS = 10_000L

        // Modos de câmera
        const val MODE_LOCAL_CAMERA = 0
        const val MODE_USB_CAMERA = 2
    }

    // UI
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var tvEstado: TextView
    private lateinit var tvSituacao: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvDetectionCount: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvAlertStatus: TextView
    private lateinit var tvScreenTitle: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnCrossing: Button
    private lateinit var btnConfig: Button
    private lateinit var btnSource: Button
    private lateinit var btnVoice: Button
    private lateinit var statusBar: View
    private lateinit var bottomPanel: View

    private var externalImageView: ImageView? = null
    private var externalPreviewBitmap: Bitmap? = null

    // Engine
    private lateinit var config: AppConfig
    private lateinit var voz: VoiceSystem
    private lateinit var voiceCommands: VoiceCommandController
    private lateinit var detector: YoloDetector
    private lateinit var vehicleTracker: AdvancedVehicleTracker
    private lateinit var rastreador: Rastreador
    private lateinit var stateMachine: StateMachine
    private lateinit var benchmarkLogger: BenchmarkLogger
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var roadCoordinator: com.travessia.segura.road.InferenceCoordinator
    private lateinit var crossing: CrossingVerification
    /** Orientação do celular: evidência de cobertura só para a câmera NATIVA. */
    private val orientationTracker = com.travessia.segura.orientation.OrientationCoverageTracker()
    /** Memória de curto prazo (RAM) dos veículos da sessão; limpa em "Parar", troca de câmera e nova sessão. */
    private val sessionMemory = com.travessia.segura.tracking.CrossingSessionMemory()
    /** Webcam USB: giro estimado pela imagem (o sensor do celular não mede a webcam). */
    // Webcam a poucos quadros/s: alcance de 50% da largura por quadro para não perder viradas rápidas.
    private val visualYaw = com.travessia.segura.vision.VisualYawEstimator(maxShiftFraction = .5f)
    private lateinit var orientationSource: com.travessia.segura.orientation.OrientationSensorSource
    private data class PendingPreview(val bitmap: Bitmap, val generation: Long, val timestampMs: Long)
    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val previewDispatcher = com.travessia.segura.camera.LatestFrameDispatcher<PendingPreview>(
        post = { action -> previewHandler.post { action() } },
        discard = { it.bitmap.recycle() },
        consume = { frame ->
            if (foreground && !isDestroyed && cameraMode != MODE_LOCAL_CAMERA &&
                frame.generation == roadCoordinator.resultToken &&
                SystemClock.elapsedRealtime() - frame.timestampMs in 0..1500) {
                externalImageView?.setImageBitmap(frame.bitmap)
                externalPreviewBitmap?.recycle()
                externalPreviewBitmap = frame.bitmap
            } else frame.bitmap.recycle()
        }
    )
    private var roadMicRequested = false
    private var foreground = false
    private var lastRoadState: com.travessia.segura.road.AnalysisState? = null
    /** Parar explícito: permanece parado (sem FFNet/YOLO) até um novo comando de início. Não persistido. */
    private var userStopped = false
    /** Incrementado a cada início/fim de análise; descarta callbacks/timers de sessões antigas. */
    private var analysisSession = 0L
    private enum class EndReason { FINISHED, CANCELLED, STOPPED, NO_VIDEO }
    private val sessionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val listeningPolicy = com.travessia.segura.voice.ListeningWindowPolicy()
    private var autoListening = false
    /** Etapa vigente quando o microfone abriu: "Pronto" ouvido antes de uma troca de etapa é descartado. */
    private var listenStage: CrossingVerification.Stage? = null
    /** Fluidez da interface: quadros desenhados por segundo e quadros > 33 ms (independe do YOLO). */
    private var lastUiFrameNanos = 0L
    private val uiFrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!foreground) { lastUiFrameNanos = 0L; return }
            val perf = com.travessia.segura.benchmark.Perf.stats
            perf.count("ui_frames")
            if (lastUiFrameNanos != 0L && frameTimeNanos - lastUiFrameNanos > 33_000_000L) perf.count("ui_jank")
            lastUiFrameNanos = frameTimeNanos
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private val listenTicker = object : Runnable {
        override fun run() {
            if (!foreground) return
            ensureStageSpoken()
            maybeOpenListeningWindow()
            com.travessia.segura.benchmark.Perf.stats.maybeReport()
            sessionHandler.postDelayed(this, 700L)
        }
    }
    /** Um aviso por perda de imagem; rearmado somente quando frames voltam a chegar. */
    private var videoLossReported = false
    /** Após "sem imagem", manter o microfone disponível para "câmera do celular"/"iniciar análise". */
    private var listenAfterVideoLoss = false
    private var analysisStartedAtMs = 0L
    private val videoWatchdog = object : Runnable {
        override fun run() {
            if (!foreground || !::roadCoordinator.isInitialized) return
            val now = SystemClock.elapsedRealtime()
            val last = roadCoordinator.lastFrameArrivalMs
            // Antes do primeiro frame (USB pedindo permissão/abrindo) a tolerância é maior.
            // Busca pela rua (sem análise): webcam USB pode levar alguns segundos para abrir; 8 s de tolerância.
            val limit = if (!isActive) 8000L else if (last <= analysisStartedAtMs) 6000L else 3000L
            val lost = (isActive || roadCoordinator.running) && last > 0 && now - last > limit
            if (!lost) videoLossReported = false
            else if (!videoLossReported) {
                videoLossReported = true
                overlayView.clear()
                if (isActive) endAnalysis(EndReason.NO_VIDEO)
                else {
                    roadCoordinator.reportVideoError()
                    listenAfterVideoLoss = true
                    // Sem análise em andamento: só avisar que está aguardando imagem (nada foi "encerrado").
                    voz.forcarPrioritario("no_video", RoadText.get(config.appLanguage,
                        if (cameraMode == MODE_USB_CAMERA) "no_video_waiting_usb" else "no_video_waiting"), VoicePriority.CONTROL)
                }
            }
            window.decorView.postDelayed(this, 1000)
        }
    }

    // CameraX (modo local)
    private var frameAnalyzer: FrameAnalyzer? = null

    private var externalProcessor: ExternalFrameProcessor? = null

    // Fonte externa (webcam USB/UVC): canal de capacidade 1 e processador compartilhado, sem segundo detector.
    private var usbClient: com.travessia.segura.camera.UsbCameraClient? = null
    private var externalScope: CoroutineScope? = null
    private var externalFrameChannel: Channel<VideoFrame>? = null
    @Volatile private var lastExternalFrameNanos = 0L
    @Volatile private var externalStaleReported = false

    // Estado
    @Volatile private var isActive = false
    private var cameraMode = MODE_LOCAL_CAMERA
    private var lastVehicleCount = 0
    private var lastSituation = "VERIFICANDO"
    private var pendingVoiceStart = false

    // FPS
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    private fun tr(key: String): String = LanguageManager.text(this, key)

    private fun estadoExibido(estado: String): String {
        return when (estado) {
            EST_INATIVO -> tr("state_inactive")
            EST_RECONHECENDO -> tr("state_recognizing")
            AppConfig.EST_SEMAFORO -> tr("state_traffic_light")
            AppConfig.EST_FAIXA -> tr("state_crosswalk")
            AppConfig.EST_VIA_LIVRE -> tr("state_clear_road")
            else -> estado
        }
    }

    private fun situacaoExibida(situacao: String): String {
        return when (situacao) {
            "SEGURO" -> tr("situation_safe")
            "AGUARDE" -> tr("situation_wait")
            "VERIFICANDO" -> tr("situation_checking")
            else -> situacao
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        // Debug: log do app em arquivo no celular (testes na rua sem adb).
        com.travessia.segura.benchmark.DebugFileLog.start(applicationContext)
        config = AppConfig(this)
        cameraMode = config.cameraSource
        benchmarkLogger = BenchmarkLogger(this)
        initViews()
        aplicarInsetsSistema()
        initEngine()
        initVoiceCommands()
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        tvEstado = findViewById(R.id.tvEstado)
        tvSituacao = findViewById(R.id.tvSituacao)
        tvInfo = findViewById(R.id.tvInfo)
        tvDetectionCount = findViewById(R.id.tvDetectionCount)
        tvFps = findViewById(R.id.tvFps)
        tvAlertStatus = findViewById(R.id.tvAlertStatus)
        tvScreenTitle = findViewById(R.id.tvScreenTitle)
        btnToggle = findViewById(R.id.btnToggle)
        btnCrossing = findViewById(R.id.btnCrossing)
        btnConfig = findViewById(R.id.btnConfig)
        btnSource = findViewById(R.id.btnSource)
        btnVoice = findViewById(R.id.btnVoice)
        statusBar = findViewById(R.id.statusBar)
        bottomPanel = findViewById(R.id.bottomPanel)

        externalImageView = findViewById(R.id.externalImageView)

        btnToggle.isEnabled = false
        btnToggle.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); toggleAnalysis() }
        btnToggle.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            if (isActive || roadCoordinator.running) endAnalysis(EndReason.STOPPED)
            true
        }
        btnCrossing.setOnClickListener {
            crossing.confirmCameraDirection(SystemClock.elapsedRealtime())
        }
        btnConfig.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnSource.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); alternarFonte() }
        btnVoice.setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); startVoiceCommand() }

        tvAlertStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        tvEstado.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        tvSituacao.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        tvInfo.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        AccessibilityOrder.apply(listOf(tvScreenTitle, tvAlertStatus, btnToggle, btnVoice, btnCrossing,
            btnConfig, tvEstado, tvSituacao, tvDetectionCount, tvInfo))

        aplicarTextoBase()
        atualizarUIFonte()
        aplicarPreferenciasVisuais()
        tvInfo.text = tr("loading_model")
    }

    private fun initEngine() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        voz = VoiceSystem(this, config)
        orientationSource = com.travessia.segura.orientation.OrientationSensorSource(this, orientationTracker)
        crossing = CrossingVerification(
            onChange = { stage -> runOnUiThread { updateCrossingStage(stage) } },
            onReprompt = { stage -> runOnUiThread { repeatInstruction(stage, reminder = true) } },
            onRetry = { stage -> runOnUiThread {
                if (isActive && crossing.stage == stage) {
                    com.travessia.segura.benchmark.Perf.stats.count("seq_retries")
                    voz.resetarEstados("crossing_stage")
                    // Orientação concreta quando o sensor explica a falha; senão a mensagem genérica.
                    val cov = orientationTracker.lastCoverage
                    val dir = crossing.currentDirection
                    val key = when {
                        !orientationTracker.available || cov == null -> "verify_retry"
                        cov.status == com.travessia.segura.orientation.OrientationCoverageTracker.Status.UNSTABLE -> "hold_steady"
                        dir == CrossingVerification.Direction.FRONT && !cov.covered -> "back_to_front"
                        dir == CrossingVerification.Direction.RIGHT && !cov.covered -> "turn_more_right"
                        dir == CrossingVerification.Direction.LEFT && !cov.covered -> "turn_more_left"
                        else -> "verify_retry"
                    }
                    voz.falarEstado("crossing_stage", "retry_${stage.name}", RoadText.get(config.appLanguage, key))
                }
            } },
            onWrongDirection = { stage -> runOnUiThread {
                if (isActive && crossing.stage == stage) voz.falarEstado("crossing_hint", "wrong_${stage.name}",
                    RoadText.get(config.appLanguage, if (stage == CrossingVerification.Stage.AWAIT_LEFT) "wrong_direction_left" else "wrong_direction_right"))
            } })
        voz.onStateSpoken = { topic, state ->
            // Instrução da etapa ouvida por completo: só então conta o tempo para girar e manter.
            if (topic == "crossing_stage" && isActive && state == crossing.stage.name)
                crossing.instructionHeard(SystemClock.elapsedRealtime(), crossing.stage)
        }
        voz.onSpeakingChanged = { speaking ->
            if (speaking) {
                // Não reconhecer a própria fala do app: fecha a janela automática enquanto o TTS fala.
                if (autoListening && ::voiceCommands.isInitialized && voiceCommands.isListening) voiceCommands.cancel()
            } else listeningPolicy.onSpeechFinished(SystemClock.elapsedRealtime())
        }
        roadCoordinator = com.travessia.segura.road.InferenceCoordinator(this, config,
            manual = { isActive },
            onRoadConfirmed = { token -> runOnUiThread { onRoadConfirmed(token) } },
            onDebug = { frame -> runOnUiThread {
                if (foreground && frame.sessionToken == roadCoordinator.resultToken) {
                    if (frame.reason == "video_error") { crossing.invalidate(); overlayView.clear() }
                    overlayView.updateRoad(frame)
                    val messageState = when {
                        frame.reason in setOf("stale_frame","ffnet_stale") -> "stale_frame"
                        frame.reason == "video_error" -> "video_error"
                        else -> frame.state.name
                    }
                    if (!isActive) {
                        val message = RoadText.get(config.appLanguage, messageState)
                        val status = message + listOfNotNull(
                            if (frame.result?.roadDetected == true) RoadText.get(config.appLanguage, "road") else null,
                            if (frame.result?.sidewalkDetected == true) RoadText.get(config.appLanguage, "sidewalk") else null
                        ).joinToString(prefix = " ", separator = " / ")
                        if (tvInfo.text.toString() != status) tvInfo.text = status
                        // Na espera, falar apenas falhas (sem narrar cada oscilação da FFNet).
                        if (frame.state == com.travessia.segura.road.AnalysisState.ERROR || frame.reason == "video_error")
                            voz.falarEstado("road_status", messageState, message,
                                if (frame.reason == "video_error") VoicePriority.SAFETY else VoicePriority.INFO)
                        else voz.observarEstado("road_status", "healthy", 1500)
                    }
                    lastRoadState = frame.state
                }
            } },
            // Só limpa as falas se a etapa realmente mudou: invalidações repetidas não repetem a fala.
            onInvalidated = { if (crossing.invalidate()) voz.resetarEstados("crossing_") },
            onStale = { crossing.degrade() })

        Thread {
            detector = YoloDetector(
                context = this,
                modelPath = "model.tflite",
                classNames = config.classNames
            )
            detector.inicializar()

            runOnUiThread {
                if (detector.isReady) {
                    vehicleTracker = AdvancedVehicleTracker(
                        ghostFrames = config.trackerGhostFrames,
                        maxCenterDistancePct = config.trackerCenterDistancePct
                    )
                    rastreador = Rastreador(config)
                    stateMachine = StateMachine(config, voz, rastreador)

                    externalProcessor = ExternalFrameProcessor(
                        config = config,
                        detector = detector,
                        vehicleTracker = vehicleTracker,
                        tracker = rastreador,
                        stateMachine = stateMachine,
                        benchmarkLogger = benchmarkLogger,
                        onStatusUpdate = { estado, situacao, nVeiculos, semCor, capturedAt, token ->
                            runOnUiThread {
                                if (token != null && roadCoordinator.acceptsResult(token,capturedAt))
                                    atualizarUI(estado, situacao, nVeiculos, semCor)
                            }
                        },
                        onOverlayUpdate = { boxes, w, h, rot, capturedAt, token ->
                            runOnUiThread {
                                if (foreground && token != null && roadCoordinator.acceptsResult(token,capturedAt) &&
                                    (config.mostrarOverlay || config.roadDebugOverlay)) overlayView.updateDetections(boxes, w, h, rot)
                            }
                        },
                        coordinator = roadCoordinator,
                        crossing = crossing,
                        sessionMemory = sessionMemory,
                        orientation = orientationTracker
                    )

                    // Preparar FrameAnalyzer para câmera local
                    frameAnalyzer = FrameAnalyzer(
                        config = config,
                        detector = detector,
                        vehicleTracker = vehicleTracker,
                        tracker = rastreador,
                        stateMachine = stateMachine,
                        benchmarkLogger = benchmarkLogger,
                        onStatusUpdate = { estado, situacao, nVeiculos, semCor, capturedAt, token ->
                            runOnUiThread {
                                if (token != null && roadCoordinator.acceptsResult(token,capturedAt))
                                    atualizarUI(estado, situacao, nVeiculos, semCor)
                            }
                        },
                        onOverlayUpdate = { boxes, w, h, rot, capturedAt, token ->
                            runOnUiThread {
                                val started = SystemClock.elapsedRealtimeNanos()
                                if (foreground && token != null && roadCoordinator.acceptsResult(token,capturedAt) &&
                                    (config.mostrarOverlay || config.roadDebugOverlay)) overlayView.updateDetections(boxes, w, h, rot)
                                com.travessia.segura.benchmark.Perf.stats.record("overlay_ui_ms",
                                    (SystemClock.elapsedRealtimeNanos() - started) / 1e6f)
                            }
                        },
                        coordinator = roadCoordinator,
                        crossing = crossing,
                        orientation = orientationTracker,
                        sessionMemory = sessionMemory
                    )

                    tvInfo.text = tr("ready_wifi")
                    btnToggle.isEnabled = true

                    if (foreground) { configurarFonte(); resumeRoadMonitoring() }
                    if (pendingVoiceStart) {
                        pendingVoiceStart = false
                        userStopped = false
                        startAnalysis(automatic = false)
                    }

                } else {
                    tvInfo.text = tr("model_error")
                    voz.forcar("model_unavailable", tr("model_error"))
                    Log.e(TAG, "Falha ao inicializar detector")
                }
            }
        }.start()
    }

    // ================================================================
    //  ACESSIBILIDADE / COMANDOS DE VOZ
    // ================================================================

    private fun initVoiceCommands() {
        voiceCommands = VoiceCommandController(
            context = this,
            languageProvider = { config.appLanguage },
            listener = object : VoiceCommandController.Listener {
                override fun onListeningChanged(isListening: Boolean) {
                    runOnUiThread {
                        btnVoice.isEnabled = !isListening
                        btnVoice.text = if (isListening) tr("voice_button_listening") else tr("voice_button")
                        if (isListening) {
                            tvAlertStatus.text = tr("voice_listening")
                        } else if (!isActive) {
                            tvAlertStatus.text = tr("waiting_camera")
                        }
                    }
                }

                override fun onCommand(command: VoiceCommandController.Command, rawText: String) {
                    runOnUiThread {
                        val automatic = autoListening
                        autoListening = false
                        listeningPolicy.onResult(SystemClock.elapsedRealtime())
                        if (command == VoiceCommandController.Command.UNKNOWN && automatic) {
                            // Fala não reconhecida numa etapa direita/esquerda: uma dica por etapa, sem loop.
                            val stage = crossing.stage
                            if (isActive && rawText.isNotBlank() && stage in setOf(
                                    CrossingVerification.Stage.AWAIT_RIGHT, CrossingVerification.Stage.AWAIT_LEFT))
                                voz.falarEstado("crossing_hint", stage.name, RoadText.get(config.appLanguage, "say_ready_hint"))
                            return@runOnUiThread
                        }
                        executarComandoVoz(command)
                    }
                }

                override fun onRecognitionError(errorCode: Int) {
                    runOnUiThread {
                        val automatic = autoListening
                        autoListening = false
                        val quietErrors = setOf(
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_CLIENT
                        )
                        val quiet = errorCode in quietErrors
                        val announceLimit = listeningPolicy.onError(quiet, SystemClock.elapsedRealtime())
                        if (automatic) {
                            if (announceLimit && isActive) {
                                val msg = RoadText.get(config.appLanguage, "voice_retry")
                                tvInfo.text = msg
                                voz.forcar("voice_error", msg)
                            }
                            return@runOnUiThread
                        }
                        // ERROR_CLIENT costuma ser o próprio cancelamento do app: não anunciar.
                        if (errorCode == SpeechRecognizer.ERROR_CLIENT) return@runOnUiThread
                        val msg = if (quiet) tr("voice_unknown") else RoadText.get(config.appLanguage, "voice_retry")
                        tvInfo.text = msg
                        voz.forcar("voice_error", msg)
                    }
                }
            }
        )
    }

    private fun micGranted(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    /**
     * Janelas de escuta automáticas durante a análise (e na espera, se "escuta automática" estiver ativa),
     * nunca enquanto o TTS fala. O botão Voz continua disponível, mas não é necessário.
     */
    private fun maybeOpenListeningWindow() {
        if (!::voiceCommands.isInitialized || !::voz.isInitialized) return
        val wanted = config.voiceCommandsEnabled && micGranted() &&
            (isActive || config.voiceAutoListen || listenAfterVideoLoss)
        if (!listeningPolicy.shouldOpen(wanted, voz.ocupado, voiceCommands.isListening, SystemClock.elapsedRealtime())) return
        autoListening = true
        listenStage = crossing.stage
        if (!voiceCommands.startListening()) {
            autoListening = false
            if (listeningPolicy.onError(false, SystemClock.elapsedRealtime())) {
                val msg = RoadText.get(config.appLanguage, "voice_retry")
                tvInfo.text = msg
                voz.forcar("voice_error", msg)
            }
        }
    }

    private fun startVoiceCommand() {
        if (!config.voiceCommandsEnabled) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MICROPHONE_PERMISSION_CODE
            )
            return
        }

        voz.interromper()
        if (voiceCommands.isListening) voiceCommands.cancel()
        listeningPolicy.reset()
        autoListening = false
        listenStage = crossing.stage
        if (!voiceCommands.startListening()) {
            val msg = tr("voice_unavailable")
            tvInfo.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun executarComandoVoz(command: VoiceCommandController.Command) {
        val now = SystemClock.elapsedRealtime()
        val heardAt = listenStage
        listenStage = null
        when (command) {
            VoiceCommandController.Command.START -> {
                if (!btnToggle.isEnabled) {
                    pendingVoiceStart = true
                    voz.forcar("voice_model", tr("voice_model_loading_start"))
                } else if (isActive) {
                    voz.forcar("voice_state", tr("voice_already_active"))
                } else {
                    userStopped = false
                    startAnalysis(automatic = false)
                }
            }

            VoiceCommandController.Command.STOP -> {
                pendingVoiceStart = false
                if (isActive || roadCoordinator.running) endAnalysis(EndReason.STOPPED)
                else voz.forcarPrioritario("voice_state", tr("voice_already_inactive"), VoicePriority.CONTROL)
            }

            VoiceCommandController.Command.CANCEL -> {
                pendingVoiceStart = false
                if (isActive) endAnalysis(EndReason.CANCELLED)
                else voz.forcar("voice_state", RoadText.get(config.appLanguage, "nothing_to_repeat"))
            }

            VoiceCommandController.Command.RESTART -> {
                if (isActive) {
                    sessionHandler.removeCallbacksAndMessages(FINISH_TOKEN)
                    voz.resetarEstados("crossing_")
                    crossing.restart(now)
                } else if (btnToggle.isEnabled) {
                    userStopped = false
                    startAnalysis(automatic = false)
                }
            }

            // Declaração do usuário: inicia/encerra a janela da direção atual, nunca prova cobertura.
            VoiceCommandController.Command.CONFIRM_DIRECTION -> crossing.confirmCameraDirection(now, heardAt)
            VoiceCommandController.Command.CONTINUE -> crossing.requestContinue(now, heardAt)
            VoiceCommandController.Command.REPEAT -> repeatInstruction(crossing.stage, reminder = false)

            VoiceCommandController.Command.STATUS -> falarStatus()
            VoiceCommandController.Command.SOURCE_PHONE -> selecionarFontePorVoz(MODE_LOCAL_CAMERA)
            // "câmera externa" = webcam USB/UVC (X5 descontinuada).
            VoiceCommandController.Command.SOURCE_EXTERNAL -> selecionarFontePorVoz(MODE_USB_CAMERA)
            VoiceCommandController.Command.SOURCE_USB -> selecionarFontePorVoz(MODE_USB_CAMERA)

            VoiceCommandController.Command.SETTINGS -> {
                if (isActive || roadCoordinator.running) endAnalysis(EndReason.STOPPED)
                startActivity(Intent(this, SettingsActivity::class.java))
            }

            VoiceCommandController.Command.HELP -> voz.forcar("voice_help",
                if (isActive) RoadText.get(config.appLanguage, "verify_voice_help") else tr("voice_help"))

            else -> voz.forcar("voice_unknown", tr("voice_unknown"))
        }
    }

    private fun selecionarFontePorVoz(mode: Int) {
        if (isActive) {
            voz.forcar("voice_source", tr("voice_stop_before_source"))
            return
        }
        // Na espera por rua a troca é permitida: suspende o monitoramento e o retoma com a nova fonte.
        if (roadCoordinator.running) {
            roadCoordinator.suspendMonitoring()
            cameraExecutor.execute { roadCoordinator.release() }
        }

        pararUsb(); pararProcessamentoExterno(); pararCameraLocal()
        cameraMode = mode; config.cameraSource = mode
        resetVision(); crossing.cancel()
        atualizarUIFonte()
        configurarFonte()
        val confirmation = if (mode == MODE_USB_CAMERA) {
            "USB / UVC"
        } else if (mode == MODE_LOCAL_CAMERA) {
            tr("voice_source_phone_selected")
        } else {
            tr("voice_source_external_selected")
        }
        voz.forcar("voice_source", confirmation)
        // Retoma a busca de rua com a nova câmera (sem reiniciar após um "Parar" explícito).
        if (!userStopped) resumeRoadMonitoring()
    }

    private fun falarStatus() {
        if (roadCoordinator.running && !isActive) {
            voz.forcar("voice_status", com.travessia.segura.road.RoadText.get(config.appLanguage,
                lastRoadState?.name ?: "MONITORING"))
            return
        }
        val source = if (cameraMode == MODE_LOCAL_CAMERA) tr("voice_source_phone") else tr("voice_source_external")
        val msg = if (isActive) {
            tr("voice_status_active").format(source, lastVehicleCount, situacaoExibida(lastSituation))
        } else {
            tr("voice_status_inactive").format(source)
        }
        voz.forcar("voice_status", msg)
    }

    // ================================================================
    //  FONTE DE VÍDEO
    // ================================================================

    private fun alternarFonte() {
        if (isActive) {
            Toast.makeText(this, tr("stop_before_source"), Toast.LENGTH_SHORT).show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tr("accessibility_source"))
            .setItems(arrayOf(tr("source_local"), "USB / UVC")) { _, selected ->
                // Na busca pela rua a troca é permitida: suspende o monitoramento e o retoma com a nova fonte.
                if (roadCoordinator.running) {
                    roadCoordinator.suspendMonitoring()
                    cameraExecutor.execute { roadCoordinator.release() }
                }
                pararUsb(); pararProcessamentoExterno(); pararCameraLocal()
                cameraMode = if (selected == 1) MODE_USB_CAMERA else MODE_LOCAL_CAMERA; config.cameraSource = cameraMode
                resetVision(); crossing.cancel()
                overlayView.clear(); atualizarUIFonte(); configurarFonte()
                if (!userStopped) resumeRoadMonitoring()
            }.show()
    }

    private fun atualizarUIFonte() {
        when (cameraMode) {
            MODE_USB_CAMERA -> {
                btnSource.text = "USB"
                previewView.visibility = View.GONE
                externalImageView?.visibility = View.VISIBLE
            }
            MODE_LOCAL_CAMERA -> {
                btnSource.text = tr("source_local")
                previewView.visibility = View.VISIBLE
                externalImageView?.visibility = View.GONE
            }
        }
    }

    private fun configurarFonte() {
        aplicarPreferenciasVisuais()
        when (cameraMode) {
            MODE_LOCAL_CAMERA -> { pararUsb(); configurarCameraLocal() }
            MODE_USB_CAMERA -> configurarUsb()
        }
    }

    // ================================================================
    //  APARÊNCIA / HUD
    // ================================================================

    /**
     * Android 15/target 35 usa edge-to-edge. Em landscape a barra de navegação pode
     * ficar à direita e cobrir botões. Mantemos a câmera em tela cheia, mas deslocamos
     * apenas o HUD para dentro das áreas seguras (status bar, navigation bar e cutout).
     */
    private fun aplicarInsetsSistema() {
        val root = findViewById<View>(R.id.rootMain)

        val topLeft = statusBar.paddingLeft
        val topTop = statusBar.paddingTop
        val topRight = statusBar.paddingRight
        val topBottom = statusBar.paddingBottom

        val bottomLeft = bottomPanel.paddingLeft
        val bottomTop = bottomPanel.paddingTop
        val bottomRight = bottomPanel.paddingRight
        val bottomBottom = bottomPanel.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            statusBar.setPadding(
                topLeft + bars.left,
                topTop + bars.top,
                topRight + bars.right,
                topBottom
            )

            bottomPanel.setPadding(
                bottomLeft + bars.left,
                bottomTop,
                bottomRight + bars.right,
                bottomBottom + bars.bottom
            )

            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun aplicarPreferenciasVisuais() {
        if (!::config.isInitialized) return

        externalImageView?.scaleType = ImageView.ScaleType.CENTER_CROP

        overlayView.visibility = if (config.mostrarOverlay || config.roadDebugOverlay) View.VISIBLE else View.GONE
        statusBar.visibility = if (config.mostrarPainelSuperior) View.VISIBLE else View.GONE
        // The preference hides informational text, never Stop / direction / voice controls.
        bottomPanel.visibility = View.VISIBLE
        for (view in listOf(tvEstado,tvSituacao,tvDetectionCount,tvInfo))
            view.visibility = if (config.mostrarPainelInferior) View.VISIBLE else View.GONE
        btnVoice.visibility = if (config.voiceCommandsEnabled) View.VISIBLE else View.GONE
        // A fonte da câmera é escolhida em Configurações (ou por voz); não fica na tela da câmera.
        btnSource.visibility = View.GONE
    }


    private fun aplicarTextoBase() {
        tvScreenTitle.text = tr("screen_title")
        btnConfig.text = tr("config_short")
        btnToggle.text = if (isActive) tr("stop") else tr("start")
        btnVoice.text = tr("voice_button")

        btnToggle.contentDescription = if (isActive) tr("accessibility_stop") else tr("accessibility_start")
        btnSource.contentDescription = tr("accessibility_source")
        btnVoice.contentDescription = tr("voice_button_description")
        btnConfig.contentDescription = tr("accessibility_settings")

        if (!isActive) {
            tvAlertStatus.text = tr("waiting_camera")
            tvEstado.text = tr("state_inactive")
            tvSituacao.text = "---"
            tvDetectionCount.text = "${tr("vehicles")}: 0"
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        // Fonte escolhida em Configurações: aplicar ao voltar para a tela principal.
        if (::config.isInitialized && config.cameraSource != cameraMode && !isActive) {
            pararUsb(); pararProcessamentoExterno(); pararCameraLocal()
            cameraMode = config.cameraSource
            if (::crossing.isInitialized) crossing.cancel()
            if (::roadCoordinator.isInitialized) resetVision()
            if (btnToggle.isEnabled) configurarFonte()
        }
        window.decorView.removeCallbacks(videoWatchdog)
        window.decorView.postDelayed(videoWatchdog, 1000)
        if (::config.isInitialized) {
            aplicarTextoBase()
            atualizarUIFonte()
            aplicarPreferenciasVisuais()
            if (btnToggle.isEnabled && cameraMode == MODE_USB_CAMERA) configurarUsb()
            resumeRoadMonitoring()
            atualizarBotaoToggle()
            sessionHandler.removeCallbacks(listenTicker)
            sessionHandler.postDelayed(listenTicker, 700L)
            sessionHandler.removeCallbacks(yawDebugTicker)
            sessionHandler.postDelayed(yawDebugTicker, 100L)
            android.view.Choreographer.getInstance().removeFrameCallback(uiFrameCallback)
            lastUiFrameNanos = 0L
            android.view.Choreographer.getInstance().postFrameCallback(uiFrameCallback)
            if (::vehicleTracker.isInitialized) {
                vehicleTracker.configure(
                    centerDistancePct = config.trackerCenterDistancePct,
                    ghostFrameCount = config.trackerGhostFrames
                )
            }

        }
    }

    // ================================================================
    //  FONTE EXTERNA (USB/UVC)
    // ================================================================

    /** Frame mais antigo que isto não é processado (idade de chegada/decodificação). */
    private val externalStaleMs = 1500L
    private fun externalFrameStale(frameNanos: Long, nowNanos: Long) =
        frameNanos <= 0L || nowNanos - frameNanos > externalStaleMs * 1_000_000L

    private fun receberFrameExterno(frame: VideoFrame) {
        val generation = roadCoordinator.resultToken
        val bitmap = if (frame.rotationDegrees == 0) {
            frame.bitmap
        } else {
            val matrix = Matrix().apply { postRotate(frame.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(frame.bitmap, 0, 0, frame.bitmap.width, frame.bitmap.height, matrix, true)
                .also { frame.bitmap.recycle() }
        }
        val normalized = VideoFrame(bitmap, frame.timestampNanos, 0)
        if (cameraMode == MODE_USB_CAMERA) {
            // Todos os quadros da webcam (não só os do YOLO): giro estimado pela imagem -> rastreador de orientação.
            val step = runCatching { visualYaw.update(bitmap) }.getOrNull()
            orientationTracker.onSample(frame.timestampNanos / 1_000_000, if (step?.accepted == true || (step != null &&
                step.shiftColumns == 0 && visualYaw.lastAccepted)) visualYaw.yawDeg else null)
        }
        val previewBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        lastExternalFrameNanos = normalized.timestampNanos
        externalStaleReported = false
        previewDispatcher.offer(PendingPreview(previewBitmap,generation,frame.timestampNanos / 1_000_000))
        val sent = if ((isActive || roadCoordinator.running) && cameraMode != MODE_LOCAL_CAMERA) externalFrameChannel?.trySend(normalized)?.isSuccess == true else false
        if (!sent) bitmap.recycle()
    }

    private fun iniciarProcessamentoExterno() {
        pararProcessamentoExterno()
        lastExternalFrameNanos = 0L
        externalStaleReported = false
        externalFrameChannel = Channel(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { it.bitmap.recycle() })
        externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val channel = externalFrameChannel
        externalScope?.launch {
            if (channel != null) for (frame in channel) {
                try {
                if ((!isActive && !roadCoordinator.running) || cameraMode == MODE_LOCAL_CAMERA) continue
                if (externalFrameStale(frame.timestampNanos, SystemClock.elapsedRealtimeNanos())) {
                    withContext(Dispatchers.Main) { invalidarVideoExterno("Frame externo vencido") }
                    continue
                }
                // The bitmap was rotated exactly once before preview and inference.
                externalProcessor?.processarFrame(frame.bitmap, overlayRotation = 0, capturedAtMs = frame.timestampNanos / 1_000_000)
                } finally { frame.bitmap.recycle() }
            }
        }
        externalScope?.launch {
            while ((isActive || roadCoordinator.running) && cameraMode != MODE_LOCAL_CAMERA) {
                delay(500L)
                if (lastExternalFrameNanos != 0L && externalFrameStale(lastExternalFrameNanos, SystemClock.elapsedRealtimeNanos())
                ) {
                    if (!externalStaleReported) {
                        externalStaleReported = true
                        Log.w(TAG, "External frame became stale after ${externalStaleMs}ms")
                        withContext(Dispatchers.Main) { invalidarVideoExterno(RoadText.get(config.appLanguage,"stale_frame")) }
                    }
                }
            }
        }
    }

    private fun pararProcessamentoExterno() {
        previewDispatcher.clear()
        externalFrameChannel?.cancel()
        externalFrameChannel = null
        externalScope?.cancel()
        externalScope = null
        lastExternalFrameNanos = 0L
        externalStaleReported = false
        externalImageView?.setImageBitmap(null)
        externalPreviewBitmap?.recycle(); externalPreviewBitmap = null
    }

    private fun invalidarVideoExterno(reason: String) {
        // Invalida (resultado inconclusivo, encerramento normal) em vez de deixar a sessão órfã.
        crossing.invalidate()
        roadCoordinator.reportVideoError()
        if (cameraMode == MODE_LOCAL_CAMERA) return
        overlayView.clear()
        resetVision()
        if (::stateMachine.isInitialized && isActive) stateMachine.entrarEstado(EST_RECONHECENDO)
        lastVehicleCount = 0
        lastSituation = "VERIFICANDO"
        tvDetectionCount.text = "${tr("vehicles")}: 0"
        tvSituacao.text = situacaoExibida("VERIFICANDO")
        tvSituacao.setTextColor(0xFFFFAA00.toInt())
        tvAlertStatus.text = reason
    }

    // ================================================================
    //  CÂMERA LOCAL (CameraX original)
    // ================================================================

    private fun configurarCameraLocal() {
        pararProcessamentoExterno()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }
    }

    private fun pararCameraLocal() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                cameraProviderFuture.get().unbindAll()
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao parar câmera local: ${e.message}")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // KEEP_ONLY_LATEST: sem fila; o analisador sempre recebe o frame mais novo.
            val builder = ImageAnalysis.Builder()
                .setTargetResolution(Size(config.captureWidth, config.captureWidth * 9 / 16))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            if (config.fastCameraPipeline) {
                // Conversão/rotação nativas do CameraX; o analisador copia num Bitmap reutilizado.
                builder.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setOutputImageRotationEnabled(true)
            }
            val analysis = builder.build()
            Log.i("SenseWalkPerf", "camera_pipeline fast=${config.fastCameraPipeline}")

            analysis.setAnalyzer(cameraExecutor, frameAnalyzer!!)

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                updateHorizontalFov(camera)
                camera.cameraInfo.cameraState.observe(this) { state ->
                    if (state.error != null) { crossing.invalidate(); overlayView.clear(); roadCoordinator.reportVideoError() }
                }
                Log.i(TAG, "Camera local iniciada")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao iniciar camera: ${e.message}")
                tvInfo.text = "${tr("camera_error")}: ${e.message}"
                if (::voz.isInitialized) voz.forcar("camera_failure", RoadText.get(config.appLanguage,"video_error"))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** FOV horizontal real da imagem analisada (foco + sensor + recorte 16:9 + retrato/paisagem). */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun updateHorizontalFov(camera: Camera) {
        runCatching {
            val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
            val focal = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val size = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            val long = config.captureWidth; val short = config.captureWidth * 9 / 16
            val fov = if (focal != null && size != null) com.travessia.segura.orientation.OrientationMath.horizontalFovDegrees(
                focal, size.width, size.height, if (portrait) short else long, if (portrait) long else short) else null
            if (fov != null) orientationTracker.setHorizontalFov(fov)
            Log.i("SenseWalkOrientation", "hfov=$fov lateral=[${orientationTracker.lateralMinDeg},${orientationTracker.lateralMaxDeg}] portrait=$portrait")
        }.onFailure { Log.w("SenseWalkOrientation", "fov_unavailable ${it.message}") }
    }

    // ================================================================
    //  CONTROLE
    // ================================================================

    /**
     * Botão principal: durante a análise = Parar; fora dela (inclusive enquanto procura a rua) = Iniciar análise.
     * Antes, durante a busca pela rua ele mostrava "Parar" e um toque desligava o modo automático sem querer.
     * Parar tudo (inclusive a busca): comando de voz "parar" ou toque longo no botão.
     */
    private fun toggleAnalysis() {
        if (isActive) endAnalysis(EndReason.STOPPED)
        else { userStopped = false; startAnalysis(automatic = false) }
    }

    private fun atualizarBotaoToggle() {
        btnToggle.text = if (isActive) tr("stop") else tr("start")
        btnToggle.contentDescription = if (isActive) tr("accessibility_stop") else tr("accessibility_start")
    }

    /** FFNet confirmou rua: inicia uma única análise, salvo parada explícita ou sessão superada. */
    private fun onRoadConfirmed(token: Long) {
        if (foreground && !userStopped && !isActive && roadCoordinator.running && token == roadCoordinator.resultToken &&
            btnToggle.isEnabled) {
            startAnalysis(automatic = true)
        } else if (!isActive) {
            cameraExecutor.execute { roadCoordinator.finishAnalysis() }
        }
    }

    /** ANALYZING: YOLO ativo e FFNet suspensa. Manual não depende da FFNet. */
    private fun startAnalysis(automatic: Boolean) {
        if (isActive || !::stateMachine.isInitialized) return
        analysisSession++
        sessionHandler.removeCallbacksAndMessages(FINISH_TOKEN)
        isActive = true // suspende a FFNet imediatamente (manual = { isActive })
        analysisStartedAtMs = SystemClock.elapsedRealtime()
        videoLossReported = false
        listenAfterVideoLoss = false
        cameraExecutor.execute { roadCoordinator.beginAnalysis() }
        if (config.benchmarkEnabled) {
            val source = if (cameraMode == MODE_USB_CAMERA) "usb_uvc" else "phone"
            benchmarkLogger.startSession(source)
        }
        // Começar cada sessão sem IDs/histórico/estados temporais herdados da sessão anterior.
        resetVision()
        voz.resetarEstados("crossing_")
        listeningPolicy.reset()
        // Câmera nativa: sensores do celular definem a frente no próximo instante estável. USB: sem IMU.
        orientationTracker.reset()
        val sensorOk = if (cameraMode == MODE_USB_CAMERA) {
            // USB: orientação estimada pela imagem; o sensor do celular não descreve a webcam.
            orientationSource.stop(); enableVisualOrientation(); true
        } else cameraMode == MODE_LOCAL_CAMERA && orientationSource.start()
        if (sensorOk) orientationTracker.requestCalibration() else orientationSource.stop()
        sessionMemory.beginSession(analysisSession)
        stateMachine.entrarEstado(EST_RECONHECENDO)
        crossing.start(SystemClock.elapsedRealtime())
        // Explica os comandos uma vez, no início: "pronto" em cada direção e "parar" para encerrar.
        voz.forcar("inicio", RoadText.get(config.appLanguage, if (automatic) "road_confirmed" else "analysis_intro"))
        atualizarBotaoToggle()
        tvAlertStatus.text = tr("analysis_running")
        overlayView.clear()
        when (cameraMode) {
            MODE_USB_CAMERA -> configurarUsb()
            MODE_LOCAL_CAMERA -> tvInfo.text = tr("analyzing")
        }
        Log.i(TAG, "Análise iniciada (modo: $cameraMode, automática: $automatic)")
    }

    /**
     * FINISHED/CANCELLED: volta à busca de rua se o monitoramento automático estiver habilitado.
     * STOPPED: encerra falas, FFNet e YOLO; permanece parado até novo comando de início.
     */
    private fun endAnalysis(reason: EndReason) {
        analysisSession++
        sessionHandler.removeCallbacksAndMessages(FINISH_TOKEN)
        val wasActive = isActive
        isActive = false
        crossing.cancel()
        orientationSource.stop(); orientationTracker.reset()
        overlayView.updateLookTag("", false)
        if (cameraMode == MODE_USB_CAMERA && usbClient != null) { enableVisualOrientation(); orientationTracker.requestCalibration() }
        sessionMemory.clear()
        if (::voiceCommands.isInitialized && reason == EndReason.STOPPED) voiceCommands.cancel()
        voz.interromper(preserveSafety = reason != EndReason.STOPPED)
        voz.resetarEstados("crossing_")
        val benchmarkResult = if (wasActive) benchmarkLogger.stopSession() else null
        if (::stateMachine.isInitialized) stateMachine.entrarEstado(EST_INATIVO)
        // Sem imagem: não voltar a procurar rua sozinho (repetiria o aviso); "iniciar análise" ou trocar a câmera.
        val returnToSearch = reason != EndReason.STOPPED && reason != EndReason.NO_VIDEO && !userStopped &&
            foreground && config.automaticRoadDetection
        if (reason == EndReason.STOPPED) userStopped = true
        if (returnToSearch) {
            cameraExecutor.execute { roadCoordinator.finishAnalysis() }
            resumeRoadMonitoring()
        } else {
            roadCoordinator.suspendMonitoring()
            cameraExecutor.execute { roadCoordinator.release() }
            if (cameraMode == MODE_USB_CAMERA) pararUsb()
        }
        val key = when (reason) {
            EndReason.STOPPED -> "analysis_stopped"
            EndReason.CANCELLED -> "analysis_cancelled"
            EndReason.FINISHED -> if (returnToSearch) "analysis_finished" else null
            EndReason.NO_VIDEO -> null
        }
        listenAfterVideoLoss = reason == EndReason.NO_VIDEO
        val text = if (reason == EndReason.NO_VIDEO) noVideoText()
            else key?.let { RoadText.get(config.appLanguage, it) } ?: tr("analysis_stop_voice")
        if (reason == EndReason.FINISHED) voz.falarPrioritario("cancelado", text, VoicePriority.INFO)
        else voz.forcarPrioritario("cancelado", text, VoicePriority.CONTROL)
        atualizarBotaoToggle()
        btnCrossing.visibility = View.GONE
        tvAlertStatus.text = tr("analysis_paused")
        tvInfo.text = if (benchmarkResult != null) "${tr("paused")} ${tr("benchmark_saved")}: ${benchmarkResult.sessionName}"
            else text
        tvEstado.text = tr("state_inactive")
        tvSituacao.text = "---"
        tvDetectionCount.text = "${tr("vehicles")}: 0"
        lastVehicleCount = 0
        lastSituation = "VERIFICANDO"
        overlayView.clear()
        resetVision()
        Log.i(TAG, "Análise encerrada: $reason (retorno à busca: $returnToSearch)")
    }

    /** Mensagem falada uma vez quando não há imagem; indica a fonte e como trocar por voz. */
    private fun noVideoText(): String = RoadText.get(config.appLanguage, when (cameraMode) {
        MODE_USB_CAMERA -> "no_video_usb"
        else -> "no_video"
    })

    /** Resultado comunicado: mantém alertas por alguns segundos e encerra normalmente. */
    private fun scheduleNormalFinish() {
        sessionHandler.removeCallbacksAndMessages(FINISH_TOKEN)
        val session = analysisSession
        val runnable = object : Runnable {
            override fun run() {
                if (session != analysisSession || !isActive) return
                // Não encerrar no meio de um alerta de perigo ou de uma fala.
                if (crossing.stage == CrossingVerification.Stage.DANGER || voz.ocupado) {
                    androidx.core.os.HandlerCompat.postDelayed(sessionHandler, this, FINISH_TOKEN, 1000L)
                    return
                }
                if (crossing.finished) endAnalysis(EndReason.FINISHED)
            }
        }
        androidx.core.os.HandlerCompat.postDelayed(sessionHandler, runnable, FINISH_TOKEN, RESULT_HOLD_MS)
    }

    // ================================================================
    //  UI
    // ================================================================

    private fun resetVision() {
        roadCoordinator.invalidateSession()
        cameraExecutor.execute {
            synchronized(roadCoordinator) { frameAnalyzer?.resetar(); externalProcessor?.resetar() }
        }
    }

    private fun stageTextKey(stage: CrossingVerification.Stage): String? = when (stage) {
        CrossingVerification.Stage.AWAIT_RIGHT -> "verify_right"
        CrossingVerification.Stage.AWAIT_LEFT -> "verify_left"
        CrossingVerification.Stage.AWAIT_FRONT -> "verify_front"
        CrossingVerification.Stage.VERIFY_RIGHT, CrossingVerification.Stage.VERIFY_LEFT,
        CrossingVerification.Stage.VERIFY_FRONT -> "verify_analyzing"
        CrossingVerification.Stage.FAVORABLE_OBSERVATION -> "verify_favorable"
        CrossingVerification.Stage.DANGER -> "verify_danger"
        // Sem falar de faixa: a ausência de detecção não prova ausência de faixa.
        CrossingVerification.Stage.INCONCLUSIVE -> when {
            // Resumo factual quando as três direções foram observadas sem conflito (não autoriza a travessia).
            crossing.noConflictObservedAllDirections -> "summary_clear"
            crossing.cannotVerifyDirection -> "verify_direction_unavailable"
            else -> "verify_inconclusive"
        }
        else -> null
    }

    /** Resultado falado por direção (o que foi visto), em vez de um "inconclusivo" genérico. */
    private fun directionSummary(): String {
        val lang = config.appLanguage
        val parts = CrossingVerification.Direction.values().map { d ->
            val name = RoadText.get(lang, "dir_${d.name.lowercase()}")
            val key = when {
                crossing.outcome(d) == CrossingVerification.ObservationOutcome.NO_CONFLICT_OBSERVED -> "obs_no_conflict"
                crossing.outcome(d) == CrossingVerification.ObservationOutcome.POTENTIAL_DANGER -> "obs_danger"
                crossing.cleanButUnverified(d) -> "obs_clean_unverified"
                crossing.outcome(d) == CrossingVerification.ObservationOutcome.NOT_OBSERVED -> "obs_not_observed"
                crossing.hadUncertainVehicles(d) -> "obs_uncertain_vehicles"
                else -> "obs_inconclusive"
            }
            "$name: ${RoadText.get(lang, key)}."
        }
        val anyDanger = CrossingVerification.Direction.values().any {
            crossing.outcome(it) == CrossingVerification.ObservationOutcome.POTENTIAL_DANGER }
        return parts.joinToString(" ") + " " + RoadText.get(lang, if (anyDanger) "summary_tail_danger" else "summary_tail")
    }

    private fun stageText(stage: CrossingVerification.Stage): String? {
        if (stage == CrossingVerification.Stage.INCONCLUSIVE && !crossing.noConflictObservedAllDirections &&
            CrossingVerification.Direction.values().any { crossing.outcome(it) != CrossingVerification.ObservationOutcome.NOT_OBSERVED })
            return directionSummary()
        val key = stageTextKey(stage) ?: return null
        val hand = if (stage == CrossingVerification.Stage.FAVORABLE_OBSERVATION && crossing.handSignalApplicable)
            " " + RoadText.get(config.appLanguage,"verify_hand") else ""
        return RoadText.get(config.appLanguage, key) + hand
    }

    /** Uma fala por mudança de etapa; falas de etapas superadas são descartadas pelo VoiceSystem. */
    private fun updateCrossingStage(stage: CrossingVerification.Stage) {
        if (!foreground || crossing.stage != stage) return // descartar callbacks superados na UI
        if (!isActive && stage != CrossingVerification.Stage.IDLE) return
        val buttonKey = when (stage) {
            CrossingVerification.Stage.AWAIT_RIGHT, CrossingVerification.Stage.VERIFY_RIGHT -> "confirm_right"
            CrossingVerification.Stage.AWAIT_LEFT, CrossingVerification.Stage.VERIFY_LEFT -> "confirm_left"
            CrossingVerification.Stage.AWAIT_FRONT, CrossingVerification.Stage.VERIFY_FRONT -> "confirm_front"
            else -> null
        }
        // Botão opcional (alternativa acessível); a sequência avança sozinha.
        btnCrossing.visibility = if (buttonKey != null) View.VISIBLE else View.GONE
        if (buttonKey != null) {
            btnCrossing.text = RoadText.get(config.appLanguage, buttonKey)
            btnCrossing.contentDescription = btnCrossing.text
        }
        recordStageTiming(stage)
        val text = stageText(stage)
        if (text != null && stage == CrossingVerification.Stage.DANGER && voz.alertaEmAndamento) {
            // O alerta específico do veículo já está sendo falado: não empilhar um segundo aviso genérico.
            tvInfo.text = text
            voz.observarEstado("crossing_stage", stage.name)
        } else if (text != null) {
            tvInfo.text = text
            voz.falarEstado("crossing_stage", stage.name, text,
                if (stage == CrossingVerification.Stage.DANGER) VoicePriority.SAFETY else VoicePriority.CROSSING)
        } else if (stage != CrossingVerification.Stage.MONITORING) voz.observarEstado("crossing_stage",stage.name)
        if (crossing.finished) scheduleNormalFinish()
    }

    /**
     * Uma instrução descartada pela fila (expirou atrás de outra fala) é falada assim que o TTS fica livre.
     * Idempotente: o VoiceSystem só fala cada estado uma vez por transição, então isto nunca gera repetição.
     */
    private fun ensureStageSpoken() {
        if (!isActive || !::voz.isInitialized || voz.ocupado) return
        val stage = crossing.stage
        // Perigo já anunciado pelo alerta do veículo; etapas de observação são curtas e não precisam de reforço.
        if (stage in setOf(CrossingVerification.Stage.MONITORING, CrossingVerification.Stage.DANGER,
                CrossingVerification.Stage.VERIFY_RIGHT, CrossingVerification.Stage.VERIFY_LEFT,
                CrossingVerification.Stage.VERIFY_FRONT)) return
        val text = stageText(stage) ?: return
        voz.falarEstado("crossing_stage", stage.name, text,
            if (stage == CrossingVerification.Stage.DANGER) VoicePriority.SAFETY else VoicePriority.CROSSING)
    }

    private var lastStageChangeMs = 0L
    private var lastTimedStage: CrossingVerification.Stage? = null
    /** Métrica reproduzível no aparelho: duração de cada etapa (logcat SenseWalkSeq / SenseWalkPerf). */
    private fun recordStageTiming(stage: CrossingVerification.Stage) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastTimedStage
        if (previous != null && lastStageChangeMs > 0) {
            val dur = now - lastStageChangeMs
            com.travessia.segura.benchmark.Perf.stats.record("seq_${previous.name.lowercase()}_ms", dur.toFloat())
            Log.i("SenseWalkSeq", "stage=$previous dur_ms=$dur next=$stage frames=${crossing.lastObservationFrames} " +
                "outcomes=${CrossingVerification.Direction.values().joinToString { "$it:${crossing.outcome(it)}" }}")
        }
        lastTimedStage = stage; lastStageChangeMs = now
    }

    /** "Repetir" ou lembrete único da própria máquina; nunca reinicia a etapa. */
    private fun repeatInstruction(stage: CrossingVerification.Stage, reminder: Boolean) {
        if (!foreground || !isActive || crossing.stage != stage) {
            if (!reminder) voz.forcar("voice_state", RoadText.get(config.appLanguage, "nothing_to_repeat"))
            return
        }
        val text = stageText(stage) ?: run {
            if (!reminder) voz.forcar("voice_state", RoadText.get(config.appLanguage, "nothing_to_repeat"))
            return
        }
        val prefix = if (reminder) RoadText.get(config.appLanguage, "verify_reprompt") else ""
        voz.resetarEstados("crossing_stage")
        voz.falarEstado("crossing_stage", stage.name, prefix + text,
            if (stage == CrossingVerification.Stage.DANGER) VoicePriority.SAFETY else VoicePriority.CROSSING)
    }

    private fun atualizarUI(estado: String, situacao: String, nVeiculos: Int, semCor: String) {
        if (!foreground) return
        lastVehicleCount = nVeiculos
        lastSituation = situacao
        val stateText = estadoExibido(estado)
        if (tvEstado.text.toString() != stateText) tvEstado.text = stateText

        tvEstado.setTextColor(
            when (situacao) {
                "SEGURO" -> 0xFF44DD44.toInt()
                "AGUARDE" -> 0xFFFF4444.toInt()
                "VERIFICANDO" -> 0xFFFFAA00.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        )

        val situationText = situacaoExibida(situacao)
        if (tvSituacao.text.toString() != situationText) tvSituacao.text = situationText
        tvSituacao.setTextColor(
            when (situacao) {
                "SEGURO" -> 0xFF44DD44.toInt()
                "AGUARDE" -> 0xFFFF4444.toInt()
                "VERIFICANDO" -> 0xFFFFAA00.toInt()
                else -> 0xFF888888.toInt()
            }
        )

        val semInfo = if (semCor != "NENHUM") " | ${tr("traffic_light")}: ${config.nomeCorSemaforo(semCor).replaceFirstChar { it.uppercase() }}" else ""
        tvDetectionCount.text = "${tr("vehicles")}: $nVeiculos$semInfo"
        val alertText = "${tr("current_situation")}: $situationText"
        if (tvAlertStatus.text.toString() != alertText) tvAlertStatus.text = alertText

        // FPS para câmera local
        if (cameraMode == MODE_LOCAL_CAMERA) {
            frameCount++
            val agora = System.currentTimeMillis()
            if (agora - lastFpsTime >= 1000L) {
                tvFps.text = "$frameCount FPS"
                frameCount = 0
                lastFpsTime = agora
            }
        }
    }

    // ================================================================
    //  PERMISSÕES
    // ================================================================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == USB_CAMERA_PERMISSION_CODE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && foreground && cameraMode == MODE_USB_CAMERA) configurarUsb()
            else tvInfo.text = tr("camera_permission_needed")
        } else if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, tr("camera_permission_needed"), Toast.LENGTH_LONG).show()
                tvInfo.text = tr("no_camera_permission")
            }
        } else if (requestCode == ROAD_MICROPHONE_PERMISSION_CODE) {
            resumeRoadMonitoring()
        } else if (requestCode == MICROPHONE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceCommand()
            } else {
                val msg = tr("voice_permission_needed")
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                voz.forcar("voice_permission", msg)
            }
        }
    }

    // ================================================================
    //  LIFECYCLE
    // ================================================================

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
        benchmarkLogger.stopSession()
        pararProcessamentoExterno()
        pararUsb()
        roadCoordinator.suspendMonitoring()
        cameraExecutor.execute { roadCoordinator.close() }
        cameraExecutor.shutdown()
        if (::voiceCommands.isInitialized) voiceCommands.destroy()
        voz.destruir()
        if (::detector.isInitialized) detector.fechar()
    }

    override fun onPause() {
        super.onPause()
        crossing.cancel()
        foreground = false
        analysisSession++
        sessionHandler.removeCallbacksAndMessages(null)
        android.view.Choreographer.getInstance().removeFrameCallback(uiFrameCallback)
        if (::orientationSource.isInitialized) { orientationSource.stop(); orientationTracker.reset() }
        pararUsb()
        window.decorView.removeCallbacks(videoWatchdog)
        pendingVoiceStart = false
        roadCoordinator.suspendMonitoring()
        cameraExecutor.execute { roadCoordinator.release() }
        pararProcessamentoExterno()
        if (::stateMachine.isInitialized) stateMachine.entrarEstado(EST_INATIVO)
        isActive = false
        benchmarkLogger.stopSession()
        if (::voiceCommands.isInitialized) voiceCommands.cancel()
        if (::voz.isInitialized) voz.interromper(preserveSafety = false)
        // RTSP sockets must not survive in the background; the user can restart explicitly.
        overlayView.clear()
    }

    private fun resumeRoadMonitoring() {
        if (!foreground || !::roadCoordinator.isInitialized || !btnToggle.isEnabled) return
        if (isActive) return // análise em andamento: FFNet permanece suspensa
        // Parar explícito: não reiniciar automaticamente até um novo comando de início.
        if (config.automaticRoadDetection && !userStopped) {
            if (!roadMicRequested && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                roadMicRequested = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), ROAD_MICROPHONE_PERMISSION_CODE)
                return
            }
            roadCoordinator.start()
            atualizarBotaoToggle()
            if (cameraMode == MODE_USB_CAMERA) configurarUsb()
        } else {
            roadCoordinator.suspendMonitoring()
            cameraExecutor.execute { roadCoordinator.release() }
            overlayView.clear()
        }
    }

    private fun configurarUsb() {
        pararCameraLocal()
        if (usbClient != null || !foreground) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),USB_CAMERA_PERMISSION_CODE)
            return
        }
        iniciarProcessamentoExterno()
        tvInfo.text = RoadText.get(config.appLanguage,"usb_search")
        enableVisualOrientation()
        if (!orientationTracker.calibrated && !orientationTracker.calibrationPending) orientationTracker.requestCalibration()
        usbClient = com.travessia.segura.camera.UsbCameraClient(this,
            onFrame = { frame -> if (cameraMode == MODE_USB_CAMERA && foreground) receberFrameExterno(frame) else frame.bitmap.recycle() },
            onStatus = { message, error -> runOnUiThread {
                if (cameraMode == MODE_USB_CAMERA && foreground) {
                    tvInfo.text = message
                    if (error) invalidarVideoExterno(message)
                }
            } }).also { it.start() }
    }

    private fun pararUsb() {
        usbClient?.close(); usbClient = null
        if (cameraMode == MODE_USB_CAMERA || !orientationSource.running) orientationTracker.available = false
    }

    /** Webcam USB: liga a orientação estimada pela imagem (FOV configurável) e zera a deriva acumulada. */
    private fun enableVisualOrientation() {
        visualYaw.horizontalFovDeg = config.usbHorizontalFovDeg
        visualYaw.reset()
        orientationTracker.setHorizontalFov(config.usbHorizontalFovDeg)
        orientationTracker.available = true
    }

    /** Modo desenvolvedor: ponto do giro na tela (+ rastro) e log SenseWalkYaw para análise posterior. */
    private var lastYawLogMs = 0L
    private val yawDebugTicker = object : Runnable {
        override fun run() {
            if (!foreground) return
            val now = SystemClock.elapsedRealtime()
            val source = if (cameraMode == MODE_USB_CAMERA) "visual" else "sensor"
            if (orientationTracker.available) {
                val rel = orientationTracker.relativeYawAt(now)
                // Etiqueta de para onde a câmera está olhando (sempre na análise; não depende do modo desenvolvedor).
                if (isActive) {
                    val looking = when {
                        rel == null -> null
                        kotlin.math.abs(rel) <= 20f -> CrossingVerification.Direction.FRONT
                        rel >= orientationTracker.lateralMinDeg -> CrossingVerification.Direction.RIGHT
                        rel <= -orientationTracker.lateralMinDeg -> CrossingVerification.Direction.LEFT
                        else -> null
                    }
                    val name = when (looking) {
                        CrossingVerification.Direction.FRONT -> RoadText.get(config.appLanguage, "dir_front")
                        CrossingVerification.Direction.RIGHT -> RoadText.get(config.appLanguage, "dir_right")
                        CrossingVerification.Direction.LEFT -> RoadText.get(config.appLanguage, "dir_left")
                        null -> if (rel == null) "?" else RoadText.get(config.appLanguage, "look_turning")
                    }
                    overlayView.updateLookTag("${RoadText.get(config.appLanguage, "look_prefix")} ${name.uppercase()}" +
                        (rel?.let { " (%.0f°)".format(it) } ?: ""), looking != null && looking == crossing.coverageDirection)
                } else overlayView.updateLookTag("", false)
                val cov = orientationTracker.lastCoverage
                val label = "giro ${rel?.let { "%.0f°".format(it) } ?: "?"} ($source) " +
                    (crossing.coverageDirection?.let { "$it:${cov?.status}" } ?: if (orientationTracker.calibrated) "" else "calibrando")
                if (config.roadDebugOverlay) overlayView.updateYawDebug(rel, cov?.covered == true && isActive, label,
                    orientationTracker.lateralMinDeg, orientationTracker.lateralMaxDeg, 20f)
                if (now - lastYawLogMs >= 300) {
                    lastYawLogMs = now
                    Log.i("SenseWalkYaw", "rel=${rel?.let { "%.1f".format(it) }} src=$source dir=${crossing.coverageDirection} " +
                        "status=${cov?.status} stage=${crossing.stage}" +
                        if (cameraMode == MODE_USB_CAMERA) " step=${visualYaw.lastStep}" else "")
                }
            } else if (config.roadDebugOverlay) overlayView.clearYawDebug()
            sessionHandler.postDelayed(this, 100L)
        }
    }

}
