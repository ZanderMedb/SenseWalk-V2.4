package com.travessia.segura.tracking

/** Expansão e deslocamento vertical, isolados, não provam movimento independente. */
object VehicleMotionRules {
    fun independentlyApproaching(vx: Float, growthPerSecond: Float, fromLeft: Boolean,
        historySamples: Int, cameraReliable: Boolean, cameraMotionLarge: Boolean,
        lateralThreshold: Float): Boolean {
        // growthPerSecond é registrado para diagnóstico, não é prova de movimento próprio.
        @Suppress("UNUSED_VARIABLE") val observedExpansion = growthPerSecond
        return cameraReliable && !cameraMotionLarge && historySamples >= 5 &&
            (if (fromLeft) vx > lateralThreshold else vx < -lateralThreshold)
    }
}
