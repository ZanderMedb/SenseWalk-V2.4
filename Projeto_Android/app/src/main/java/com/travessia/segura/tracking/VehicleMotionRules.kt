package com.travessia.segura.tracking

/** Expansão e deslocamento vertical, isolados, não provam movimento independente. */
object VehicleMotionRules {
    /** Legacy name: returns an inward IMAGE trajectory candidate, not physical approach.
     * Production classification is POTENCIAL_CONFLITO; never announce approach from this alone.
     */
    fun independentlyApproaching(vx: Float, growthPerSecond: Float, fromLeft: Boolean,
        historySamples: Int, cameraReliable: Boolean, cameraMotionLarge: Boolean,
        lateralThreshold: Float): Boolean {
        // growthPerSecond é registrado para diagnóstico, não é prova de movimento próprio.
        @Suppress("UNUSED_VARIABLE") val observedExpansion = growthPerSecond
        return cameraReliable && !cameraMotionLarge && historySamples >= 5 &&
            (if (fromLeft) vx > lateralThreshold else vx < -lateralThreshold)
    }
}
