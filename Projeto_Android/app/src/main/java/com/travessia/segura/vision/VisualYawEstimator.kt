package com.travessia.segura.vision

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Giro horizontal (yaw) ESTIMADO pela própria imagem — para a webcam USB, cujo giro o celular não mede.
 *
 * A cada quadro: perfil horizontal da imagem (média de cada uma de [columns] colunas na faixa central),
 * derivada (independe de brilho) e busca do deslocamento lateral que melhor alinha com o perfil anterior
 * (até [maxShiftFraction] da largura por quadro). Câmera girando para a DIREITA → a cena desliza para a
 * esquerda → yaw positivo. Graus = colunas × FOV horizontal / colunas.
 *
 * Limites (por isso é "estimado"): depende do FOV configurado da webcam; integra pequenos erros (deriva);
 * cena escura/sem textura ou objetos grandes em movimento podem enganar — nesses quadros o passo é
 * rejeitado (confiança baixa) e o yaw fica DESCONHECIDO (null), nunca inventado.
 */
class VisualYawEstimator(
    @Volatile var horizontalFovDeg: Float = 70f,
    private val columns: Int = 160,
    private val maxShiftFraction: Float = .35f,
    private val minConfidence: Float = .12f,
    private val minTexture: Float = 1.5f
) {
    data class Step(val shiftColumns: Int, val deltaDeg: Float, val confidence: Float, val accepted: Boolean)

    var yawDeg = 0f; private set
    /** Último passo foi aceito? Se não, o yaw atual é incerto. */
    var lastAccepted = false; private set
    var lastStep: Step? = null; private set
    private var previous: FloatArray? = null
    /** Perfil da "frente" (primeiro quadro após reset): ao voltar a vê-la, o yaw é reancorado (remove deriva). */
    private var frontProfile: FloatArray? = null
    var reanchored = false; private set
    private var scaled: Bitmap? = null
    private val rowsSample = 48

    @Synchronized fun reset() { yawDeg = 0f; previous = null; frontProfile = null; lastAccepted = false; lastStep = null }

    /** Quadro inteiro (qualquer tamanho). Reduz para [columns] x [rowsSample] e extrai o perfil. */
    @Synchronized fun update(bitmap: Bitmap): Step {
        val s = scaled?.takeIf { !it.isRecycled } ?: Bitmap.createBitmap(columns, rowsSample, Bitmap.Config.ARGB_8888).also { scaled = it }
        android.graphics.Canvas(s).drawBitmap(bitmap, android.graphics.Rect(0, bitmap.height / 5, bitmap.width, bitmap.height * 4 / 5),
            android.graphics.Rect(0, 0, columns, rowsSample), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(columns * rowsSample)
        s.getPixels(px, 0, columns, 0, 0, columns, rowsSample)
        val profile = FloatArray(columns)
        for (x in 0 until columns) {
            var sum = 0f
            for (y in 0 until rowsSample) { val p = px[y * columns + x]; sum += .299f * ((p shr 16) and 255) + .587f * ((p shr 8) and 255) + .114f * (p and 255) }
            profile[x] = sum / rowsSample
        }
        return updateProfile(profile)
    }

    /** Núcleo testável: perfil de luminância por coluna (tamanho [columns]). */
    @Synchronized fun updateProfile(profile: FloatArray): Step {
        val cur = gradient(profile)
        val prev = previous
        previous = cur
        val texture = std(cur)
        if (frontProfile == null && texture >= minTexture) frontProfile = cur
        if (prev == null || texture < minTexture) {
            lastAccepted = false
            return Step(0, 0f, 0f, false).also { lastStep = it }
        }
        val maxShift = (columns * maxShiftFraction).toInt()
        val scores = FloatArray(2 * maxShift + 1)
        for (s in -maxShift..maxShift) {
            var sum = 0f; var n = 0
            for (i in cur.indices) { val j = i + s; if (j in prev.indices) { sum += abs(cur[i] - prev[j]); n++ } }
            scores[s + maxShift] = if (n >= cur.size / 2) sum / n else Float.MAX_VALUE
        }
        val bestIdx = scores.indices.minByOrNull { scores[it] }!!
        val best = scores[bestIdx]
        // Confiança: quanto o melhor alinhamento se destaca dos alinhamentos distantes dele (evita padrões repetidos).
        val far = scores.indices.filter { abs(it - bestIdx) > 3 && scores[it] != Float.MAX_VALUE }.map { scores[it] }
        val reference = if (far.isEmpty()) best else far.sorted()[far.size / 4]
        val confidence = if (reference <= 0f) 0f else ((reference - best) / reference).coerceIn(0f, 1f)
        val shift = bestIdx - maxShift
        val accepted = confidence >= minConfidence
        val delta = shift * horizontalFovDeg / columns
        if (accepted) yawDeg += delta
        lastAccepted = accepted
        // Fechamento de laço: a vista da frente reaparece -> yaw absoluto em relação a ela (corrige deriva).
        reanchored = false
        val ref = frontProfile
        if (accepted && ref != null && ref !== cur && abs(yawDeg) <= horizontalFovDeg) {
            val (refShift, refConf) = bestShift(cur, ref, maxShift)
            val anchored = refShift * horizontalFovDeg / columns
            if (refConf >= .35f && abs(anchored - yawDeg) <= 50f) { yawDeg = anchored; reanchored = true }
        }
        return Step(shift, delta, confidence, accepted).also { lastStep = it }
    }

    private fun bestShift(cur: FloatArray, prev: FloatArray, maxShift: Int): Pair<Int, Float> {
        val scores = FloatArray(2 * maxShift + 1)
        for (s in -maxShift..maxShift) {
            var sum = 0f; var n = 0
            for (i in cur.indices) { val j = i + s; if (j in prev.indices) { sum += abs(cur[i] - prev[j]); n++ } }
            scores[s + maxShift] = if (n >= cur.size / 2) sum / n else Float.MAX_VALUE
        }
        val bestIdx = scores.indices.minByOrNull { scores[it] }!!
        val best = scores[bestIdx]
        val far = scores.indices.filter { abs(it - bestIdx) > 3 && scores[it] != Float.MAX_VALUE }.map { scores[it] }
        val reference = if (far.isEmpty()) best else far.sorted()[far.size / 4]
        return (bestIdx - maxShift) to (if (reference <= 0f) 0f else ((reference - best) / reference).coerceIn(0f, 1f))
    }

    private fun gradient(p: FloatArray) = FloatArray(p.size) { i -> if (i == 0) 0f else p[i] - p[i - 1] }
    private fun std(a: FloatArray): Float { val m = a.average().toFloat(); return sqrt(a.map { (it - m) * (it - m) }.average().toFloat()) }
}
