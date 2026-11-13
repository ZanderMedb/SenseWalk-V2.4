package com.travessia.segura.orientation

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot

/** Geometria pura (testável sem Android). */
object OrientationMath {
    /**
     * Rumo horizontal (graus, sentido horário visto de cima) do eixo óptico da câmera TRASEIRA.
     * [r] = matriz 3x3 aparelho->mundo (SensorManager.getRotationMatrixFromVector). O eixo óptico é −Z
     * do aparelho, logo a 3ª coluna negada. Independe de retrato/paisagem. null se a câmera aponta
     * quase para o chão/céu (rumo indefinido).
     */
    fun cameraHeadingDegrees(r: FloatArray): Float? {
        if (r.size < 9) return null
        val x = -r[2]; val y = -r[5]
        if (hypot(x, y) < .35f) return null
        return Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
    }

    fun wrap(deg: Float): Float { var d = deg % 360f; if (d > 180f) d -= 360f; if (d <= -180f) d += 360f; return d }

    /**
     * Campo de visão HORIZONTAL da imagem analisada (graus), com recorte do aspecto de saída.
     * Sensor em paisagem; em retrato a horizontal da imagem corresponde ao lado curto do sensor.
     */
    fun horizontalFovDegrees(focalMm: Float, sensorWmm: Float, sensorHmm: Float, imageW: Int, imageH: Int): Float? {
        if (focalMm <= 0f || sensorWmm <= 0f || sensorHmm <= 0f || imageW <= 0 || imageH <= 0) return null
        val sensorLong = maxOf(sensorWmm, sensorHmm); val sensorShort = minOf(sensorWmm, sensorHmm)
        val imageLong = maxOf(imageW, imageH).toFloat(); val imageShort = minOf(imageW, imageH).toFloat()
        val sensorAspect = sensorShort / sensorLong; val imageAspect = imageShort / imageLong
        // Saída mais "larga" que o sensor (16:9 de 4:3) corta o lado curto; mais "alta" corta o longo.
        val usedShort = if (imageAspect < sensorAspect) sensorLong * imageAspect else sensorShort
        val usedLong = if (imageAspect < sensorAspect) sensorLong else sensorShort / imageAspect
        val horizontalMm = if (imageW >= imageH) usedLong else usedShort
        return Math.toDegrees(2 * atan(horizontalMm / (2.0 * focalMm))).toFloat()
    }
}

/**
 * Cobertura direcional da câmera NATIVA por orientação do aparelho, relativa a uma referência frontal.
 *
 * - [requestCalibration] marca que a próxima janela estável define a frente (início da análise).
 * - Direita/esquerda: rumo relativo dentro de uma janela em torno de ±90° derivada do FOV real, para
 *   que a direção lateral da via fique dentro do quadro; frente: |rumo| <= [frontToleranceDeg].
 * - Exige estabilidade (velocidade angular baixa por [stableMs]) e amostra do sensor próxima do frame.
 * - Sem sensor, sem calibração ou sem amostra recente: nunca "coberto" (inconclusivo).
 * Isto é evidência de ORIENTAÇÃO do celular; não prova ausência de veículos nem serve para webcam USB.
 */
