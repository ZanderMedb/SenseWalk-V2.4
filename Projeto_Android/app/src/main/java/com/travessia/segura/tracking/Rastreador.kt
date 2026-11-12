package com.travessia.segura.tracking

import com.travessia.segura.config.AppConfig
import com.travessia.segura.detection.CinematicaResult
import kotlin.math.sqrt

/**
 * Cinemática temporal dos veículos.
 *
 * V2.4:
 * - EMA do centro;
 * - histórico de largura da bbox como indício de incerteza, nunca prova de movimento próprio;
 * - compensação de translação global da câmera recebida do optical flow;
 * - ghosts não alimentam o histórico cinemático.
 */
class Rastreador(private val settings: () -> Settings) {
    data class Settings(val emaAlpha: Float = .45f, val historicoMax: Int = 20,
        val velParadoPxs: Float = 18f, val velAproximandoPxs: Float = 30f,
        val crescimentoBBoxAproximando: Float = .14f, val limiarProximoPct: Float = .30f)
    constructor(config: AppConfig) : this({ Settings(config.emaAlpha,config.historicoMax,
        config.velParadoPxs,config.velAproximandoPxs,config.crescimentoBBoxAproximando,config.limiarProximoPct) })

    private val historicos = mutableMapOf<Int, ArrayDeque<Triple<Double, Double, Double>>>()
    private val historicoLarguras = mutableMapOf<Int, ArrayDeque<Pair<Double, Double>>>()
    private val ultimoVisto = mutableMapOf<Int, Double>()
    private val ultimoCentroObservado = mutableMapOf<Int, Pair<Double, Double>>()
    val bboxLargura = mutableMapOf<Int, Int>()
    private val ema = mutableMapOf<Int, Pair<Double, Double>>()

    private var cameraOffsetX = 0.0
    private var cameraOffsetY = 0.0
    private var cameraReliable = false
    private var cameraMotionLarge = false

    /**
     * Deve ser chamado uma vez por frame, antes de atualizar os veículos.
     * dx/dy representam o deslocamento dominante do fundo do frame anterior ao atual.
     */
    fun novoFrameCamera(dx: Float, dy: Float, confidence: Float, minConfidence: Float) {
        val wasReliable = cameraReliable
        cameraReliable = confidence >= minConfidence && dx.isFinite() && dy.isFinite()
        cameraMotionLarge = kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > 12.0
        if (!cameraReliable || cameraMotionLarge || !wasReliable) {
            historicos.clear(); historicoLarguras.clear(); ema.clear()
        }
        if (cameraReliable) {
            cameraOffsetX += dx.toDouble()
            cameraOffsetY += dy.toDouble()
        }
    }

    fun atualizar(tid: Int, cx: Float, cy: Float, t: Double, bboxW: Int = 0) {
        val config = settings()
        // Nunca misturar amostras anteriores a uma lacuna no vídeo ou timestamps retrocedidos.
        val previous = ultimoVisto[tid]
        if (previous != null && (t <= previous || t - previous > 1.5)) {
            historicos.remove(tid); historicoLarguras.remove(tid); ema.remove(tid)
        }
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
        while (hist.size > config.historicoMax.coerceIn(5,60)) hist.removeFirst()

        if (bboxW > 0) {
            bboxLargura[tid] = bboxW
            val wh = historicoLarguras.getOrPut(tid) { ArrayDeque() }
            wh.add(bboxW.toDouble() to t)
            while (wh.size > config.historicoMax.coerceIn(5,60)) wh.removeFirst()
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
        val config = settings()
        val h = historicos[tid]
        if (h == null || h.size < 3) {
            return CinematicaResult(0f, 0f, 0f, false, false)
        }

        val pts = h.toList()
        val n = minOf(8, pts.size)
        val recentTime = pts.last().third
        val p = pts.takeLast(n).filter { recentTime - it.third <= 2.0 }
        if (p.size < 3) return CinematicaResult(0f,0f,0f,false,false)
        val dt = p.last().third - p.first().third
        if (dt < 0.15 || dt > 2.0 || !cameraReliable || cameraMotionLarge) return CinematicaResult(0f, 0f, 0f, false, false)

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
        val pixelScale = wFrame.coerceAtLeast(1) / 640f
        val emMov = vel > config.velParadoPxs * pixelScale

        val observed = ultimoCentroObservado[tid]
        val noEsq = (observed?.first ?: (wFrame / 2.0)) < wFrame / 2.0
        // Deslocamento vertical e expansão também ocorrem ao caminhar rumo a um carro parado.
        // Não são evidências independentes de movimento próprio do veículo.
        val aproximando = emMov && VehicleMotionRules.independentlyApproaching(
            vx, taxaCrescimentoBBox(tid), noEsq, p.size, cameraReliable, cameraMotionLarge,
            config.velAproximandoPxs * pixelScale)
        return CinematicaResult(vel, vx, vy, emMov, aproximando, valid = true)
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
        val config = settings()
        val c = cinematica(tid, w, h)
        val samples = historicos[tid]
        return when {
            !c.valid || !cameraReliable || cameraMotionLarge || samples == null || samples.size < 5 ||
                samples.last().third - samples.first().third < .15 -> "MOVIMENTO_INCERTO" to c.vel
            c.aproximando -> "APROXIMANDO" to c.vel
            kotlin.math.abs(c.vx) > config.velParadoPxs * w.coerceAtLeast(1) / 640f -> "EM_MOVIMENTO" to c.vel
            c.emMovimento || taxaCrescimentoBBox(tid) >= config.crescimentoBBoxAproximando -> "MOVIMENTO_INCERTO" to c.vel
            else -> "PARADO" to c.vel
        }
    }

    fun ehProximo(tid: Int, wFrame: Int): Boolean {
        val config = settings()
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
        cameraReliable = false
        cameraMotionLarge = false
    }
}
