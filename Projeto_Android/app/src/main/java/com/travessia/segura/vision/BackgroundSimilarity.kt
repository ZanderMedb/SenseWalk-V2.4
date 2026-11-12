package com.travessia.segura.vision

import kotlin.math.*

/** Robust 2D similarity diagnostic, NOT yaw, depth, or physical directional coverage.
 * Reject translation-only compensation if the distributed background rotates/scales
 * or cannot be represented by one transform (including some parallax cases).
 */
object BackgroundSimilarity {
    data class Match(val x: Float, val y: Float, val dx: Float, val dy: Float)
    data class Fit(val rotationDegrees: Float, val scale: Float, val residual: Float,
        val inlierRatio: Float, val distortion: Float) {
        val translationSupported: Boolean get() = inlierRatio >= .65f && residual <= 1f && distortion <= .75f
    }
    private data class Transform(val a: Double, val b: Double, val tx: Double, val ty: Double) {
        fun error(p: Match) = hypot(a*p.x-b*p.y+tx-p.x-p.dx,b*p.x+a*p.y+ty-p.y-p.dy)
    }
    fun fit(points: List<Match>, width: Int, height: Int): Fit? {
        if (points.size < 6) return null
        var best = emptyList<Match>()
        // Small grid (< 60 matches), deterministic two-point RANSAC.
        for (i in points.indices) for (j in i+1 until points.size) {
            val p = points[i]; val q = points[j]
            val x = (q.x-p.x).toDouble(); val y = (q.y-p.y).toDouble()
            val norm = x*x+y*y
            if (norm < 100) continue
            val u = x+q.dx-p.dx; val v = y+q.dy-p.dy
            val a = (x*u+y*v)/norm; val b = (x*v-y*u)/norm
            val t = Transform(a,b,p.x+p.dx-a*p.x+b*p.y,p.y+p.dy-b*p.x-a*p.y)
            val inliers = points.filter { t.error(it) <= 1.25 }
            if (inliers.size > best.size) best = inliers
        }
        if (best.size < 6) return null
        val x = best.map { it.x.toDouble() }.average(); val y = best.map { it.y.toDouble() }.average()
        val u = best.map { (it.x+it.dx).toDouble() }.average(); val v = best.map { (it.y+it.dy).toDouble() }.average()
        var norm = 0.0; var dot = 0.0; var cross = 0.0
        for (p in best) {
            val px = p.x-x; val py = p.y-y; val qx = p.x+p.dx-u; val qy = p.y+p.dy-v
            norm += px*px+py*py; dot += px*qx+py*qy; cross += px*qy-py*qx
        }
        if (norm < 100) return null
        val a = dot/norm; val b = cross/norm
        val t = Transform(a,b,u-a*x+b*y,v-b*x-a*y)
        return Fit(Math.toDegrees(atan2(b,a)).toFloat(),hypot(a,b).toFloat(),
            sqrt(best.map { t.error(it).pow(2) }.average()).toFloat(),best.size.toFloat()/points.size,
            (hypot(a-1,b)*hypot(width.toDouble(),height.toDouble())/2).toFloat())
    }
}
