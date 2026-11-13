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
    /** Escala acumulada do fundo (câmera andando para frente/zoom). Larguras são corrigidas por ela. */
    private var cameraScaleCum = 1.0
    private var cameraReliable = false
    private var cameraMotionLarge = false
    private data class CameraSample(val t: Double, val shift: Double, val logScale: Double, val rotation: Double, val ok: Boolean,
        val dx: Double = 0.0)
    private val cameraSamples = ArrayDeque<CameraSample>()

    /**
     * Deve ser chamado uma vez por frame, antes de atualizar os veículos.
     * dx/dy representam o deslocamento dominante do fundo do frame anterior ao atual.
     */
    fun novoFrameCamera(dx: Float, dy: Float, confidence: Float, minConfidence: Float,
        scale: Float = 1f, rotationDegrees: Float = 0f, parallaxSuspected: Boolean = false, t: Double = -1.0) {
        val wasReliable = cameraReliable
        cameraReliable = confidence >= minConfidence && dx.isFinite() && dy.isFinite() && scale.isFinite() &&
            !parallaxSuspected
        // Giro no plano ou paralaxe invalidam a compensação só translacional: recomeçar o histórico.
        cameraMotionLarge = kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > 12.0 ||
            kotlin.math.abs(rotationDegrees) > 1.5f || kotlin.math.abs(scale - 1f) > .04f
        if (!cameraReliable || cameraMotionLarge || !wasReliable) {
            historicos.clear(); historicoLarguras.clear(); ema.clear()
        }
        if (cameraReliable) {
            cameraOffsetX += dx.toDouble()
            cameraOffsetY += dy.toDouble()
            cameraScaleCum = (cameraScaleCum * scale.toDouble()).coerceIn(.2, 5.0)
        }
        if (t >= 0) {
            cameraSamples.add(CameraSample(t, kotlin.math.hypot(dx.toDouble(), dy.toDouble()),
                kotlin.math.ln(scale.toDouble().coerceAtLeast(1e-3)), rotationDegrees.toDouble(), cameraReliable, dx.toDouble()))
            while (cameraSamples.isNotEmpty() && t - cameraSamples.first().t > 1.2) cameraSamples.removeFirst()
        }
    }

    /**
     * Para caixas pequenas (< 6% do quadro): se, no último ~1 s, a câmera deslocou a imagem >= 10 px e o
     * movimento relativo do veículo tem o MESMO sentido do giro, ele pode ser só compensação incompleta:
     * classificar como incerto (bloqueia conclusões, não gera alerta). Carros grandes/próximos não passam aqui.
     */
    fun residuoDeCompensacao(tid: Int, wFrame: Int, vx: Float): Boolean {
        val boxW = bboxLargura[tid] ?: return false
        if (boxW >= wFrame.coerceAtLeast(1) * .06f || cameraSamples.size < 3) return false
        val span = (cameraSamples.last().t - cameraSamples.first().t).coerceAtLeast(.1)
        val camDx = cameraSamples.sumOf { it.dx }
        if (kotlin.math.abs(camDx) < 10.0) return false
        val relDx = vx * span
        // A fração compensada é desconhecida (subcompensação de 50% gera resíduo do tamanho da estimativa):
        // durante o giro, movimento no MESMO sentido de um veículo pequeno é incerto. Sentido oposto segue valendo.
        return kotlin.math.abs(relDx) > 1e-6 && kotlin.math.sign(relDx) == kotlin.math.sign(camDx)
    }

    /** Escala acumulada do fundo no último ~1 s indica deslocamento da câmera para frente/trás. */
    fun cameraAndando(): Boolean = cameraSamples.size >= 3 && kotlin.math.abs(cameraSamples.sumOf { it.logScale }) >= .02

    /**
     * Câmera aproximadamente parada no último ~1 s (sem translação, giro ou escala do fundo relevantes).
     * Só então expansão/retração da caixa é indício (não prova) de aproximação/afastamento do veículo.
     */
    fun cameraParada(): Boolean {
        if (cameraSamples.size < 4) return false
        if (cameraSamples.last().t - cameraSamples.first().t < .6) return false
        return cameraSamples.all { it.ok && it.shift < 3.0 && kotlin.math.abs(it.rotation) < .6 } &&
            kotlin.math.abs(cameraSamples.sumOf { it.logScale }) < .02
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
            // Largura corrigida pela escala do fundo: andar em direção à cena não vira "crescimento".
            wh.add(bboxW.toDouble() / cameraScaleCum to t)
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
        // Velocidade acima do limiar só conta como movimento quando o DESLOCAMENTO é significativo para o
        // tamanho do próprio veículo (carro pequeno/distante: tremor e erro de compensação não viram movimento).
        val emMov = vel > config.velParadoPxs * pixelScale && significantDisplacement(p, tid, wFrame, lateralOnly = false)

        val observed = ultimoCentroObservado[tid]
        val noEsq = (observed?.first ?: (wFrame / 2.0)) < wFrame / 2.0
        // Deslocamento vertical e expansão também ocorrem ao caminhar rumo a um carro parado.
        // Não são evidências independentes de movimento próprio do veículo.
        val aproximando = emMov && significantDisplacement(p, tid, wFrame, lateralOnly = true) &&
            VehicleMotionRules.independentlyApproaching(
            vx, taxaCrescimentoBBox(tid), noEsq, p.size, cameraReliable, cameraMotionLarge,
            config.velAproximandoPxs * pixelScale)
        return CinematicaResult(vel, vx, vy, emMov, aproximando, valid = true)
    }

    /**
     * Deslocamento líquido na janela >= 0,4 largura da caixa (mínimo 1,5% do quadro) e com direção consistente
     * (>= 70% dos passos no mesmo sentido). Oscilação (vai-e-volta) ou ruído de alguns pixels não passam.
     */
    private fun significantDisplacement(p: List<Triple<Double, Double, Double>>, tid: Int, wFrame: Int, lateralOnly: Boolean): Boolean {
        if (p.size < 3) return false
        val widths = historicoLarguras[tid]?.map { it.first }.orEmpty()
        val boxW = if (widths.isEmpty()) (bboxLargura[tid]?.toDouble() ?: 0.0) else widths.average()
        val needed = maxOf(.4 * boxW, .015 * wFrame.coerceAtLeast(1))
        val dx = p.last().first - p.first().first
        val dy = p.last().second - p.first().second
        val net = if (lateralOnly) kotlin.math.abs(dx) else kotlin.math.hypot(dx, dy)
        if (net < needed) return false
        val steps = (1 until p.size).map { p[it].first - p[it - 1].first }.filter { kotlin.math.abs(it) > 1e-6 }
        if (lateralOnly || kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
            if (steps.isEmpty()) return false
            val agree = steps.count { kotlin.math.sign(it) == kotlin.math.sign(dx) }
            return agree >= .7 * steps.size
        }
        return true
    }

    /** Movimento lateral significativo para o tamanho do veículo (usado na classificação EM_MOVIMENTO). */
    fun movimentoLateralSignificativo(tid: Int, wFrame: Int): Boolean {
        val h = historicos[tid] ?: return false
        val pts = h.toList()
        val recent = pts.last().third
        return significantDisplacement(pts.takeLast(8).filter { recent - it.third <= 2.0 }, tid, wFrame, lateralOnly = true)
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

    fun classificar(tid: Int, w: Int = 640, h: Int = 480): Pair<String, Float> =
        avaliar(tid, w, h).let { it.classification to it.speedPxS }

    /**
     * Classificação qualificada com confiança. Regras:
     * - caixa parada na imagem NÃO basta para PARADO: exige câmera estimada e >= 1 s de histórico;
     * - expansão da caixa só vira POSSIVEL_APROXIMACAO com a câmera parada (largura já corrigida pela
     *   escala do fundo); com a câmera andando/girando é MOVIMENTO_INCERTO;
     * - retração nunca é afastamento seguro: POSSIVEL_AFASTAMENTO continua bloqueando conclusões.
     */
    fun avaliar(tid: Int, w: Int = 640, h: Int = 480): MotionAssessment {
        val config = settings()
        val c = cinematica(tid, w, h)
        val samples = historicos[tid]
        val growth = taxaCrescimentoBBox(tid)
        val span = if (samples == null || samples.isEmpty()) 0.0 else samples.last().third - samples.first().third
        val still = cameraParada()
        val historyConfidence = ((samples?.size ?: 0) / 10f).coerceAtMost(1f) * (span / 1.5).toFloat().coerceIn(0f, 1f)
        val growthThreshold = config.crescimentoBBoxAproximando
        fun result(cls: String, conf: Float, reason: String) = MotionAssessment(cls, conf.coerceIn(0f, 1f), c.vel, growth, reason)
        return when {
            !c.valid || !cameraReliable || cameraMotionLarge || samples == null || samples.size < 5 || span < .15 ->
                result(MotionClass.MOVIMENTO_INCERTO, .2f, "historico_ou_camera_insuficiente")
            // Veículo pequeno/distante "andando" no mesmo sentido do giro da câmera e mais devagar que ela:
            // resíduo de compensação subestimada (o carro acompanha o fundo), não movimento próprio.
            residuoDeCompensacao(tid, w, c.vx) ->
                result(MotionClass.MOVIMENTO_INCERTO, .25f, "residuo_compensacao_camera")
            // Movimento rumo ao centro da imagem: possível conflito, não aproximação física.
            c.aproximando -> result(MotionClass.POTENCIAL_CONFLITO, .4f + .5f * historyConfidence, "lateral_para_o_centro")
            still && growth >= growthThreshold && span >= .5 ->
                result(MotionClass.POSSIVEL_APROXIMACAO, .3f + .4f * historyConfidence, "expansao_com_camera_parada")
            // Câmera andando (escala do fundo variando): expansão radial confunde o deslocamento lateral.
            cameraAndando() -> result(MotionClass.MOVIMENTO_INCERTO, .25f, "camera_andando_escala_do_fundo")
            kotlin.math.abs(c.vx) > config.velParadoPxs * w.coerceAtLeast(1) / 640f && movimentoLateralSignificativo(tid, w) ->
                result(MotionClass.EM_MOVIMENTO, .4f + .5f * historyConfidence, "deslocamento_lateral")
            still && growth <= -growthThreshold && span >= .5 ->
                result(MotionClass.POSSIVEL_AFASTAMENTO, .3f + .4f * historyConfidence, "retracao_com_camera_parada")
            c.emMovimento -> result(MotionClass.MOVIMENTO_INCERTO, .3f, "deslocamento_nao_lateral")
            kotlin.math.abs(growth) >= growthThreshold -> result(MotionClass.MOVIMENTO_INCERTO, .3f, "escala_com_camera_em_movimento")
            span < 1.0 -> result(MotionClass.MOVIMENTO_INCERTO, .3f, "historico_curto_para_parado")
            else -> result(MotionClass.PARADO, (if (still) .5f else .3f) + .4f * historyConfidence,
                if (still) "sem_movimento_aparente_camera_parada" else "sem_movimento_aparente_camera_compensada")
        }
    }

    fun ehProximo(tid: Int, wFrame: Int): Boolean {
        val config = settings()
        val bw = bboxLargura[tid] ?: return false
        return (bw.toFloat() / wFrame) >= config.limiarProximoPct
    }

    fun trajectory(tid: Int, width: Int, height: Int): ImageTrajectory {
        val c = cinematica(tid,width,height)
        return ImageTrajectory(c.vx,c.vy,taxaCrescimentoBBox(tid),c.valid && historicoOk(tid))
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
        cameraScaleCum = 1.0
        cameraSamples.clear()
        cameraReliable = false
        cameraMotionLarge = false
    }
}
