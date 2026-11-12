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
import com.travessia.segura.camera.X5CameraClient
import com.travessia.segura.camera.X5CameraConfig
import com.travessia.segura.camera.X5Protocol
import com.travessia.segura.camera.X5StreamState
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

        // Modos de câmera
        const val MODE_LOCAL_CAMERA = 0
        const val MODE_X5_CAMERA = 1
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
    private lateinit var crossingConsent: com.travessia.segura.road.CrossingConsentController
    private lateinit var crossing: CrossingVerification
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
    private var announcedYoloStarted = false
    private val videoWatchdog = object : Runnable {
        override fun run() {
            if (!foreground || !::roadCoordinator.isInitialized) return
            val last = roadCoordinator.lastFrameArrivalMs
            if ((isActive || roadCoordinator.running) && last > 0 && SystemClock.elapsedRealtime() - last > 3000) {
                if (::crossing.isInitialized) crossing.cancel()
                overlayView.clear(); roadCoordinator.reportVideoError()
            }
            window.decorView.postDelayed(this, 1000)
        }
    }

    // CameraX (modo local)
    private var frameAnalyzer: FrameAnalyzer? = null

    private var externalProcessor: ExternalFrameProcessor? = null

    // X5 RTSP (experimental). It reuses the external processor, so no second detector exists.
    private lateinit var x5Config: X5CameraConfig
    private var x5Client: X5CameraClient? = null
    private var usbClient: com.travessia.segura.camera.UsbCameraClient? = null
    private var x5Scope: CoroutineScope? = null
    private var x5FrameChannel: Channel<VideoFrame>? = null
    @Volatile private var lastX5FrameNanos = 0L
    @Volatile private var x5StaleReported = false

    // Estado
    @Volatile private var isActive = false
    private var cameraMode = MODE_LOCAL_CAMERA
    private var lastVehicleCount = 0
    private var lastSituation = "VERIFICANDO"
    private var autoListenTriggered = false
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

        config = AppConfig(this)
        cameraMode = config.cameraSource
        x5Config = X5CameraConfig(this)
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
        btnToggle.setOnClickListener { toggleAnalysis() }
        btnCrossing.setOnClickListener {
            crossing.confirmCameraDirection(SystemClock.elapsedRealtime())
        }
        btnConfig.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnSource.setOnClickListener { alternarFonte() }
        btnVoice.setOnClickListener { startVoiceCommand() }

        tvAlertStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        tvEstado.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        tvSituacao.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        tvInfo.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

        aplicarTextoBase()
        atualizarUIFonte()
        aplicarPreferenciasVisuais()
        tvInfo.text = tr("loading_model")
    }

    private fun initEngine() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        voz = VoiceSystem(this, config)
        crossing = CrossingVerification { stage -> runOnUiThread { updateCrossingStage(stage) } }
        crossingConsent = com.travessia.segura.road.CrossingConsentController(this, voz,
            { config.appLanguage }, { executarComandoVoz(VoiceCommandController.Command.STOP) })
        roadCoordinator = com.travessia.segura.road.InferenceCoordinator(this, config,
            manual = { isActive },
            onYoloState = { enabled ->
                if (::stateMachine.isInitialized &&
                    ((enabled && stateMachine.estado == EST_INATIVO) || (!enabled && stateMachine.estado != EST_INATIVO))) {
                    stateMachine.entrarEstado(if (enabled) EST_RECONHECENDO else EST_INATIVO)
                }
            },
            onDebug = { frame -> runOnUiThread {
                if (foreground && frame.sessionToken == roadCoordinator.resultToken) {
                    val contextOnly = frame.reason == "ffnet_stale" && roadCoordinator.hasRecentYoloResult
                    if ((frame.reason in setOf("stale_frame","ffnet_stale") && !contextOnly) || frame.state == com.travessia.segura.road.AnalysisState.ERROR) {
                        crossing.invalidate(); overlayView.clear()
                    }
                    overlayView.updateRoad(frame)
                    val messageState = when {
                        contextOnly -> "context_only"
                        frame.reason in setOf("stale_frame","ffnet_stale") -> "stale_frame"
                        frame.reason == "video_error" -> "video_error"
                        frame.state == com.travessia.segura.road.AnalysisState.ACTIVE_ANALYSIS && roadCoordinator.successfulYoloFrames == 0L -> "STARTING_ANALYSIS"
                        else -> frame.state.name
                    }
                    val message = com.travessia.segura.road.RoadText.get(config.appLanguage, messageState)
                    val status = message + listOfNotNull(
                        if (frame.result?.roadDetected == true) com.travessia.segura.road.RoadText.get(config.appLanguage, "road") else null,
                        if (frame.result?.sidewalkDetected == true) com.travessia.segura.road.RoadText.get(config.appLanguage, "sidewalk") else null
                    ).joinToString(prefix = " ", separator = " / ")
                    if (tvInfo.text.toString() != status) tvInfo.text = status
                    if (frame.state == com.travessia.segura.road.AnalysisState.AWAITING_CONFIRMATION && !isActive && !crossingConsent.active &&
                        lastRoadState != frame.state) {
                        voiceCommands.cancel()
                        announcedYoloStarted = false
                        crossingConsent.ask { accepted -> cameraExecutor.execute {
                            val started = roadCoordinator.answerCrossing(accepted)
                            if (started) crossing.start(SystemClock.elapsedRealtime())
                            if (accepted && !started) runOnUiThread {
                                if (foreground) voz.forcar("crossing_stale", com.travessia.segura.road.RoadText.get(config.appLanguage,"unheard"))
                            }
                        } }
                    } else if (frame.state != com.travessia.segura.road.AnalysisState.AWAITING_CONFIRMATION && crossingConsent.active && frame.reason !in setOf("stale_frame","ffnet_stale")) {
                        crossingConsent.cancel()
                        cameraExecutor.execute { roadCoordinator.answerCrossing(false) }
                    }
                    if (frame.state in setOf(
                            com.travessia.segura.road.AnalysisState.ERROR,
                            com.travessia.segura.road.AnalysisState.INCONCLUSIVE) && !crossingConsent.active && !contextOnly) {
                        voz.falarEstado("road_status",messageState,message,
                            if (frame.reason == "video_error") VoicePriority.SAFETY else VoicePriority.INFO,
                            settleMs=if (frame.state == com.travessia.segura.road.AnalysisState.ERROR) 0 else 1500)
                    } else if (!crossingConsent.active) {
                        voz.observarEstado("road_status","healthy",1500)
                    }
                    lastRoadState = frame.state
                }
            } }, onInvalidated = { crossing.invalidate(); voz.resetarEstados("crossing_") })

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
                        crossing = crossing
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
                                if (foreground && token != null && roadCoordinator.acceptsResult(token,capturedAt) &&
                                    (config.mostrarOverlay || config.roadDebugOverlay)) overlayView.updateDetections(boxes, w, h, rot)
                            }
                        },
                        coordinator = roadCoordinator,
                        crossing = crossing
                    )

                    tvInfo.text = tr("ready_wifi")
                    btnToggle.isEnabled = true

                    if (foreground) { configurarFonte(); resumeRoadMonitoring() }
                    if (pendingVoiceStart) {
                        pendingVoiceStart = false
                        setAnalysisActive(true)
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
                    runOnUiThread { executarComandoVoz(command) }
                }

                override fun onRecognitionError(errorCode: Int) {
                    runOnUiThread {
                        val quietErrors = setOf(
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_CLIENT
                        )
                        val msg = if (errorCode in quietErrors) tr("voice_unknown") else tr("voice_unavailable")
                        tvInfo.text = msg
                        voz.forcar("voice_error", msg)
                    }
                }
            }
        )
    }

    private fun startVoiceCommand() {
        if (crossingConsent.active) return
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
        if (!voiceCommands.startListening()) {
            val msg = tr("voice_unavailable")
            tvInfo.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun executarComandoVoz(command: VoiceCommandController.Command) {
        when (command) {
            VoiceCommandController.Command.START -> {
                if (!btnToggle.isEnabled) {
                    pendingVoiceStart = true
                    voz.forcar("voice_model", tr("voice_model_loading_start"))
                } else if (isActive) {
                    voz.forcar("voice_state", tr("voice_already_active"))
                } else {
                    setAnalysisActive(true)
                }
            }

            VoiceCommandController.Command.STOP -> {
                pendingVoiceStart = false
                if (isActive) {
                    setAnalysisActive(false)
                } else if (roadCoordinator.running) {
                    toggleAnalysis()
                } else {
                    voz.forcar("voice_state", tr("voice_already_inactive"))
                }
            }

            VoiceCommandController.Command.STATUS -> falarStatus()
            VoiceCommandController.Command.SOURCE_PHONE -> selecionarFontePorVoz(MODE_LOCAL_CAMERA)
            VoiceCommandController.Command.SOURCE_EXTERNAL -> selecionarFontePorVoz(MODE_X5_CAMERA)
            VoiceCommandController.Command.SOURCE_USB -> selecionarFontePorVoz(MODE_USB_CAMERA)
            VoiceCommandController.Command.CONFIRM_DIRECTION -> crossing.confirmCameraDirection(SystemClock.elapsedRealtime())

            VoiceCommandController.Command.SETTINGS -> {
                if (isActive) setAnalysisActive(false)
                startActivity(Intent(this, SettingsActivity::class.java))
            }

            VoiceCommandController.Command.HELP -> voz.forcar("voice_help", tr("voice_help"))

            else -> voz.forcar("voice_unknown", tr("voice_unknown"))
        }
    }

    private fun selecionarFontePorVoz(mode: Int) {
        if (isActive || roadCoordinator.running) {
            voz.forcar("voice_source", tr("voice_stop_before_source"))
            return
        }

        pararUsb(); pararX5(); pararCameraLocal()
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
        if (isActive || roadCoordinator.running) {
            Toast.makeText(this, tr("stop_before_source"), Toast.LENGTH_SHORT).show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(tr("accessibility_source"))
            .setItems(arrayOf(tr("source_local"), "X5 Wi-Fi", "USB / UVC")) { _, selected ->
                pararUsb(); pararX5(); pararCameraLocal()
                cameraMode = selected; config.cameraSource = selected
                resetVision(); crossing.cancel()
                overlayView.clear(); atualizarUIFonte(); configurarFonte()
            }.show()
    }

    private fun atualizarUIFonte() {
        when (cameraMode) {
            MODE_X5_CAMERA, MODE_USB_CAMERA -> {
                btnSource.text = if (cameraMode == MODE_USB_CAMERA) "USB" else "X5"
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
            MODE_X5_CAMERA -> { pararUsb(); configurarX5() }
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
        window.decorView.removeCallbacks(videoWatchdog)
        window.decorView.postDelayed(videoWatchdog, 1000)
        if (::config.isInitialized) {
            aplicarTextoBase()
            atualizarUIFonte()
            aplicarPreferenciasVisuais()
            if (btnToggle.isEnabled && cameraMode == MODE_USB_CAMERA) configurarUsb()
            resumeRoadMonitoring()
            if (::vehicleTracker.isInitialized) {
                vehicleTracker.configure(
                    centerDistancePct = config.trackerCenterDistancePct,
                    ghostFrameCount = config.trackerGhostFrames
                )
            }

            if (config.voiceCommandsEnabled && config.voiceAutoListen && !autoListenTriggered &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                autoListenTriggered = true
                window.decorView.postDelayed({
                    if (foreground && !crossingConsent.active) startVoiceCommand()
                }, 700L)
            }
        }
    }

    // ================================================================
    //  X5 RTSP CAMERA (JPEG/RTP experimental)
    // ================================================================

    private fun configurarX5() {
        pararCameraLocal()
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val connectedSsid = wifiManager.connectionInfo?.ssid?.trim('"').orEmpty()
        tvInfo.text = if (connectedSsid.isNotBlank() && connectedSsid != "<unknown ssid>" && connectedSsid != x5Config.ssid) {
            RoadText.get(config.appLanguage,"x5_setup") + " SSID: '${x5Config.ssid}' ($connectedSsid)."
        } else {
            RoadText.get(config.appLanguage,"x5_setup") + " SSID: '${x5Config.ssid}'."
        }
        tvAlertStatus.text = RoadText.get(config.appLanguage,"x5_off")

        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        x5Client = X5CameraClient(
            connectivityManager = connectivity,
            config = x5Config,
            onFrame = { frame -> if (cameraMode == MODE_X5_CAMERA) receberFrameX5(frame) else frame.bitmap.recycle() },
            onStateChanged = { state, message -> atualizarEstadoX5(state, message) },
            onFpsUpdate = { fps -> runOnUiThread { tvFps.text = "$fps FPS" } }
        )
    }

    private fun receberFrameX5(frame: VideoFrame) {
        val generation = roadCoordinator.resultToken
        val bitmap = if (frame.rotationDegrees == 0) {
            frame.bitmap
        } else {
            val matrix = Matrix().apply { postRotate(frame.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(frame.bitmap, 0, 0, frame.bitmap.width, frame.bitmap.height, matrix, true)
                .also { frame.bitmap.recycle() }
        }
        val normalized = VideoFrame(bitmap, frame.timestampNanos, 0)
        val previewBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        lastX5FrameNanos = normalized.timestampNanos
        x5StaleReported = false
        previewDispatcher.offer(PendingPreview(previewBitmap,generation,frame.timestampNanos / 1_000_000))
        val sent = if ((isActive || roadCoordinator.running) && cameraMode != MODE_LOCAL_CAMERA) x5FrameChannel?.trySend(normalized)?.isSuccess == true else false
        if (!sent) bitmap.recycle()
    }

    private fun atualizarEstadoX5(state: X5StreamState, message: String) {
        if (cameraMode != MODE_X5_CAMERA) return
        tvInfo.text = message
        when (state) {
            X5StreamState.ERRO -> invalidarVideoExterno(RoadText.get(config.appLanguage,"x5_signal"))
            X5StreamState.RECONECTANDO -> tvAlertStatus.text = RoadText.get(config.appLanguage,"x5_reconnecting")
            X5StreamState.REPRODUZINDO -> tvAlertStatus.text = RoadText.get(config.appLanguage,"x5_playing")
            else -> Unit
        }
    }

    private fun iniciarX5() {
        iniciarProcessamentoExterno()
        x5Client?.start()
    }

    private fun iniciarProcessamentoExterno() {
        pararX5()
        lastX5FrameNanos = 0L
        x5StaleReported = false
        x5FrameChannel = Channel(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { it.bitmap.recycle() })
        x5Scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val channel = x5FrameChannel
        x5Scope?.launch {
            if (channel != null) for (frame in channel) {
                try {
                if ((!isActive && !roadCoordinator.running) || cameraMode == MODE_LOCAL_CAMERA) continue
                if (X5Protocol.isStale(frame.timestampNanos, SystemClock.elapsedRealtimeNanos(), x5Config.staleFrameMs)) {
                    withContext(Dispatchers.Main) { invalidarVideoExterno("Frame externo vencido") }
                    continue
                }
                // The bitmap was rotated exactly once before preview and inference.
                externalProcessor?.processarFrame(frame.bitmap, overlayRotation = 0, capturedAtMs = frame.timestampNanos / 1_000_000)
                } finally { frame.bitmap.recycle() }
            }
        }
        x5Scope?.launch {
            while ((isActive || roadCoordinator.running) && cameraMode != MODE_LOCAL_CAMERA) {
                delay(500L)
                if (lastX5FrameNanos != 0L && X5Protocol.isStale(
                        lastX5FrameNanos, SystemClock.elapsedRealtimeNanos(), x5Config.staleFrameMs
                    )
                ) {
                    if (!x5StaleReported) {
                        x5StaleReported = true
                        Log.w(TAG, "X5 frame became stale after ${x5Config.staleFrameMs}ms")
                        withContext(Dispatchers.Main) { invalidarVideoExterno(RoadText.get(config.appLanguage,"stale_frame")) }
                    }
                }
            }
        }
    }

    private fun pararX5() {
        previewDispatcher.clear()
        x5Client?.stop()
        x5FrameChannel?.cancel()
        x5FrameChannel = null
        x5Scope?.cancel()
        x5Scope = null
        lastX5FrameNanos = 0L
        x5StaleReported = false
        externalImageView?.setImageBitmap(null)
        externalPreviewBitmap?.recycle(); externalPreviewBitmap = null
    }

    private fun invalidarVideoExterno(reason: String) {
        crossing.cancel()
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
        pararX5()

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

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(config.captureWidth, config.captureWidth * 9 / 16))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor, frameAnalyzer!!)

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
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

    // ================================================================
    //  CONTROLE
    // ================================================================

    private fun toggleAnalysis() {
        crossingConsent.cancel()
        crossing.cancel()
        if (!isActive && roadCoordinator.running) {
            voz.interromper(preserveSafety = false)
            config.automaticRoadDetection = false
            roadCoordinator.suspendMonitoring()
            cameraExecutor.execute { roadCoordinator.release() }
            if (::stateMachine.isInitialized) stateMachine.entrarEstado(EST_INATIVO)
            pararUsb(); pararX5(); overlayView.clear()
            btnToggle.text = tr("start")
            btnToggle.contentDescription = tr("accessibility_start")
            return
        }
        setAnalysisActive(!isActive)
    }

    private fun setAnalysisActive(active: Boolean) {
        crossingConsent.cancel()
        crossing.cancel()
        if (active) cameraExecutor.execute { roadCoordinator.answerCrossing(false) }
        if (!active && ::roadCoordinator.isInitialized && roadCoordinator.running) {
            config.automaticRoadDetection = false
            roadCoordinator.suspendMonitoring()
            cameraExecutor.execute { roadCoordinator.release() }
        }
        if (active == isActive) return
        isActive = active

        if (isActive) {
            if (config.benchmarkEnabled) {
                val source = when(cameraMode) { MODE_LOCAL_CAMERA -> "phone"; MODE_USB_CAMERA -> "usb_uvc"; else -> "x5_rtsp" }
                benchmarkLogger.startSession(source)
            }
            // Começar cada sessão sem IDs/histórico herdados da sessão anterior.
            resetVision()
            stateMachine.entrarEstado(EST_RECONHECENDO)
            crossing.start(SystemClock.elapsedRealtime())
            voz.forcar("inicio", tr("analysis_start_voice"))
            btnToggle.text = tr("stop")
            btnToggle.contentDescription = tr("accessibility_stop")
            tvAlertStatus.text = tr("analysis_running")
            overlayView.clear()

            when (cameraMode) {
                MODE_X5_CAMERA -> iniciarX5()
                MODE_USB_CAMERA -> configurarUsb()
                MODE_LOCAL_CAMERA -> tvInfo.text = tr("analyzing")
            }

            Log.i(TAG, "Análise iniciada (modo: $cameraMode)")
        } else {
            val benchmarkResult = benchmarkLogger.stopSession()
            voz.interromper(preserveSafety = false)
            stateMachine.entrarEstado(EST_INATIVO)
            voz.forcar("cancelado", tr("analysis_stop_voice"))
            btnToggle.text = tr("start")
            btnToggle.contentDescription = tr("accessibility_start")
            tvAlertStatus.text = tr("analysis_paused")
            tvInfo.text = if (benchmarkResult != null) {
                "${tr("paused")} ${tr("benchmark_saved")}: ${benchmarkResult.sessionName}"
            } else {
                tr("paused")
            }
            tvEstado.text = tr("state_inactive")
            tvSituacao.text = "---"
            tvDetectionCount.text = "${tr("vehicles")}: 0"
            lastVehicleCount = 0
            lastSituation = "VERIFICANDO"
            overlayView.clear()
            resetVision()
            if (cameraMode == MODE_X5_CAMERA && !roadCoordinator.running) pararX5()
            if (cameraMode == MODE_USB_CAMERA && !roadCoordinator.running) pararUsb()

            Log.i(TAG, "Análise parada")
        }
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

    private fun updateCrossingStage(stage: CrossingVerification.Stage) {
        if (!foreground || crossing.stage != stage) return // descartar callbacks superados na UI
        val buttonKey = when (stage) {
            CrossingVerification.Stage.AWAIT_RIGHT -> "confirm_right"
            CrossingVerification.Stage.AWAIT_LEFT -> "confirm_left"
            CrossingVerification.Stage.AWAIT_FRONT -> "confirm_front"
            else -> null
        }
        btnCrossing.visibility = if (buttonKey != null) View.VISIBLE else View.GONE
        if (buttonKey != null) {
            btnCrossing.text = RoadText.get(config.appLanguage, buttonKey)
            btnCrossing.contentDescription = btnCrossing.text
        }
        val key = when (stage) {
            CrossingVerification.Stage.ANALYZING -> "verify_analyzing"
            CrossingVerification.Stage.AWAIT_RIGHT -> "verify_right"
            CrossingVerification.Stage.AWAIT_LEFT -> "verify_left"
            CrossingVerification.Stage.AWAIT_FRONT -> "verify_front"
            CrossingVerification.Stage.FAVORABLE_OBSERVATION -> "verify_favorable"
            CrossingVerification.Stage.DANGER -> "verify_danger"
            CrossingVerification.Stage.INCONCLUSIVE ->
                when {
                    crossing.cannotVerifyDirection -> "verify_direction_unavailable"
                    crossing.cannotAssessWithoutCrosswalk -> "verify_no_crosswalk"
                    else -> "verify_inconclusive"
                }
            else -> null
        }
        if (key != null) {
            val prefix = if (stage == CrossingVerification.Stage.AWAIT_RIGHT && crossing.hasCrosswalk)
                RoadText.get(config.appLanguage,"verify_crosswalk") + " " else ""
            val hand = if (stage == CrossingVerification.Stage.FAVORABLE_OBSERVATION && crossing.handSignalApplicable)
                " " + RoadText.get(config.appLanguage,"verify_hand") else ""
            val text = prefix + RoadText.get(config.appLanguage, key) + hand
            tvInfo.text = text
            voz.falarEstado("crossing_stage", stage.name, text,
                if (stage == CrossingVerification.Stage.DANGER) VoicePriority.SAFETY else VoicePriority.CROSSING)
        } else if (stage != CrossingVerification.Stage.MONITORING) voz.observarEstado("crossing_stage",stage.name)
    }

    private fun atualizarUI(estado: String, situacao: String, nVeiculos: Int, semCor: String) {
        if (!foreground) return
        if (roadCoordinator.running && !isActive && crossing.stage != CrossingVerification.Stage.IDLE &&
            !announcedYoloStarted && roadCoordinator.successfulYoloFrames > 0) {
            announcedYoloStarted = true
            val message = com.travessia.segura.road.RoadText.get(config.appLanguage,"ACTIVE_ANALYSIS")
            tvInfo.text = message
            voz.falarPrioritario("yolo_started",message,com.travessia.segura.voice.VoicePriority.CROSSING)
        }
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
        pararX5()
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
        pararUsb()
        crossingConsent.cancel()
        window.decorView.removeCallbacks(videoWatchdog)
        pendingVoiceStart = false
        roadCoordinator.suspendMonitoring()
        cameraExecutor.execute { roadCoordinator.release() }
        pararX5()
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
        if (config.automaticRoadDetection) {
            if (!roadMicRequested && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                roadMicRequested = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), ROAD_MICROPHONE_PERMISSION_CODE)
                return
            }
            roadCoordinator.start()
            btnToggle.text = tr("stop")
            btnToggle.contentDescription = tr("accessibility_stop")
            if (cameraMode == MODE_X5_CAMERA) iniciarX5()
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
        usbClient = com.travessia.segura.camera.UsbCameraClient(this,
            onFrame = { frame -> if (cameraMode == MODE_USB_CAMERA && foreground) receberFrameX5(frame) else frame.bitmap.recycle() },
            onStatus = { message, error -> runOnUiThread {
                if (cameraMode == MODE_USB_CAMERA && foreground) {
                    tvInfo.text = message
                    if (error) invalidarVideoExterno(message)
                }
            } }).also { it.start() }
    }

    private fun pararUsb() { usbClient?.close(); usbClient = null }

}
