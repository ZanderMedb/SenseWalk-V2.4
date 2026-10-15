package com.travessia.segura.tracking

import com.travessia.segura.config.AppConfig
import com.travessia.segura.detection.CinematicaResult
import kotlin.math.sqrt

/**
 * Cinemática temporal dos veículos.
 *
 * V2.4:
 * - EMA do centro;
 * - histórico de largura da bbox para aproximação frontal;
 * - compensação de translação global da câmera recebida do optical flow;
 * - ghosts não alimentam o histórico cinemático.
 */
class Rastreador(private val config: AppConfig) {

    private val historicos = mutableMapOf<Int, ArrayDeque<Triple<Double, Double, Double>>>()
    private val historicoLarguras = mutableMapOf<Int, ArrayDeque<Pair<Double, Double>>>()
    private val ultimoVisto = mutableMapOf<Int, Double>()
    private val ultimoCentroObservado = mutableMapOf<Int, Pair<Double, Double>>()
    val bboxLargura = mutableMapOf<Int, Int>()
    private val ema = mutableMapOf<Int, Pair<Double, Double>>()

    private var cameraOffsetX = 0.0
    private var cameraOffsetY = 0.0

    /**
     * Deve ser chamado uma vez por frame, antes de atualizar os veículos.
     * dx/dy representam o deslocamento dominante do fundo do frame anterior ao atual.
     */
    fun novoFrameCamera(dx: Float, dy: Float, confidence: Float, minConfidence: Float) {
        if (confidence >= minConfidence) {
            cameraOffsetX += dx.toDouble()
            cameraOffsetY += dy.toDouble()
        }
    }

    fun atualizar(tid: Int, cx: Float, cy: Float, t: Double, bboxW: Int = 0) {
        ultimoCentroObservado[tid] = cx.toDouble() to cy.toDouble()

        // Converte a posição observada para um referencial aproximadamente estável,
        // removendo a translação acumulada da câmera.
        val correctedX = cx.toDouble() - cameraOffsetX
        val correctedY = cy.toDouble() - cameraOffsetY

        val (sx, sy) = if (tid in ema) {
            val (ox, oy) = ema[tid]!!
            val alpha = config.emaAlpha.toDouble()
            Pair(
                alpha * correctedX + (1 - alpha) * ox,
                alpha * correctedY + (1 - alpha) * oy
            )
        } else {
            Pair(correctedX, correctedY)
        }

        ema[tid] = Pair(sx, sy)

        val hist = historicos.getOrPut(tid) { ArrayDeque() }
        hist.add(Triple(sx, sy, t))
        while (hist.size > config.historicoMax) hist.removeFirst()

        if (bboxW > 0) {
            bboxLargura[tid] = bboxW
            val wh = historicoLarguras.getOrPut(tid) { ArrayDeque() }
            wh.add(bboxW.toDouble() to t)
            while (wh.size > config.historicoMax) wh.removeFirst()
        }

        ultimoVisto[tid] = t
    }

    fun limpar(agora: Double, timeout: Double = 3.0) {
        val mortos = ultimoVisto.filter { agora - it.value > timeout }.keys.toList()
        for (k in mortos) {
            historicos.remove(k)
            historicoLarguras.remove(k)
            ultimoVisto.remove(k)
            ultimoCentroObservado.remove(k)
            bboxLargura.remove(k)
            ema.remove(k)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun cinematica(tid: Int, wFrame: Int = 640, hFrame: Int = 480): CinematicaResult {
        val h = historicos[tid]
        if (h == null || h.size < 3) {
            return CinematicaResult(0f, 0f, 0f, false, false)
        }

        val pts = h.toList()
        val n = minOf(8, pts.size)
        val p = pts.takeLast(n)
        val dt = p.last().third - p.first().third
        if (dt < 0.05) return CinematicaResult(0f, 0f, 0f, false, false)

        val vxs = mutableListOf<Double>()
        val vys = mutableListOf<Double>()
        val pesos = mutableListOf<Double>()

        for (i in 1 until p.size) {
            val dtPar = p[i].third - p[i - 1].third
            if (dtPar < 0.001) continue
            val vx = (p[i].first - p[i - 1].first) / dtPar
            val vy = (p[i].second - p[i - 1].second) / dtPar
            vxs.add(vx * i)
            vys.add(vy * i)
            pesos.add(i.toDouble())
        }

        if (pesos.isEmpty()) return CinematicaResult(0f, 0f, 0f, false, false)

        val sp = pesos.sum()
        val vx = (vxs.sum() / sp).toFloat()
        val vy = (vys.sum() / sp).toFloat()
        val vel = sqrt((vx * vx + vy * vy).toDouble()).toFloat()
        val emMov = vel > config.velParadoPxs

        val observed = ultimoCentroObservado[tid]
        val noEsq = (observed?.first ?: (wFrame / 2.0)) < wFrame / 2.0
        val aproxY = vy > config.velAproximandoPxs
        val aproxX = (noEsq && vx > config.velAproximandoPxs) ||
                (!noEsq && vx < -config.velAproximandoPxs)

        val crescimento = taxaCrescimentoBBox(tid)
        val larguraAtual = bboxLargura[tid]?.toFloat() ?: 0f
        val tamanhoMinimoOk = wFrame > 0 && larguraAtual / wFrame >= 0.035f
        val aproxEscala = tamanhoMinimoOk && crescimento >= config.crescimentoBBoxAproximando

        val aproximando = aproxY || aproxX || aproxEscala
        return CinematicaResult(vel, vx, vy, emMov, aproximando)
    }

    fun taxaCrescimentoBBox(tid: Int): Float {
        val h = historicoLarguras[tid] ?: return 0f
        if (h.size < 4) return 0f

        val pts = h.toList().takeLast(minOf(7, h.size))
        val first = pts.first()
        val last = pts.last()
        val dt = last.second - first.second
        if (dt < 0.08 || first.first <= 1.0) return 0f

        return (((last.first - first.first) / first.first) / dt).toFloat()
    }

    fun classificar(tid: Int, w: Int = 640, h: Int = 480): Pair<String, Float> {
        val c = cinematica(tid, w, h)
        return when {
            c.aproximando -> "APROXIMANDO" to c.vel
            c.emMovimento -> "EM_MOVIMENTO" to c.vel
            else -> "PARADO" to c.vel
        }
    }

    fun ehProximo(tid: Int, wFrame: Int): Boolean {
        val bw = bboxLargura[tid] ?: return false
        return (bw.toFloat() / wFrame) >= config.limiarProximoPct
    }

    fun historicoOk(tid: Int, minimo: Int = 5): Boolean {
        val h = historicos[tid] ?: return false
        return h.size >= minimo
    }

    fun resetar() {
        historicos.clear()
        historicoLarguras.clear()
        ultimoVisto.clear()
        ultimoCentroObservado.clear()
        bboxLargura.clear()
        ema.clear()
        cameraOffsetX = 0.0
        cameraOffsetY = 0.0
    }
}