class OrientationCoverageTracker(
    horizontalFovDeg: Float = 60f,
    private val frontToleranceDeg: Float = 20f,
    private val stableMs: Long = 600,
    /** Variação total máxima do rumo dentro da janela de [stableMs] para considerar a câmera "segura". */
    private val maxSpreadDeg: Float = 8f,
    /** Variação total máxima para considerar a câmera PARADA (substitui o fluxo óptico quando ele falha). */
    private val stillSpreadDeg: Float = 2.5f,
    private val maxSampleGapMs: Long = 150,
    private val historyMs: Long = 3000
) {
    enum class Direction { RIGHT, LEFT, FRONT }
    enum class Status { UNAVAILABLE, NOT_CALIBRATED, NO_RECENT_SAMPLE, UNSTABLE, INSUFFICIENT_ROTATION, WRONG_DIRECTION, COVERED }
    data class Coverage(val status: Status, val relativeYawDeg: Float?, val confidence: Float) {
        val covered: Boolean get() = status == Status.COVERED
    }
    private data class Sample(val tMs: Long, val heading: Float?)

    @Volatile var available: Boolean = false
    var referenceHeading: Float? = null; private set
    private var calibrationRequested = false
    private var calibrationRequestedAtSample = -1L
    private val samples = ArrayDeque<Sample>()
    var lateralMinDeg = 0f; private set
    var lateralMaxDeg = 0f; private set

    init { setHorizontalFov(horizontalFovDeg) }

    /**
     * O quadro precisa alcançar a direção de onde vêm os veículos (>= 75° do eixo frontal) com margem de 5°.
     * Ex.: FOV 74° → giro mínimo ~43°. Limites em [35°,135°]. Não exige apontar exatamente a 90°.
     */
    @Synchronized fun setHorizontalFov(fovDeg: Float) {
        val half = (fovDeg.coerceIn(30f, 120f) / 2f - 5f).coerceAtLeast(10f)
        lateralMinDeg = (75f - half).coerceIn(35f, 75f)
        lateralMaxDeg = 135f
    }

    @Synchronized fun onSample(tMs: Long, headingDeg: Float?) {
        if (samples.isNotEmpty() && tMs <= samples.last().tMs) return
        samples.addLast(Sample(tMs, headingDeg))
        while (samples.isNotEmpty() && tMs - samples.first().tMs > historyMs) samples.removeFirst()
        if (calibrationRequested && headingDeg != null) {
            if (calibrationRequestedAtSample < 0) calibrationRequestedAtSample = tMs
            // Frente = primeiro instante estável; se o usuário não ficar parado em 1,5 s, usa a mediana recente
            // (a calibração nunca bloqueia a sequência).
            val fallback = tMs - calibrationRequestedAtSample >= 1500
            if (stableAt(tMs)) { referenceHeading = headingDeg; calibrationRequested = false }
            else if (fallback) {
                val recent = samples.filter { tMs - it.tMs <= 300 }.mapNotNull { it.heading }.sorted()
                if (recent.isNotEmpty()) { referenceHeading = recent[recent.size / 2]; calibrationRequested = false }
            }
        }
    }

    /** Nova sessão / "analisar novamente" / troca de fonte: a frente é redefinida no próximo instante estável. */
    @Synchronized fun requestCalibration() { referenceHeading = null; calibrationRequested = true; calibrationRequestedAtSample = -1 }
    @Synchronized fun reset() { samples.clear(); referenceHeading = null; calibrationRequested = false }
    val calibrated: Boolean get() = referenceHeading != null
    val calibrationPending: Boolean get() = calibrationRequested

    @Synchronized fun relativeYawAt(tMs: Long): Float? {
        val ref = referenceHeading ?: return null
        val s = nearest(tMs) ?: return null
        return s.heading?.let { OrientationMath.wrap(it - ref) }
    }

    /** Última avaliação (para orientar o usuário: girar mais, voltar à frente, segurar firme). */
    @Volatile var lastCoverage: Coverage? = null; private set

    @Synchronized fun evaluate(direction: Direction, frameMs: Long): Coverage =
        evaluateInternal(direction, frameMs).also { lastCoverage = it }

    private fun evaluateInternal(direction: Direction, frameMs: Long): Coverage {
        if (!available) return Coverage(Status.UNAVAILABLE, null, 0f)
        val ref = referenceHeading ?: return Coverage(Status.NOT_CALIBRATED, null, 0f)
        val sample = nearest(frameMs)
        val heading = sample?.heading ?: return Coverage(Status.NO_RECENT_SAMPLE, null, 0f)
        val yaw = OrientationMath.wrap(heading - ref)
        if (!stableAt(sample.tMs)) return Coverage(Status.UNSTABLE, yaw, 0f)
        return when (direction) {
            Direction.FRONT -> if (abs(yaw) <= frontToleranceDeg) Coverage(Status.COVERED, yaw, 1f - abs(yaw) / (frontToleranceDeg * 2))
                else Coverage(Status.INSUFFICIENT_ROTATION, yaw, 0f)
            Direction.RIGHT, Direction.LEFT -> {
                val signed = if (direction == Direction.RIGHT) yaw else -yaw
                when {
                    signed in lateralMinDeg..lateralMaxDeg ->
                        Coverage(Status.COVERED, yaw, (1f - abs(signed - 90f) / 90f).coerceIn(.3f, 1f))
                    // "Lado errado" só se NÃO estiver indo na direção certa: voltando da outra lateral rumo ao alvo
                    // (ex.: da direita para a esquerda) é progresso, não erro.
                    signed <= -lateralMinDeg * .6f && !movingToward(direction, sample.tMs) ->
                        Coverage(Status.WRONG_DIRECTION, yaw, 0f)
                    signed <= -lateralMinDeg * .6f -> Coverage(Status.INSUFFICIENT_ROTATION, yaw, 0f)
                    else -> Coverage(Status.INSUFFICIENT_ROTATION, yaw, 0f)
                }
            }
        }
    }

    /** O rumo andou >= 5° rumo ao alvo no último 1 s? */
    private fun movingToward(direction: Direction, tMs: Long): Boolean {
        val now = samples.lastOrNull { it.tMs <= tMs && it.heading != null } ?: return false
        val before = samples.lastOrNull { it.tMs <= tMs - 1000 && it.heading != null } ?: return false
        val delta = OrientationMath.wrap(now.heading!! - before.heading!!)
        return if (direction == Direction.RIGHT) delta >= 5f else delta <= -5f
    }

    private fun nearest(tMs: Long): Sample? =
        samples.minByOrNull { abs(it.tMs - tMs) }?.takeIf { abs(it.tMs - tMs) <= maxSampleGapMs }

    /** Variação angular pequena em toda a janela [stableMs] terminada em [tMs]. */
    /**
     * Estável = variação TOTAL do rumo na janela <= [maxSpreadDeg]. (A velocidade entre amostras de 20 ms é
     * dominada pelo ruído do sensor: 0,3° de ruído já viram ~15°/s e reprovavam uma mão firme.)
     */
    private fun stableAt(tMs: Long, spreadDeg: Float = maxSpreadDeg): Boolean {
        val window = samples.filter { it.tMs in (tMs - stableMs)..tMs }
        if (window.size < 3 || window.any { it.heading == null }) return false
        if (window.last().tMs - window.first().tMs < stableMs * .8) return false
        val ref = window.first().heading!!
        val rel = window.map { OrientationMath.wrap(it.heading!! - ref) }
        return rel.max() - rel.min() <= spreadDeg
    }

    /**
     * Câmera do celular praticamente parada (giro total <= [stillSpreadDeg] em 0,6 s) no instante do frame.
     * Usado quando o fluxo óptico falha (noite, pouca textura): não apagar o histórico dos veículos à toa.
     * Não detecta translação (andar); só vale com o usuário parado na calçada.
     */
    @Synchronized fun isStillAt(frameMs: Long): Boolean {
        if (!available) return false
        val s = nearest(frameMs) ?: return false
        return stableAt(s.tMs, stillSpreadDeg)
    }
}
