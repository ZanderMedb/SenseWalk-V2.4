package com.travessia.segura.road

/** Estados detalhados (UI/telemetria). AWAITING_CONFIRMATION/STARTING_ANALYSIS mantidos só por compatibilidade. */
enum class AnalysisState { STOPPED, MONITORING, POSSIBLE_CROSSING, AWAITING_CONFIRMATION, STARTING_ANALYSIS, ACTIVE_ANALYSIS, INCONCLUSIVE, ERROR }

/** Fase principal do MVP: FFNet procura rua -> YOLO analisa -> parado. */
enum class MonitorPhase { WAITING_FOR_ROAD, ANALYZING, STOPPED }

/**
 * Máquina pura do gatilho FFNet. A FFNet apenas indica presença de pista; nunca autoriza travessia.
 * - Uma amostra isolada não inicia análise: exige [minSamples] amostras consecutivas e frescas
 *   cobrindo pelo menos [confirmationMs].
 * - Calçada/borda NÃO é exigida para iniciar.
 * - Durante ANALYZING, resultados FFNet são ignorados (o coordenador nem executa inferência).
 * - Após término normal, só rearma depois de [cooldownMs], para a mesma rua não reiniciar em seguida.
 */
class AutomaticDetectionManager(
    private val confirmationMs: Long = 1500,
    @Suppress("unused") private val holdMs: Long = 10000,
    private val cooldownMs: Long = 15000,
    private val minSamples: Int = 3,
    private val maxAgeMs: Long = 4000
) {
    var state = AnalysisState.STOPPED; private set
    val phase: MonitorPhase get() = when (state) {
        AnalysisState.STOPPED, AnalysisState.ERROR -> MonitorPhase.STOPPED
        AnalysisState.ACTIVE_ANALYSIS -> MonitorPhase.ANALYZING
        else -> MonitorPhase.WAITING_FOR_ROAD
    }
    private var candidateSince: Long? = null
    private var candidateSamples = 0
    private var lastSample = -1L
    private var lastExit = Long.MIN_VALUE / 2

    fun start() { state = AnalysisState.MONITORING; resetCandidate(); lastSample = -1 }
    fun stop() { state = AnalysisState.STOPPED; resetCandidate() }
    fun error() { state = AnalysisState.ERROR; resetCandidate() }

    /** Início manual: YOLO direto, sem depender da FFNet. */
    fun beginAnalysis(): Boolean {
        if (state == AnalysisState.ACTIVE_ANALYSIS) return false
        state = AnalysisState.ACTIVE_ANALYSIS; resetCandidate()
        return true
    }

    /** Encerramento normal: volta a procurar rua após o intervalo de rearme. */
    fun finishAnalysis(now: Long) {
        if (state != AnalysisState.ACTIVE_ANALYSIS) return
        state = AnalysisState.MONITORING; lastExit = now; resetCandidate(); lastSample = -1
    }

    @Suppress("UNUSED_PARAMETER") fun observeYolo(hazardOrCrossing: Boolean, now: Long) = Unit

    fun update(result: RoadSegmentationResult, now: Long): AnalysisState {
        if (phase != MonitorPhase.WAITING_FOR_ROAD) return state
        // Gatilho de presença de rua: máscara de até 4 s ainda vale (inferência FFNet lenta no aparelho);
        // amostras com lacuna > 4 s não somam confirmação.
        if (now - result.timestamp !in 0..maxAgeMs || result.timestamp <= lastSample) return state
        if (lastSample >= 0 && result.timestamp - lastSample > maxAgeMs) resetCandidate()
        lastSample = result.timestamp
        if (!result.roadDetected || now - lastExit < cooldownMs) {
            resetCandidate()
            state = if (result.roadDetected) AnalysisState.MONITORING else AnalysisState.INCONCLUSIVE
            return state
        }
        if (candidateSince == null) candidateSince = result.timestamp
        candidateSamples++
        state = if (candidateSamples >= minSamples && result.timestamp - candidateSince!! >= confirmationMs) {
            resetCandidate(); AnalysisState.ACTIVE_ANALYSIS
        } else AnalysisState.POSSIBLE_CROSSING
        return state
    }

    private fun resetCandidate() { candidateSince = null; candidateSamples = 0 }
}
