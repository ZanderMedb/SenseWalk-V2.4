package com.travessia.segura.road

import com.travessia.segura.detection.RawDetection

enum class CrosswalkStatus { CONFIRMED, UNCERTAIN }
enum class SignalApplicability { PEDESTRIAN_GREEN, PEDESTRIAN_RED, VEHICLE_ONLY, UNCERTAIN, NOT_OBSERVED,
    /** Exige fonte independente. Nunca produzido por mera ausência de detecção YOLO. */
    NOT_APPLICABLE_CONFIRMED }

data class SceneEvidence(val crosswalk: CrosswalkStatus, val crosswalkBox: RawDetection?,
    val signal: SignalApplicability, val timestampMs: Long, val greenObservedAtMs: Long = -1L,
    /** Independent association evidence. Image proximity alone does not establish applicability. */
    val signalAssociationVerified: Boolean = false)

/** Só YOLO confirma a faixa; ausência de detecção nunca comprova ausência de faixa/sinal. */
class SceneEvidenceTracker {
    private var firstCrosswalk = -1L
    private var lastCrosswalk = -1L
    private var crosswalkBox: RawDetection? = null
    private var hits = 0
    private var greenSinceMs = -1L
    private var lastSampleMs = -1L
    val observedGreenSinceMs: Long get() = greenSinceMs

    fun update(detections: List<RawDetection>, nowMs: Long, width: Int): SceneEvidence {
        if (nowMs <= lastSampleMs) {
            reset()
            return SceneEvidence(CrosswalkStatus.UNCERTAIN,null,SignalApplicability.UNCERTAIN,nowMs)
        }
        if (lastSampleMs >= 0 && nowMs - lastSampleMs > 850) reset()
        lastSampleMs = nowMs
        val crossing = detections.filter { it.className == "crosswalk" && it.confidence >= .20f }
            .maxByOrNull { it.confidence }
        if (crossing != null) {
            val previous = crosswalkBox
            val consistent = previous != null && overlap(previous,crossing) >= .25f
            if (lastCrosswalk < 0 || nowMs - lastCrosswalk !in 0..850 || !consistent) {
                firstCrosswalk = nowMs; hits = 0
            }
            if (nowMs > lastCrosswalk) hits++
            lastCrosswalk = nowMs
            crosswalkBox = crossing
        }
        if (nowMs - lastCrosswalk !in 0..850) { firstCrosswalk = -1L; crosswalkBox = null; hits = 0 }
        // Hold can retain a previously confirmed stripe, never mature two brief hits by a timer.
        val confirmed = firstCrosswalk >= 0 && hits >= 2 && lastCrosswalk - firstCrosswalk >= 400 && crosswalkBox != null
        val signalCandidates = detections.filter { it.className.startsWith("pedestrian-light-") }
        // O tipo visual é necessário, mas a correspondência geométrica é só um indício.
        val associated = signalCandidates.filter { det ->
            confirmed && crosswalkBox != null &&
                kotlin.math.abs(det.cx - crosswalkBox!!.cx) <= width * .38f &&
                det.cy < crosswalkBox!!.cy
        }
        val signal = when {
            associated.size > 1 -> SignalApplicability.UNCERTAIN
            associated.singleOrNull()?.className == "pedestrian-light-red" -> SignalApplicability.PEDESTRIAN_RED
            associated.singleOrNull()?.className == "pedestrian-light-green" -> SignalApplicability.PEDESTRIAN_GREEN
            signalCandidates.isNotEmpty() -> SignalApplicability.UNCERTAIN
            detections.any { it.className.startsWith("traffic-light-") } -> SignalApplicability.VEHICLE_ONLY
            else -> SignalApplicability.NOT_OBSERVED
        }
        if (signal == SignalApplicability.PEDESTRIAN_GREEN) {
            if (greenSinceMs < 0) greenSinceMs = nowMs
        } else greenSinceMs = -1L
        return SceneEvidence(if (confirmed) CrosswalkStatus.CONFIRMED else CrosswalkStatus.UNCERTAIN,
            if (confirmed) crosswalkBox else null, signal, nowMs, greenSinceMs)
    }

    private fun overlap(a: RawDetection, b: RawDetection): Float {
        val intersection = (minOf(a.x2,b.x2)-maxOf(a.x1,b.x1)).coerceAtLeast(0f) *
            (minOf(a.y2,b.y2)-maxOf(a.y1,b.y1)).coerceAtLeast(0f)
        return intersection / (a.area+b.area-intersection).coerceAtLeast(1f)
    }
    fun reset() { firstCrosswalk = -1; lastCrosswalk = -1; crosswalkBox = null; hits = 0; greenSinceMs = -1; lastSampleMs = -1 }
}
