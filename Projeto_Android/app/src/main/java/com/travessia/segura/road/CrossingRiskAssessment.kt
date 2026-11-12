package com.travessia.segura.road

import com.travessia.segura.detection.VeiculoInfo

enum class RiskReason { CONFLICTING_APPROACH, POTENTIAL_TRAFFIC_CONFLICT, CROSSWALK_OCCUPIED, OBSTACLE_AHEAD, MOTION_UNCERTAIN, TRACK_LOST, SIGNAL_UNCERTAIN, STALE_IMAGE }
data class SceneConfidence(val visualCurrent: Boolean, val motionReliable: Boolean, val coverageVerified: Boolean)
data class VehicleMotionAssessment(val trackId: Int, val classification: String, val inCorridor: Boolean,
    val obstacle: Boolean, val conflicting: Boolean)
data class CrossingRiskAssessment(val reasons: Set<RiskReason>, val vehicles: List<VehicleMotionAssessment>,
    val confidence: SceneConfidence) {
    val danger: Boolean get() = reasons.any { it in setOf(RiskReason.CONFLICTING_APPROACH, RiskReason.POTENTIAL_TRAFFIC_CONFLICT, RiskReason.CROSSWALK_OCCUPIED, RiskReason.OBSTACLE_AHEAD) }
    val conclusive: Boolean get() = reasons.isEmpty() && confidence.visualCurrent &&
        confidence.motionReliable && confidence.coverageVerified
}

/** Geometria normalizada do campo visível, NÃO distância, velocidade ou posição dos pés. */
object CrossingRiskAnalyzer {
    fun analyze(vehicles: Collection<VeiculoInfo>, frameWidth: Int, frameHeight: Int,
        crosswalk: com.travessia.segura.detection.RawDetection? = null,
        frameAgeMs: Long = 0, coverageVerified: Boolean = false,
        cameraReliable: Boolean = false, unobservedVehicles: Int = 0): CrossingRiskAssessment {
        val reasons = mutableSetOf<RiskReason>()
        if (frameAgeMs !in 0..1500) reasons += RiskReason.STALE_IMAGE
        if (unobservedVehicles > 0) reasons += RiskReason.TRACK_LOST
        val w = frameWidth.coerceAtLeast(1).toFloat()
        val h = frameHeight.coerceAtLeast(1).toFloat()
        val assessed = vehicles.map { v ->
            val b = v.bbox
            val corridor = b[2] >= w * .25f && b[0] <= w * .75f
            val occupied = crosswalk != null && b[2] >= crosswalk.x1 && b[0] <= crosswalk.x2 &&
                b[3] >= crosswalk.y1 && b[1] <= crosswalk.y2
            val obstacle = corridor && b[3] / h >= .78f && v.bboxW / w >= .23f
            // A câmera lateral não tem o mesmo corredor central da vista frontal.
            // Fora da caixa da faixa não equivale a fora de uma trajetória futura.
            val conflict = v.classif == "APROXIMANDO"
            if (occupied) reasons += RiskReason.CROSSWALK_OCCUPIED
            if (obstacle) reasons += RiskReason.OBSTACLE_AHEAD
            if (conflict) reasons += RiskReason.CONFLICTING_APPROACH
            if (corridor && v.classif == "MOVIMENTO_INCERTO" &&
                v.bboxW / w >= .12f && b[3] / h >= .65f && !obstacle && !occupied)
                reasons += RiskReason.POTENTIAL_TRAFFIC_CONFLICT
            if (v.classif == "MOVIMENTO_INCERTO" || v.classif == "EM_MOVIMENTO")
                reasons += RiskReason.MOTION_UNCERTAIN
            VehicleMotionAssessment(v.tid, v.classif, corridor, obstacle, conflict)
        }
        return CrossingRiskAssessment(reasons, assessed,
            SceneConfidence(frameAgeMs in 0..1500, cameraReliable && RiskReason.MOTION_UNCERTAIN !in reasons,
                coverageVerified))
    }
}
