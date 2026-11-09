package com.travessia.segura.context

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.travessia.segura.config.AppConfig
import com.travessia.segura.location.LocationService
import com.travessia.segura.navigation.CrossingMapRepository
import com.travessia.segura.navigation.GeoMath
import com.travessia.segura.navigation.PedestrianRoute
import com.travessia.segura.voice.VoiceNavigationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Conecta o contexto GPS/OSM ao pipeline visual já existente, sem criar outro detector. */
class CrossingAnalysisCoordinator(
    context: Context,
    private val config: AppConfig,
    private val scope: CoroutineScope,
    private val repository: CrossingMapRepository,
    private val voice: VoiceNavigationManager,
    private val listener: Listener,
    private val activeRoute: () -> PedestrianRoute?
) {
    interface Listener {
        fun onMonitoringState(state: MonitoringState)
        fun onLocationStatus(message: String)
        fun startCrossingAnalysis()
        fun stopCrossingAnalysis()
    }

    private val appContext = context.applicationContext
    private val locationService = LocationService(appContext, config)
    private val recognitionClient: ActivityRecognitionClient = ActivityRecognition.getClient(appContext)
    private val recognitionIntent = PendingIntent.getBroadcast(
        appContext,
        401,
        Intent(appContext, ActivityRecognitionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    private var manager: ContextManager? = null
    private var activityJob: Job? = null
    private var timeoutJob: Job? = null
    private var monitoring = false

    fun startAutomaticMonitoring(): Boolean {
        if (monitoring) return true
        if (!config.walkingMonitoringEnabled || !config.automaticAnalysisEnabled) return false
        if (!locationService.hasLocationPermission()) {
            listener.onLocationStatus("GPS indisponível: permita a localização para monitorar travessias.")
            return false
        }
        manager = ContextManager(
            alertDistanceMeters = config.crossingAlertDistanceMeters.toDouble(),
            maximumAccuracyMeters = config.maximumLocationAccuracyMeters,
            cooldownMs = config.automaticAnalysisCooldownSeconds * 1_000L,
            analysisDurationMs = config.automaticAnalysisDurationSeconds * 1_000L,
            exitHysteresisMeters = config.crossingExitHysteresisMeters.toDouble()
        )
        monitoring = true
        dispatch(manager!!.start())
        subscribeActivityRecognition()
        locationService.start(::onLocation, ::onLocationError)
        timeoutJob = scope.launch {
            while (monitoring) {
                delay(1_000L)
                manager?.let { dispatch(it.tick()) }
            }
        }
        listener.onLocationStatus("Monitoramento de caminhada ativo. GPS em primeiro plano.")
        return true
    }

    fun stopAutomaticMonitoring() {
        if (!monitoring) return
        monitoring = false
        timeoutJob?.cancel()
        timeoutJob = null
        activityJob?.cancel()
        activityJob = null
        locationService.stop()
        recognitionClient.removeActivityUpdates(recognitionIntent)
        manager?.let { dispatch(it.stop()) }
        manager = null
    }

    fun notifyManualAnalysisStopped() {
        manager?.let { dispatch(it.analysisStoppedExternally()) }
    }

    private fun subscribeActivityRecognition() {
        val allowed = android.os.Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            listener.onLocationStatus("Reconhecimento de atividade sem permissão; usando movimento do GPS como alternativa.")
            return
        }
        recognitionClient.requestActivityUpdates(5_000L, recognitionIntent)
        activityJob = scope.launch {
            MonitoringActivityEvents.walking.collectLatest { isWalking ->
                manager?.let { dispatch(it.onWalkingChanged(isWalking)) }
            }
        }
    }

    private fun onLocation(snapshot: com.travessia.segura.navigation.LocationSnapshot) {
        if (!monitoring) return
        scope.launch {
            val maps = repository.crossingsNearby(snapshot.point, config.crossingQueryRadiusMeters)
            if (maps.error != null && maps.crossings.isEmpty()) {
                listener.onLocationStatus("Não foi possível consultar travessias mapeadas. Monitoramento continua com GPS.")
            } else if (maps.crossings.isEmpty()) {
                listener.onLocationStatus("Não há travessias mapeadas próximas nesta consulta.")
            }
            val route = activeRoute()
            manager?.let {
                dispatch(it.onLocation(snapshot, maps.crossings) { crossing ->
                    route == null || GeoMath.distanceToRouteMeters(crossing.point, route.geometry) <= 35.0
                })
            }
        }
    }

    private fun onLocationError(message: String) {
        listener.onLocationStatus("GPS indisponível: $message")
    }

    private fun dispatch(actions: List<ContextAction>) {
        for (action in actions) {
            when (action) {
                is ContextAction.StateChanged -> listener.onMonitoringState(action.state)
                ContextAction.AnnouncePossibleCrossing -> voice.context(
                    "possible_crossing",
                    "Possível travessia à frente. Iniciando análise do ambiente."
                )
                ContextAction.StartCrossingAnalysis -> listener.startCrossingAnalysis()
                ContextAction.StopCrossingAnalysis -> {
                    voice.context(
                        "crossing_unconfirmed",
                        "Não foi possível confirmar as condições da travessia."
                    )
                    listener.stopCrossingAnalysis()
                }
                ContextAction.LocationImprecise -> voice.context(
                    "location_imprecise",
                    "Localização imprecisa. Aguarde uma nova leitura."
                )
            }
        }
    }
}
