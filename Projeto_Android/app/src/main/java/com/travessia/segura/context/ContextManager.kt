package com.travessia.segura.context

import com.travessia.segura.navigation.CrossingPoint
import com.travessia.segura.navigation.GeoMath
import com.travessia.segura.navigation.LocationSnapshot

/** Estados explícitos do monitoramento leve que antecede a análise visual. */
enum class MonitoringState {
    STOPPED,
    STATIONARY,
    WALKING,
    POSSIBLE_CROSSING,
    ANALYSING_CROSSING
}

sealed class ContextAction {
    data class StateChanged(val state: MonitoringState) : ContextAction()
    data object AnnouncePossibleCrossing : ContextAction()
    data object StartCrossingAnalysis : ContextAction()
    data object StopCrossingAnalysis : ContextAction()
    data object LocationImprecise : ContextAction()
}

/**
 * Máquina de estados sem dependência de Android, testável em JVM.
 * GPS isolado não inicia a câmera: são exigidos caminhada, precisão aceitável,
 * duas leituras compatíveis, proximidade e, quando conhecido, rumo/rota.
 */
class ContextManager(
    private val alertDistanceMeters: Double,
    private val maximumAccuracyMeters: Float,
    private val cooldownMs: Long,
    private val analysisDurationMs: Long,
    private val exitHysteresisMeters: Double = 15.0,
    private val confirmReadings: Int = 2,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    var state: MonitoringState = MonitoringState.STOPPED
        private set

    private var walking = false
    private var previousLocation: LocationSnapshot? = null
    private var candidateReadings = 0
    private var lastActivationMs = Long.MIN_VALUE / 2
    private var analysisStartedAtMs = 0L
    private var lowAccuracyAnnouncedAtMs = Long.MIN_VALUE / 2

    fun start(): List<ContextAction> {
        if (state != MonitoringState.STOPPED) return emptyList()
        candidateReadings = 0
        return changeState(MonitoringState.STATIONARY)
    }

    fun stop(): List<ContextAction> {
        candidateReadings = 0
        previousLocation = null
        return changeState(MonitoringState.STOPPED)
    }

    fun onWalkingChanged(isWalking: Boolean): List<ContextAction> {
        if (state == MonitoringState.STOPPED) return emptyList()
        walking = isWalking
        if (!isWalking && state != MonitoringState.ANALYSING_CROSSING) {
            candidateReadings = 0
            return changeState(MonitoringState.STATIONARY)
        }
        if (isWalking && state == MonitoringState.STATIONARY) return changeState(MonitoringState.WALKING)
        return emptyList()
    }

    fun onLocation(
        location: LocationSnapshot,
        crossings: List<CrossingPoint>,
        isRelatedToActiveRoute: (CrossingPoint) -> Boolean = { true }
    ): List<ContextAction> {
        if (state == MonitoringState.STOPPED) return emptyList()
        val actions = mutableListOf<ContextAction>()
        val nowMs = now()

        if (location.accuracyMeters > maximumAccuracyMeters) {
            candidateReadings = 0
            if (state != MonitoringState.ANALYSING_CROSSING && walking) actions += changeState(MonitoringState.WALKING)
            if (nowMs - lowAccuracyAnnouncedAtMs >= 60_000L) {
                lowAccuracyAnnouncedAtMs = nowMs
                actions += ContextAction.LocationImprecise
            }
            previousLocation = location
            return actions
        }

        // O provedor de atividade pode indisponibilizar-se; velocidade GPS é apenas fallback.
        if (!walking && location.speedMetersPerSecond >= 0.45f) {
            walking = true
            actions += changeState(MonitoringState.WALKING)
        }
        if (!walking || state == MonitoringState.ANALYSING_CROSSING) {
            previousLocation = location
            return actions
        }

        val candidates = crossings
            .asSequence()
            .filter(isRelatedToActiveRoute)
            .filter { headingSupportsApproach(location, it) }
            .toList()
        val candidate = candidates.filter { it.distanceMeters <= alertDistanceMeters }
            .minByOrNull { it.distanceMeters }

        if (candidate == null) {
            val remainsWithinHysteresis = state == MonitoringState.POSSIBLE_CROSSING &&
                candidates.any { it.distanceMeters <= alertDistanceMeters + exitHysteresisMeters }
            if (!remainsWithinHysteresis) {
                candidateReadings = 0
                if (state == MonitoringState.POSSIBLE_CROSSING) actions += changeState(MonitoringState.WALKING)
            }
        } else {
            candidateReadings += 1
            if (state == MonitoringState.WALKING) {
                actions += changeState(MonitoringState.POSSIBLE_CROSSING)
                actions += ContextAction.AnnouncePossibleCrossing
            }
            if (candidateReadings >= confirmReadings && nowMs - lastActivationMs >= cooldownMs) {
                lastActivationMs = nowMs
                analysisStartedAtMs = nowMs
                candidateReadings = 0
                actions += changeState(MonitoringState.ANALYSING_CROSSING)
                actions += ContextAction.StartCrossingAnalysis
            }
        }
        previousLocation = location
        return actions
    }

    fun tick(): List<ContextAction> {
        if (state != MonitoringState.ANALYSING_CROSSING || now() - analysisStartedAtMs < analysisDurationMs) {
            return emptyList()
        }
        return changeState(if (walking) MonitoringState.WALKING else MonitoringState.STATIONARY) +
            ContextAction.StopCrossingAnalysis
    }

    fun analysisStoppedExternally(): List<ContextAction> {
        if (state != MonitoringState.ANALYSING_CROSSING) return emptyList()
        return changeState(if (walking) MonitoringState.WALKING else MonitoringState.STATIONARY)
    }

    private fun headingSupportsApproach(location: LocationSnapshot, crossing: CrossingPoint): Boolean {
        val heading = location.bearingDegrees?.toDouble() ?: previousLocation?.let { previous ->
            if (GeoMath.distanceMeters(previous.point, location.point) >= 3.0) {
                GeoMath.bearingDegrees(previous.point, location.point)
            } else null
        }
        // Sem rumo confiável não é uma autorização: apenas deixa a confirmação depender
        // de leituras repetidas e da proximidade. Com rumo, rejeita ponto claramente atrás.
        return heading == null || GeoMath.angularDifferenceDegrees(
            heading,
            GeoMath.bearingDegrees(location.point, crossing.point)
        ) <= 75.0
    }

    private fun changeState(newState: MonitoringState): List<ContextAction> {
        if (state == newState) return emptyList()
        state = newState
        return listOf(ContextAction.StateChanged(newState))
    }
}
