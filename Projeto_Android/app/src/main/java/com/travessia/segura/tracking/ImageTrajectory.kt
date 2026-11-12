package com.travessia.segura.tracking

/** Image-plane measurements only. Scale trend is not metric distance/approach. */
data class ImageTrajectory(val vx: Float, val vy: Float, val growthPerSecond: Float, val valid: Boolean) {
    val scaleTrend: String get() = when {
        !valid -> "UNCERTAIN"
        growthPerSecond > .14f -> "EXPANDING"
        growthPerSecond < -.14f -> "SHRINKING"
        else -> "STABLE"
    }
    /** Swept image box over one second; hypothesis of conflict, never a clearance test. */
    fun mayIntersect(box: FloatArray, target: FloatArray, horizonSeconds: Float = 1f): Boolean {
        if (!valid || box.size != 4 || target.size != 4) return false
        var enter = 0f; var exit = horizonSeconds
        for (axis in 0..1) {
            val speed = if (axis == 0) vx else vy
            if (kotlin.math.abs(speed) < .001f) {
                if (box[axis+2] < target[axis] || box[axis] > target[axis+2]) return false
            } else {
                val t0 = (target[axis]-box[axis+2])/speed
                val t1 = (target[axis+2]-box[axis])/speed
                enter = maxOf(enter,minOf(t0,t1)); exit = minOf(exit,maxOf(t0,t1))
                if (enter > exit) return false
            }
        }
        return enter <= exit
    }
}
