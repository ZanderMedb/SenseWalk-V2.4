package com.travessia.segura.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.travessia.segura.R
import com.travessia.segura.config.AppConfig
import com.travessia.segura.location.LocationService
import com.travessia.segura.navigation.DestinationPlace
import com.travessia.segura.navigation.GeoPoint
import com.travessia.segura.navigation.GraphHopperRouteRepository
import com.travessia.segura.navigation.LocationSnapshot
import com.travessia.segura.navigation.NavigationManager
import com.travessia.segura.navigation.PedestrianRoute
import com.travessia.segura.navigation.PlaceSearchRepository
import com.travessia.segura.voice.VoiceCommandController
import com.travessia.segura.voice.VoiceNavigationManager
import com.travessia.segura.voice.VoiceSystem
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Tela acessível de seleção de destino e acompanhamento de rota em primeiro plano. */
class NavigationActivity : AppCompatActivity(), NavigationManager.Listener {
    companion object {
        private const val LOCATION_PERMISSION_CODE = 310
        private const val MICROPHONE_PERMISSION_CODE = 311
    }

    private val scope = MainScope()
    private lateinit var config: AppConfig
    private lateinit var voice: VoiceSystem
    private lateinit var locationService: LocationService
    private lateinit var navigationManager: NavigationManager
    private lateinit var voiceInput: VoiceCommandController
    private val places = PlaceSearchRepository()
    private val routes = GraphHopperRouteRepository()

