package com.travessia.segura.navigation

import com.travessia.segura.location.LocationService
import com.travessia.segura.voice.VoiceNavigationManager

/** Sessão de navegação em primeiro plano e interpretação das instruções da rota. */
class NavigationManager(
    private val locationService: LocationService,
    private val voice: VoiceNavigationManager,
    private val listener: Listener,
    private val routeRepository: GraphHopperRouteRepository
) {
    interface Listener {
        fun onStatus(message: String)
        fun onRoute(route: PedestrianRoute)
        fun onLocation(snapshot: LocationSnapshot)
        fun onRecalculateRequested(origin: GeoPoint, destination: DestinationPlace)
    }

    private var route: PedestrianRoute? = null
    private var nextInstruction = 0
    private var paused = false
    private var offRouteReadings = 0

    fun setRoute(value: PedestrianRoute) {
        route = value
        nextInstruction = 0
        offRouteReadings = 0
        NavigationSessionStore.currentRoute = value
        listener.onRoute(value)
    }

    fun start() {
        val activeRoute = route ?: run {
            listener.onStatus("Escolha um destino e calcule uma rota antes de iniciar.")
            return
        }
        paused = false
        NavigationSessionStore.currentRoute = activeRoute
        NavigationSessionStore.navigationRequested = true
        locationService.start(::onLocation) { listener.onStatus("GPS indisponível: $it") }
        listener.onStatus("Navegação iniciada.")
        voice.navigation("navigation_started", "Navegação iniciada. ${activeRoute.instructions.firstOrNull()?.text ?: "Continue em frente."}")
    }

    fun pause() {
        paused = true
        NavigationSessionStore.navigationRequested = false
        locationService.stop()
        listener.onStatus("Navegação pausada.")
        voice.navigation("navigation_paused", "Navegação pausada.")
    }

    fun resume() = start()

    fun cancel() {
        paused = false
        route = null
        nextInstruction = 0
        offRouteReadings = 0
        NavigationSessionStore.clear()
        locationService.stop()
        listener.onStatus("Navegação encerrada.")
        voice.navigation("navigation_cancelled", "Navegação encerrada.")
    }

    fun repeatCurrentInstruction() {
        val current = route?.instructions?.getOrNull(nextInstruction)
        voice.navigation("repeat_instruction", current?.text ?: "Não há instrução de rota disponível.")
    }

    /** Remove atualizações ao perder primeiro plano, sem encerrar a sessão solicitada. */
    fun suspendForBackground() {
        locationService.stop()
    }

    /** Retoma somente uma sessão que o usuário ainda deixou ativa, sem nova fala inicial. */
    fun resumeForeground() {
        if (paused || route == null || !NavigationSessionStore.navigationRequested) return
        locationService.start(::onLocation) { listener.onStatus("GPS indisponível: $it") }
    }

    private fun onLocation(snapshot: LocationSnapshot) {
        listener.onLocation(snapshot)
        val activeRoute = route ?: return
        if (paused) return
        if (snapshot.accuracyMeters > 50f) {
            listener.onStatus("Localização imprecisa. Aguarde uma nova leitura.")
            voice.context("nav_location_imprecise", "Localização imprecisa. Aguarde uma nova leitura.")
            return
        }
        val destinationDistance = GeoMath.distanceMeters(snapshot.point, activeRoute.destination.point)
        if (NavigationProgressPolicy.isNearDestination(destinationDistance)) {
            voice.navigation("destination_near", "Você está se aproximando do destino.")
            listener.onStatus("Próximo ao destino: ${destinationDistance.toInt()} metros.")
        }
        announceInstructions(snapshot, activeRoute)
        detectDeviation(snapshot, activeRoute)
    }

    private fun announceInstructions(snapshot: LocationSnapshot, activeRoute: PedestrianRoute) {
        val instruction = activeRoute.instructions.getOrNull(nextInstruction) ?: return
        if (NavigationProgressPolicy.shouldAnnounceInstruction(GeoMath.distanceMeters(snapshot.point, instruction.point))) {
            voice.navigation("route_instruction_$nextInstruction", instruction.text)
            listener.onStatus(instruction.text)
            nextInstruction += 1
        }
    }

    private fun detectDeviation(snapshot: LocationSnapshot, activeRoute: PedestrianRoute) {
        val distance = GeoMath.distanceToRouteMeters(snapshot.point, activeRoute.geometry)
        offRouteReadings = NavigationProgressPolicy.nextOffRouteReadings(distance, offRouteReadings)
        if (!NavigationProgressPolicy.shouldRecalculate(offRouteReadings)) return
        offRouteReadings = 0
        listener.onStatus("Você pode estar fora da rota. Recalculando.")
        listener.onRecalculateRequested(snapshot.point, activeRoute.destination)
    }
}

/** Estado em memória compartilhado com o coordenador visual enquanto o processo estiver vivo. */
object NavigationSessionStore {
    @Volatile var currentRoute: PedestrianRoute? = null
    @Volatile var navigationRequested: Boolean = false

    fun clear() {
        currentRoute = null
        navigationRequested = false
    }
}

/** Regras puras de progresso da rota, reutilizadas pelo gerenciador e testes JVM. */
object NavigationProgressPolicy {
    fun isNearDestination(distanceMeters: Double): Boolean = distanceMeters <= 20.0
    fun shouldAnnounceInstruction(distanceMeters: Double): Boolean = distanceMeters <= 25.0
    fun nextOffRouteReadings(distanceMeters: Double, previous: Int): Int =
        if (distanceMeters > 35.0) previous + 1 else 0
    fun shouldRecalculate(offRouteReadings: Int): Boolean = offRouteReadings >= 3
}
