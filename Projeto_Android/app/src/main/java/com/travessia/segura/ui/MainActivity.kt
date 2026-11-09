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
import com.travessia.segura.voice.VoiceSystem
import com.travessia.segura.voice.VoiceCommandController
import com.travessia.segura.voice.VoiceNavigationManager
import com.travessia.segura.context.CrossingAnalysisCoordinator
import com.travessia.segura.context.MonitoringState
import com.travessia.segura.navigation.CrossingMapRepository
import com.travessia.segura.navigation.DestinationPlace
import com.travessia.segura.navigation.GeoPoint
import com.travessia.segura.navigation.GraphHopperRouteRepository
import com.travessia.segura.navigation.LocationSnapshot
import com.travessia.segura.navigation.NavigationManager
import com.travessia.segura.navigation.NavigationSessionStore
import com.travessia.segura.navigation.PedestrianRoute
import com.travessia.segura.location.LocationService
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
        private const val MONITORING_PERMISSION_CODE = 102

        // Modos de câmera
        const val MODE_LOCAL_CAMERA = 0
        const val MODE_X5_CAMERA = 1
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
    private lateinit var btnConfig: Button
    private lateinit var btnSource: Button
    private lateinit var btnVoice: Button
    private lateinit var btnNavigation: Button
    private lateinit var statusBar: View
    private lateinit var bottomPanel: View

    private var externalImageView: ImageView? = null

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
    private val activityScope = MainScope()
    private var crossingCoordinator: CrossingAnalysisCoordinator? = null
    private var foregroundNavigationManager: NavigationManager? = null
    private var foregroundNavigationRoute: PedestrianRoute? = null

    // CameraX (modo local)
    private var frameAnalyzer: FrameAnalyzer? = null

    private var externalProcessor: ExternalFrameProcessor? = null

    // X5 RTSP (experimental). It reuses the external processor, so no second detector exists.
    private lateinit var x5Config: X5CameraConfig
    private var x5Client: X5CameraClient? = null
    private var x5Scope: CoroutineScope? = null
    private var x5FrameChannel: Channel<VideoFrame>? = null
    @Volatile private var lastX5FrameNanos = 0L
    @Volatile private var x5StaleReported = false

    // Estado
    private var isActive = false
    private var cameraMode = MODE_LOCAL_CAMERA
    private var lastVehicleCount = 0
    private var lastSituation = "VERIFICANDO"
    private var autoListenTriggered = false
    private var pendingVoiceStart = false
    private var pendingAutomaticStart = false
    private var analysisStartedAutomatically = false

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
        btnConfig = findViewById(R.id.btnConfig)
        btnSource = findViewById(R.id.btnSource)
        btnVoice = findViewById(R.id.btnVoice)
        btnNavigation = findViewById(R.id.btnNavigation)
        statusBar = findViewById(R.id.statusBar)
        bottomPanel = findViewById(R.id.bottomPanel)

        externalImageView = findViewById(R.id.externalImageView)

        btnToggle.isEnabled = false
        btnToggle.setOnClickListener { toggleAnalysis() }
        btnConfig.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnSource.setOnClickListener { alternarFonte() }
        btnVoice.setOnClickListener { startVoiceCommand() }
        btnNavigation.setOnClickListener {
            startActivity(Intent(this, NavigationActivity::class.java))
        }

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
        crossingCoordinator = CrossingAnalysisCoordinator(
            context = this,
            config = config,
            scope = activityScope,
            repository = CrossingMapRepository(),
            voice = VoiceNavigationManager(config, voz),
            listener = object : CrossingAnalysisCoordinator.Listener {
                override fun onMonitoringState(state: MonitoringState) {
                    runOnUiThread {
                        if (!isActive) tvAlertStatus.text = when (state) {
                            MonitoringState.STOPPED -> "Monitoramento automático desligado"
                            MonitoringState.STATIONARY -> "Monitoramento: usuário parado"
                            MonitoringState.WALKING -> "Monitoramento: caminhando"
                            MonitoringState.POSSIBLE_CROSSING -> "Possível travessia mapeada"
                            MonitoringState.ANALYSING_CROSSING -> "IA analisando travessia"
                        }
                    }
                }

                override fun onLocationStatus(message: String) {
                    runOnUiThread { tvInfo.text = message }
                }

                override fun startCrossingAnalysis() {
                    runOnUiThread {
                        if (!isActive) {
                            if (btnToggle.isEnabled) {
                                analysisStartedAutomatically = true
                                setAnalysisActive(true, automatic = true)
                            } else {
                                pendingAutomaticStart = true
                            }
                        }
                    }
                }

                override fun stopCrossingAnalysis() {
                    runOnUiThread {
                        if (isActive && analysisStartedAutomatically) setAnalysisActive(false, automatic = true)
                    }
                }
            },
            activeRoute = { NavigationSessionStore.currentRoute }
        )
        foregroundNavigationManager = NavigationManager(
            locationService = LocationService(this, config),
            voice = VoiceNavigationManager(config, voz),
            routeRepository = GraphHopperRouteRepository(),
            listener = object : NavigationManager.Listener {
                override fun onStatus(message: String) {
                    runOnUiThread { tvInfo.text = message }
                }

                override fun onRoute(route: PedestrianRoute) {
                    foregroundNavigationRoute = route
                }

                override fun onLocation(snapshot: LocationSnapshot) = Unit

                override fun onRecalculateRequested(origin: GeoPoint, destination: DestinationPlace) {
                    activityScope.launch {
                        GraphHopperRouteRepository().route(origin, destination).onSuccess { route ->
                            foregroundNavigationManager?.setRoute(route)
                            VoiceNavigationManager(config, voz).navigation("route_recalculated", "Rota recalculada.")
                        }.onFailure {
                            runOnUiThread { tvInfo.text = "Não foi possível recalcular a rota: ${it.message}" }
                        }
                    }
                }
            }
        )

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
                        onStatusUpdate = { estado, situacao, nVeiculos, semCor ->
                            runOnUiThread {
                                atualizarUI(estado, situacao, nVeiculos, semCor)
                            }
                        },
                        onOverlayUpdate = { boxes, w, h, rot ->
                            runOnUiThread {
                                overlayView.updateDetections(boxes, w, h, rot)
                            }
                        }
                    )

                    // Preparar FrameAnalyzer para câmera local
                    frameAnalyzer = FrameAnalyzer(
                        config = config,
                        detector = detector,
                        vehicleTracker = vehicleTracker,
                        tracker = rastreador,
                        stateMachine = stateMachine,
                        benchmarkLogger = benchmarkLogger,
                        onStatusUpdate = { estado, situacao, nVeiculos, semCor ->
                            runOnUiThread {
                                atualizarUI(estado, situacao, nVeiculos, semCor)
                            }
                        },
                        onOverlayUpdate = { boxes, w, h, rot ->
                            runOnUiThread {
                                overlayView.updateDetections(boxes, w, h, rot)
                            }
                        }
                    )

                    tvInfo.text = tr("ready_wifi")
                    btnToggle.isEnabled = true

                    configurarFonte()
                    if (pendingVoiceStart) {
                        pendingVoiceStart = false
                        setAnalysisActive(true)
                    }
                    if (pendingAutomaticStart) {
                        pendingAutomaticStart = false
                        analysisStartedAutomatically = true
                        setAnalysisActive(true, automatic = true)
                    }

                } else {
                    tvInfo.text = tr("model_error")
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
                if (isActive) {
                    setAnalysisActive(false)
                } else {
                    voz.forcar("voice_state", tr("voice_already_inactive"))
                }
            }

            VoiceCommandController.Command.STATUS -> falarStatus()
            VoiceCommandController.Command.SOURCE_PHONE -> selecionarFontePorVoz(MODE_LOCAL_CAMERA)
            VoiceCommandController.Command.SOURCE_EXTERNAL -> selecionarFontePorVoz(MODE_X5_CAMERA)

            VoiceCommandController.Command.SETTINGS -> {
                if (isActive) setAnalysisActive(false)
                startActivity(Intent(this, SettingsActivity::class.java))
            }

            VoiceCommandController.Command.HELP -> voz.forcar("voice_help", tr("voice_help"))

            else -> voz.forcar("voice_unknown", tr("voice_unknown"))
        }
    }

    private fun selecionarFontePorVoz(mode: Int) {
        if (isActive) {
            voz.forcar("voice_source", tr("voice_stop_before_source"))
            return
        }

        cameraMode = mode
        atualizarUIFonte()
        configurarFonte()
        val confirmation = if (mode == MODE_LOCAL_CAMERA) {
            tr("voice_source_phone_selected")
        } else {
            tr("voice_source_external_selected")
        }
        voz.forcar("voice_source", confirmation)
    }

    private fun falarStatus() {
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

        cameraMode = if (cameraMode == MODE_LOCAL_CAMERA) MODE_X5_CAMERA else MODE_LOCAL_CAMERA
        atualizarUIFonte()
        configurarFonte()
    }

    private fun atualizarUIFonte() {
        when (cameraMode) {
            MODE_X5_CAMERA -> {
                btnSource.text = "X5"
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
            MODE_X5_CAMERA -> configurarX5()
            MODE_LOCAL_CAMERA -> configurarCameraLocal()
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

        overlayView.visibility = if (config.mostrarOverlay) View.VISIBLE else View.GONE
        statusBar.visibility = if (config.mostrarPainelSuperior) View.VISIBLE else View.GONE
        bottomPanel.visibility = if (config.mostrarPainelInferior) View.VISIBLE else View.GONE
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
        btnNavigation.contentDescription = "Abrir navegação acessível"

        if (!isActive) {
            tvAlertStatus.text = tr("waiting_camera")
            tvEstado.text = tr("state_inactive")
            tvSituacao.text = "---"
            tvDetectionCount.text = "${tr("vehicles")}: 0"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::config.isInitialized) {
            aplicarTextoBase()
            atualizarUIFonte()
            aplicarPreferenciasVisuais()
            if (::vehicleTracker.isInitialized) {
                vehicleTracker.configure(
                    centerDistancePct = config.trackerCenterDistancePct,
                    ghostFrameCount = config.trackerGhostFrames
                )
            }
            resumeNavigationInMain()
            startAutomaticMonitoring()

            // Temporarily disabled during X5 diagnostics; manual voice activation remains available.
            // if (config.voiceCommandsEnabled && config.voiceAutoListen && !autoListenTriggered &&
            //     ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            // ) {
            //     autoListenTriggered = true
            //     window.decorView.postDelayed({ startVoiceCommand() }, 700L)
            // }
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
            "X5 experimental: conecte manualmente ao Wi-Fi '${x5Config.ssid}' (atual: $connectedSsid)."
        } else {
            "X5 experimental: conecte manualmente ao Wi-Fi '${x5Config.ssid}' (sem internet)."
        }
        tvAlertStatus.text = "X5 desligada"

        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        x5Client = X5CameraClient(
            connectivityManager = connectivity,
            config = x5Config,
            onFrame = { frame -> receberFrameX5(frame) },
            onStateChanged = { state, message -> atualizarEstadoX5(state, message) },
            onFpsUpdate = { fps -> runOnUiThread { tvFps.text = "$fps FPS" } }
        )
    }

    private fun receberFrameX5(frame: VideoFrame) {
        val bitmap = if (frame.rotationDegrees == 0) {
            frame.bitmap
        } else {
            val matrix = Matrix().apply { postRotate(frame.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(frame.bitmap, 0, 0, frame.bitmap.width, frame.bitmap.height, matrix, true)
                .also { frame.bitmap.recycle() }
        }
        val normalized = VideoFrame(bitmap, frame.timestampNanos, 0)
        lastX5FrameNanos = normalized.timestampNanos
        x5StaleReported = false
        runOnUiThread {
            if (cameraMode == MODE_X5_CAMERA) externalImageView?.setImageBitmap(bitmap)
        }
        if (isActive && cameraMode == MODE_X5_CAMERA) x5FrameChannel?.trySend(normalized)
    }

    private fun atualizarEstadoX5(state: X5StreamState, message: String) {
        if (cameraMode != MODE_X5_CAMERA) return
        tvInfo.text = message
        when (state) {
            X5StreamState.ERRO -> invalidarVideoExterno("X5 sem sinal")
            X5StreamState.RECONECTANDO -> tvAlertStatus.text = "X5 reconectando"
            X5StreamState.REPRODUZINDO -> tvAlertStatus.text = "X5 experimental em reprodução"
            else -> Unit
        }
    }

    private fun iniciarX5() {
        pararX5()
        lastX5FrameNanos = 0L
        x5StaleReported = false
        x5FrameChannel = Channel(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        x5Scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val channel = x5FrameChannel
        x5Scope?.launch {
            if (channel != null) for (frame in channel) {
                if (!isActive || cameraMode != MODE_X5_CAMERA) continue
                if (X5Protocol.isStale(frame.timestampNanos, SystemClock.elapsedRealtimeNanos(), x5Config.staleFrameMs)) {
                    withContext(Dispatchers.Main) { invalidarVideoExterno("Frame X5 vencido") }
                    continue
                }
                // The bitmap was rotated exactly once before preview and inference.
                externalProcessor?.processarFrame(frame.bitmap, overlayRotation = 0)
            }
        }
        x5Scope?.launch {
            while (isActive && cameraMode == MODE_X5_CAMERA) {
                delay(500L)
                if (lastX5FrameNanos != 0L && X5Protocol.isStale(
                        lastX5FrameNanos, SystemClock.elapsedRealtimeNanos(), x5Config.staleFrameMs
                    )
                ) {
                    if (!x5StaleReported) {
                        x5StaleReported = true
                        Log.w(TAG, "X5 frame became stale after ${x5Config.staleFrameMs}ms")
                        withContext(Dispatchers.Main) { invalidarVideoExterno("X5 sem frames recentes") }
                    }
                }
            }
        }
        x5Client?.start()
    }

    private fun pararX5() {
        x5Client?.stop()
        x5FrameChannel?.close()
        x5FrameChannel = null
        x5Scope?.cancel()
        x5Scope = null
        lastX5FrameNanos = 0L
        x5StaleReported = false
    }

    private fun invalidarVideoExterno(reason: String) {
        if (cameraMode != MODE_X5_CAMERA) return
        overlayView.clear()
        externalProcessor?.resetar()
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
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor, frameAnalyzer!!)

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                Log.i(TAG, "Camera local iniciada")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao iniciar camera: ${e.message}")
                tvInfo.text = "${tr("camera_error")}: ${e.message}"
                if (::voz.isInitialized) voz.forcar("camera_failure", "Câmera indisponível. Não foi possível avaliar o ambiente.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ================================================================
    //  CONTROLE
    // ================================================================

    private fun toggleAnalysis() {
        setAnalysisActive(!isActive, automatic = false)
    }

    private fun setAnalysisActive(active: Boolean, automatic: Boolean = false) {
        if (active == isActive) return
        isActive = active

        if (isActive) {
            analysisStartedAutomatically = automatic
            if (config.benchmarkEnabled) {
                val source = if (cameraMode == MODE_LOCAL_CAMERA) "phone" else "x5_rtsp"
                benchmarkLogger.startSession(source)
            }
            // Começar cada sessão sem IDs/histórico herdados da sessão anterior.
            frameAnalyzer?.resetar()
            externalProcessor?.resetar()
            stateMachine.entrarEstado(EST_RECONHECENDO)
            // A fala de contexto já informou a ativação automática; não a interrompa.
            if (!automatic) voz.forcar("inicio", tr("analysis_start_voice"))
            btnToggle.text = tr("stop")
            btnToggle.contentDescription = tr("accessibility_stop")
            tvAlertStatus.text = tr("analysis_running")
            overlayView.clear()

            when (cameraMode) {
                MODE_X5_CAMERA -> iniciarX5()
                MODE_LOCAL_CAMERA -> tvInfo.text = tr("analyzing")
            }

            Log.i(TAG, "Análise iniciada (modo: $cameraMode)")
        } else {
            val benchmarkResult = benchmarkLogger.stopSession()
            stateMachine.entrarEstado(EST_INATIVO)
            if (!automatic) voz.forcar("cancelado", tr("analysis_stop_voice"))
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
            frameAnalyzer?.resetar()
            externalProcessor?.resetar()
            if (cameraMode == MODE_X5_CAMERA) pararX5()
            if (!automatic) crossingCoordinator?.notifyManualAnalysisStopped()
            analysisStartedAutomatically = false

            Log.i(TAG, "Análise parada")
        }
    }

    // ================================================================
    //  UI
    // ================================================================

    private fun atualizarUI(estado: String, situacao: String, nVeiculos: Int, semCor: String) {
        lastVehicleCount = nVeiculos
        lastSituation = situacao
        tvEstado.text = estadoExibido(estado)

        tvEstado.setTextColor(
            when (situacao) {
                "SEGURO" -> 0xFF44DD44.toInt()
                "AGUARDE" -> 0xFFFF4444.toInt()
                "VERIFICANDO" -> 0xFFFFAA00.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        )

        tvSituacao.text = situacaoExibida(situacao)
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
        tvAlertStatus.text = "${tr("current_situation")}: ${situacaoExibida(situacao)}"

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
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, tr("camera_permission_needed"), Toast.LENGTH_LONG).show()
                tvInfo.text = tr("no_camera_permission")
            }
        } else if (requestCode == MICROPHONE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceCommand()
            } else {
                val msg = tr("voice_permission_needed")
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                voz.forcar("voice_permission", msg)
            }
        } else if (requestCode == MONITORING_PERMISSION_CODE) {
            startAutomaticMonitoring()
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
        cameraExecutor.shutdown()
        if (::voiceCommands.isInitialized) voiceCommands.destroy()
        stopAutomaticMonitoring()
        foregroundNavigationManager?.suspendForBackground()
        activityScope.cancel()
        voz.destruir()
        if (::detector.isInitialized) detector.fechar()
    }

    override fun onPause() {
        super.onPause()
        stopAutomaticMonitoring()
        foregroundNavigationManager?.suspendForBackground()
        if (analysisStartedAutomatically && isActive) setAnalysisActive(false, automatic = true)
        // RTSP sockets must not survive in the background; the user can restart explicitly.
        if (cameraMode == MODE_X5_CAMERA && isActive) setAnalysisActive(false)
    }

    /** Inicia apenas o contexto leve; câmera/YOLO continuam desligados até os critérios se confirmarem. */
    private fun startAutomaticMonitoring() {
        if (!::config.isInitialized || !config.walkingMonitoringEnabled || !config.automaticAnalysisEnabled) return
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (android.os.Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), MONITORING_PERMISSION_CODE)
            return
        }
        crossingCoordinator?.startAutomaticMonitoring()
    }

    private fun stopAutomaticMonitoring() {
        crossingCoordinator?.stopAutomaticMonitoring()
    }

    /** Mantém voz/GPS da rota ativos na tela que também pode iniciar a visão automaticamente. */
    private fun resumeNavigationInMain() {
        val activeRoute = NavigationSessionStore.currentRoute ?: return
        if (!NavigationSessionStore.navigationRequested) return
        if (foregroundNavigationRoute !== activeRoute) {
            foregroundNavigationManager?.setRoute(activeRoute)
            foregroundNavigationRoute = activeRoute
        }
        foregroundNavigationManager?.resumeForeground()
    }
}
