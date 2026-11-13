package com.travessia.segura.vision

import kotlin.math.*

/** Robust 2D similarity diagnostic, NOT yaw, depth, or physical directional coverage.
 * Reject translation-only compensation if the distributed background rotates/scales
 * or cannot be represented by one transform (including some parallax cases).
 */
object BackgroundSimilarity {
    data class Match(val x: Float, val y: Float, val dx: Float, val dy: Float)
    enum class Model { TRANSLATION, SIMILARITY, PARALLAX_SUSPECTED, INCONSISTENT }
    data class Fit(val rotationDegrees: Float, val scale: Float, val residual: Float,
        val inlierRatio: Float, val distortion: Float, val parallaxSuspected: Boolean = false) {
        /** Fundo explicado só por translação (compensação atual exata o suficiente). */
        val translationSupported: Boolean get() = inlierRatio >= .65f && residual <= 1f && distortion <= .75f && !parallaxSuspected
        /** Fundo explicado por uma similaridade (giro/escala pequenos) de forma consistente. */
        val similaritySupported: Boolean get() = inlierRatio >= .65f && residual <= 1f && !parallaxSuspected
        val model: Model get() = when {
            parallaxSuspected -> Model.PARALLAX_SUSPECTED
            translationSupported -> Model.TRANSLATION
            similaritySupported -> Model.SIMILARITY
            else -> Model.INCONSISTENT
        }
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
        val inlierRatio = best.size.toFloat()/points.size
        return Fit(Math.toDegrees(atan2(b,a)).toFloat(),hypot(a,b).toFloat(),
            sqrt(best.map { t.error(it).pow(2) }.average()).toFloat(),inlierRatio,
            (hypot(a-1,b)*hypot(width.toDouble(),height.toDouble())/2).toFloat(),
            parallaxSuspected = inlierRatio < .65f && parallax(points, height))
    }

    /**
     * Paralaxe: metade superior (fundo distante) e inferior (chão/objetos próximos) do fundo são,
     * cada uma, coerentes, mas com translações diferentes. Uma única transformação 2D não descreve a
     * cena; a compensação é rejeitada em vez de forçada. Não estima profundidade.
     */
    fun parallax(points: List<Match>, height: Int): Boolean {
        val top = points.filter { it.y < height / 2f }
        val bottom = points.filter { it.y >= height / 2f }
        if (top.size < 3 || bottom.size < 3) return false
        fun layer(ps: List<Match>): Pair<Double, Double>? {
            val mx = ps.map { it.dx.toDouble() }.sorted()[ps.size / 2]
            val my = ps.map { it.dy.toDouble() }.sorted()[ps.size / 2]
            val coherent = ps.count { hypot(it.dx - mx, it.dy - my) <= 1.25 }.toFloat() / ps.size
            return if (coherent >= .7f) mx to my else null
        }
        val a = layer(top) ?: return false
        val b = layer(bottom) ?: return false
        return hypot(a.first - b.first, a.second - b.second) > 2.0
    }
}
