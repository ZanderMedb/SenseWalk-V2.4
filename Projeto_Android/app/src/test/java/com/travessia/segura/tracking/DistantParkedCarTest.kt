package com.travessia.segura.tracking

import com.travessia.segura.detection.VeiculoInfo
import com.travessia.segura.road.CrossingRiskAnalyzer
import com.travessia.segura.road.RiskReason
import org.junit.Assert.*
import org.junit.Test

/**
 * Reprodução sintética do teste no celular: carro parado bem distante (caixa ~20 px num quadro de 854),
 * câmera na mão, compensação do fluxo quantizada (~5 px) e caixa tremendo. Não deve virar movimento/conflito.
 */
class DistantParkedCarTest {
    private val w = 854; private val h = 480

    private fun run(frames: Int, trueMotionPxPerFrame: Float = 0f, boxW: Float = 20f, seed: Long = 7): Pair<Rastreador, List<String>> {
        val rnd = java.util.Random(seed)
        val r = Rastreador { Rastreador.Settings() }
        val memory = CrossingSessionMemory().apply { beginSession(1) }
        var t = 1.0; var worldX = 200f
        val states = mutableListOf<String>()
        for (i in 0 until frames) {
            val camTrue = (rnd.nextGaussian() * 4).toFloat()          // mão tremendo
            val camEstimated = Math.round(camTrue / 5.3f) * 5.3f        // fluxo em passos de ~5 px
            worldX += trueMotionPxPerFrame
            val cx = worldX + camTrue + (rnd.nextGaussian() * 2).toFloat() // jitter da caixa
            r.novoFrameCamera(camEstimated, 0f, .8f, .35f, 1f, 0f, false, t)
            r.atualizar(1, cx, 150f, t, (boxW + rnd.nextGaussian()).toInt().coerceAtLeast(5))
            val a = r.avaliar(1, w, h)
            states += memory.observe(1, "car", floatArrayOf(cx - boxW / 2, 140f, cx + boxW / 2, 160f), a.classification,
                a.confidence, (t * 1000).toLong(), CrossingSessionMemory.Direction.LEFT, w, h, cameraMotionInvalid = false).state
            t += .3
        }
        return r to states
    }

    @Test fun distantParkedCarWithHandheldCameraIsNeverMovingOrConflict() {
        for (seed in 1L..20L) {
            val states = run(30, seed = seed).second
            assertTrue("seed $seed: $states", states.none { it in MotionClass.CONFLICT || it == MotionClass.EM_MOVIMENTO })
        }
    }

    @Test fun genuinelyMovingDistantCarIsStillDetected() {
        // 12 px por frame (~40 px/s) = 2 larguras por segundo, sempre no mesmo sentido.
        val states = run(20, trueMotionPxPerFrame = 12f).second
        assertTrue(states.toString(), states.takeLast(8).any { it in MotionClass.MOVING })
    }

    @Test fun distantSlowConflictBecomesUncertaintyButFastSmallCarStaysAnAlert() {
        val slow = VeiculoInfo("car", .8f, 300f, 150f, MotionClass.POTENCIAL_CONFLITO, 15f, floatArrayOf(290f, 140f, 310f, 160f), 20f, 1)
        val slowRisk = CrossingRiskAnalyzer.analyze(listOf(slow), w, h, cameraReliable = true)
        assertFalse(slowRisk.danger)
        assertTrue(RiskReason.MOTION_UNCERTAIN in slowRisk.reasons)
        assertFalse("incerteza nunca é conclusão livre", slowRisk.conclusive)
        val fast = slow.copy(vel = 200f)
        assertTrue(CrossingRiskAnalyzer.analyze(listOf(fast), w, h, cameraReliable = true).danger)
        val near = slow.copy(bbox = floatArrayOf(250f, 300f, 400f, 420f), bboxW = 150f)
        assertTrue("perto continua alerta", CrossingRiskAnalyzer.analyze(listOf(near), w, h, cameraReliable = true).danger)
    }
    /**
     * Caso mais provável do teste real: usuário gira devagar para a esquerda segurando a webcam e o fluxo
     * SUBESTIMA o giro (só ~50% compensado). O resíduo é consistente, no sentido do giro, e o carro parado
     * distante "anda" junto com o fundo.
     */
    @Test fun underCompensatedSlowPanDoesNotTurnADistantParkedCarIntoMovement() {
        for (seed in 1L..10L) {
            val rnd = java.util.Random(seed)
            val r = Rastreador { Rastreador.Settings() }
            val memory = CrossingSessionMemory().apply { beginSession(1) }
            var t = 1.0; var cx = 600f
            val states = mutableListOf<String>()
            for (i in 0 until 25) {
                val camTrue = 20f + (rnd.nextGaussian() * 2).toFloat() // giro lento contínuo (~66 px/s ≈ 6°/s)
                val camEstimated = camTrue * .5f                          // compensação subestimada
                cx += camTrue + (rnd.nextGaussian() * 1.5).toFloat()
                r.novoFrameCamera(camEstimated, 0f, .8f, .35f, 1f, 0f, false, t)
                r.atualizar(1, cx, 150f, t, 20)
                val a = r.avaliar(1, w, h)
                states += memory.observe(1, "car", floatArrayOf(cx - 10, 140f, cx + 10, 160f), a.classification, a.confidence,
                    (t * 1000).toLong(), CrossingSessionMemory.Direction.LEFT, w, h, cameraMotionInvalid = false).state
                t += .3
            }
            assertTrue("seed $seed: $states", states.none { it in MotionClass.CONFLICT || it == MotionClass.EM_MOVIMENTO })
        }
    }
    @Test fun smallCarMovingAgainstThePanIsStillDetectedDuringTheTurn() {
        val r = Rastreador { Rastreador.Settings() }
        var t = 1.0; var cx = 600f
        var last = ""
        for (i in 0 until 20) {
            val cam = 10f                         // giro (abaixo do limite de 12 px/frame que reinicia o histórico)
            cx += cam - 15f                       // carro anda 15 px/frame no sentido oposto (~2,5 larguras/s)
            r.novoFrameCamera(cam, 0f, .8f, .35f, 1f, 0f, false, t)
            r.atualizar(1, cx, 150f, t, 20)
            last = r.avaliar(1, w, h).classification
            t += .3
        }
        assertTrue(last, last in MotionClass.MOVING)
    }
}
