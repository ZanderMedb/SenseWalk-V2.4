package com.travessia.segura.road

enum class AnalysisState { STOPPED, MONITORING, POSSIBLE_CROSSING, AWAITING_CONFIRMATION, STARTING_ANALYSIS, ACTIVE_ANALYSIS, INCONCLUSIVE, ERROR }

/** Máquina pura. Perda isolada de segmentação nunca encerra alertas do YOLO. */
class AutomaticDetectionManager(
    private val confirmationMs: Long = 1500,
    private val holdMs: Long = 10000,
    private val cooldownMs: Long = 15000
) {
    var state = AnalysisState.STOPPED; private set
    private var candidateSince: Long? = null
    private var absentSince: Long? = null
    private var lastEvidence = 0L
    private var lastSample = -1L
    private var lastExit = Long.MIN_VALUE / 2
    private var askedAt = 0L
    private var declined = false
    fun start() { state = AnalysisState.MONITORING; candidateSince = null; absentSince = null; lastSample = -1; declined = false }
    fun stop() { state = AnalysisState.STOPPED }
    fun error() { state = AnalysisState.ERROR }
    fun observeYolo(hazardOrCrossing: Boolean, now: Long) { if (hazardOrCrossing) lastEvidence = now }
    fun update(result: RoadSegmentationResult, now: Long): AnalysisState {
        if (state == AnalysisState.STOPPED || state == AnalysisState.ERROR) return state
        if (now - result.timestamp !in 0..1500 || result.timestamp <= lastSample) return state
        if (lastSample >= 0 && result.timestamp - lastSample > 1500) candidateSince = null
        lastSample = result.timestamp
        if (state == AnalysisState.STARTING_ANALYSIS) {
            if (now - askedAt >= 10000 || !result.roadDetected) {
                state = AnalysisState.INCONCLUSIVE; declined = true
            } else { state = AnalysisState.ACTIVE_ANALYSIS; lastEvidence = now }
            return state
        }
        if (state == AnalysisState.AWAITING_CONFIRMATION) {
            if (!result.roadDetected || now - askedAt >= 25000) answer(false, now)
            return state
        }
        if (declined) {
            if (result.roadDetected) absentSince = null
            else if (absentSince == null) absentSince = now
            if (absentSince != null && now - absentSince!! >= holdMs) declined = false
            state = if (result.roadDetected) AnalysisState.MONITORING else AnalysisState.INCONCLUSIVE
            return state
        }
        if (state == AnalysisState.ACTIVE_ANALYSIS) {
            // Só retorna após ausência persistente de pista E ausência de evidência do YOLO.
            if (result.roadDetected) absentSince = null
            else if (absentSince == null) absentSince = now
            if (absentSince != null && now - absentSince!! >= holdMs && now - lastEvidence >= holdMs) {
                state = AnalysisState.MONITORING; lastExit = now; candidateSince = null
            }
        } else if (result.possibleRoadBoundary && now - lastExit >= cooldownMs) {
            if (candidateSince == null) candidateSince = now
            state = if (now - candidateSince!! >= confirmationMs) {
                askedAt = now
                AnalysisState.AWAITING_CONFIRMATION
            } else AnalysisState.POSSIBLE_CROSSING
        } else {
            candidateSince = null
            state = if (result.roadDetected) AnalysisState.MONITORING else AnalysisState.INCONCLUSIVE
        }
        return state
    }

    fun answer(accepted: Boolean, now: Long): Boolean {
        if (state != AnalysisState.AWAITING_CONFIRMATION) return false
        val valid = accepted && now - askedAt in 0 until 25000
        candidateSince = null
        if (valid) { state = AnalysisState.STARTING_ANALYSIS; askedAt = now }
        else { state = AnalysisState.MONITORING; declined = true; lastExit = now; absentSince = null }
        return valid
    }
    fun tick(now: Long) {
        if (state == AnalysisState.AWAITING_CONFIRMATION && now - askedAt >= 25000) answer(false,now)
        if (state == AnalysisState.STARTING_ANALYSIS && now - askedAt >= 10000) {
            state = AnalysisState.INCONCLUSIVE; declined = true; candidateSince = null
        }
    }
}