    private lateinit var destinationInput: EditText
    private lateinit var locationStatus: TextView
    private lateinit var routeSummary: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var searchButton: Button
    private var selectedDestination: DestinationPlace? = null
    private var currentLocation: LocationSnapshot? = null
    private var temporaryLocationStarted = false
    private var navigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)
        config = AppConfig(this)
        voice = VoiceSystem(this, config)
        locationService = LocationService(this, config)
        navigationManager = NavigationManager(
            locationService = locationService,
            voice = VoiceNavigationManager(config, voice),
            listener = this,
            routeRepository = routes
        )
        destinationInput = findViewById(R.id.etDestination)
        locationStatus = findViewById(R.id.tvLocationStatus)
        routeSummary = findViewById(R.id.tvRouteSummary)
        resultsContainer = findViewById(R.id.placeResultsContainer)
        searchButton = findViewById(R.id.btnSearchDestination)

        findViewById<Button>(R.id.btnSearchDestination).setOnClickListener { searchPlaces() }
        findViewById<Button>(R.id.btnSpeakDestination).setOnClickListener { listenForDestination() }
        findViewById<Button>(R.id.btnCalculateRoute).setOnClickListener { calculateRoute() }
        findViewById<Button>(R.id.btnStartNavigation).setOnClickListener { startNavigation() }
        findViewById<Button>(R.id.btnPauseNavigation).setOnClickListener { navigationManager.pause(); navigating = false }
        findViewById<Button>(R.id.btnEndNavigation).setOnClickListener { navigationManager.cancel(); navigating = false }
        findViewById<Button>(R.id.btnRepeatInstruction).setOnClickListener { navigationManager.repeatCurrentInstruction() }

        voiceInput = VoiceCommandController(
            context = this,
            languageProvider = { config.appLanguage },
            listener = object : VoiceCommandController.Listener {
                override fun onListeningChanged(isListening: Boolean) {
                    findViewById<Button>(R.id.btnSpeakDestination).text = if (isListening) "Ouvindo..." else "Dizer destino"
                }

                override fun onCommand(command: VoiceCommandController.Command, rawText: String) {
                    if (rawText.isNotBlank()) {
                        destinationInput.setText(cleanVoiceDestination(rawText))
                        searchPlaces()
                    }
                }

                override fun onRecognitionError(errorCode: Int) {
                    locationStatus.text = "Não foi possível ouvir o destino. Tente novamente."
                }
            }
        )
        requestLocationIfNeeded()
        if (!routes.isConfigured()) {
            routeSummary.text = "Roteamento pendente: configure GRAPHHOPPER_API_KEY no local.properties. A busca de destinos continua disponível."
        }
    }

    private fun requestLocationIfNeeded() {
        if (locationService.hasLocationPermission()) {
            startTemporaryLocation()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_CODE
            )
        }
    }

    private fun startTemporaryLocation() {
        if (temporaryLocationStarted || navigating) return
        temporaryLocationStarted = true
        locationService.start({ snapshot ->
            currentLocation = snapshot
            locationStatus.text = "GPS disponível. Precisão aproximada: ${snapshot.accuracyMeters.toInt()} metros."
        }) { error ->
            temporaryLocationStarted = false
            locationStatus.text = "GPS indisponível: $error"
        }
    }

    private fun searchPlaces() {
        val query = destinationInput.text.toString()
        if (query.trim().length < 3) {
            routeSummary.text = "Informe um destino com pelo menos três caracteres."
            return
        }
        searchButton.isEnabled = false
        routeSummary.text = "Pesquisando lugares no OpenStreetMap..."
        scope.launch {
            places.search(query).onSuccess(::showPlaces).onFailure {
                routeSummary.text = "Não foi possível pesquisar o destino: ${it.message}"
            }
            searchButton.isEnabled = true
        }
    }

    private fun showPlaces(found: List<DestinationPlace>) {
        resultsContainer.removeAllViews()
        if (found.isEmpty()) {
            routeSummary.text = "Nenhum lugar encontrado. Tente informar endereço, cidade ou nome mais completo."
            return
        }
        routeSummary.text = "Selecione um dos lugares encontrados."
        found.forEach { place ->
            resultsContainer.addView(Button(this).apply {
                text = place.displayName
                isAllCaps = false
                contentDescription = "Selecionar destino: ${place.displayName}"
                setOnClickListener {
                    selectedDestination = place
                    destinationInput.setText(place.displayName)
                    resultsContainer.removeAllViews()
                    routeSummary.text = "Destino selecionado. Toque em Calcular rota a pé."
                }
            })
        }
    }

    private fun calculateRoute(originOverride: GeoPoint? = null, destinationOverride: DestinationPlace? = null) {
        val origin = originOverride ?: currentLocation?.point
        val destination = destinationOverride ?: selectedDestination
        if (origin == null) {
            routeSummary.text = "Aguardando uma localização GPS utilizável para calcular a rota."
            return
        }
        if (destination == null) {
            routeSummary.text = "Pesquise e selecione um destino antes de calcular a rota."
            return
        }
        if (!routes.isConfigured()) {
            routeSummary.text = "Roteamento não configurado. Adicione GRAPHHOPPER_API_KEY ao local.properties e gere uma nova compilação."
            return
        }
        routeSummary.text = "Calculando rota de pedestre..."
        scope.launch {
            routes.route(origin, destination).onSuccess { route ->
                navigationManager.setRoute(route)
                if (originOverride != null) {
                    VoiceNavigationManager(config, voice).navigation("route_recalculated", "Rota recalculada.")
                }
            }.onFailure {
                routeSummary.text = "Não foi possível calcular a rota: ${it.message}"
            }
        }
    }

    private fun startNavigation() {
        temporaryLocationStarted = false
        navigating = true
        navigationManager.start()
    }

    private fun listenForDestination() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION_CODE)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this) || !voiceInput.startListening()) {
            locationStatus.text = "Reconhecimento de voz não está disponível neste aparelho."
        }
    }

    private fun cleanVoiceDestination(raw: String): String = raw
        .replace(Regex("(?i)^sensewalk[, ]*"), "")
        .replace(Regex("(?i)^(quero ir (até|para)|ir (até|para)|navegar (até|para))\\s+"), "")
        .trim()

    override fun onStatus(message: String) {
        routeSummary.text = message
    }

    override fun onRoute(route: PedestrianRoute) {
        val distance = if (route.distanceMeters >= 1_000) "%.1f km".format(route.distanceMeters / 1_000) else "${route.distanceMeters.toInt()} m"
        val minutes = (route.timeMillis / 60_000).coerceAtLeast(1)
        routeSummary.text = "Rota a pé pronta: $distance, cerca de $minutes minutos. ${route.instructions.firstOrNull()?.text.orEmpty()}"
    }

    override fun onLocation(snapshot: LocationSnapshot) {
        currentLocation = snapshot
        locationStatus.text = "GPS disponível. Precisão aproximada: ${snapshot.accuracyMeters.toInt()} metros."
    }

    override fun onRecalculateRequested(origin: GeoPoint, destination: DestinationPlace) {
        calculateRoute(origin, destination)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_CODE -> {
                if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startTemporaryLocation()
                else locationStatus.text = "Permissão de localização necessária para navegação."
            }
            MICROPHONE_PERMISSION_CODE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listenForDestination()
                else locationStatus.text = "Permissão de microfone necessária para informar o destino por voz."
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (navigating) {
            navigationManager.resumeForeground()
        } else if (locationService.hasLocationPermission()) {
            temporaryLocationStarted = false
            startTemporaryLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        // Sem serviço em primeiro plano, o app não continua rastreando em segundo plano.
        if (navigating) navigationManager.suspendForBackground()
        else locationService.stop()
    }

    override fun onDestroy() {
        locationService.stop()
        voiceInput.destroy()
        voice.destruir()
        scope.cancel()
        super.onDestroy()
    }
}
