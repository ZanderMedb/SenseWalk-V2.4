package com.travessia.segura.tracking

import com.travessia.segura.road.CrossingRiskAnalyzer
import com.travessia.segura.road.RiskReason
import com.travessia.segura.tracking.CrossingSessionMemory.Direction
import org.junit.Assert.*
import org.junit.Test

/** Memória da sessão (RAM). Classificações brutas sintéticas a ~3,3 Hz (300 ms), 640x360. */
class CrossingSessionMemoryTest {
    private val W = 640; private val H = 360
    private fun box(cx: Float, cy: Float = 220f, w: Float = 80f, h: Float = 50f) = floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    private fun CrossingSessionMemory.obs(tid: Int, raw: String, t: Long, dir: Direction? = Direction.RIGHT, cx: Float = 300f,
        cameraInvalid: Boolean = false, w: Float = 80f, cy: Float = 220f) =
        observe(tid, "car", box(cx, cy, w), raw, .8f, t, dir, W, H, cameraInvalid)

    @Test fun threeConsistentObservationsStabilizeApparentlyStopped() {
        val m = CrossingSessionMemory().apply { beginSession(1) }
        assertEquals(MotionClass.MOVIMENTO_INCERTO, m.obs(1, MotionClass.PARADO, 300).state)
        assertEquals(MotionClass.MOVIMENTO_INCERTO, m.obs(1, MotionClass.PARADO, 600).state)
        assertEquals(MotionClass.APARENTEMENTE_PARADO, m.obs(1, MotionClass.PARADO, 900).state)
        // Mesma observação repetida (sem distância temporal) não conta.
        val m2 = CrossingSessionMemory().apply { beginSession(1) }
        m2.obs(2, MotionClass.PARADO, 300); m2.obs(2, MotionClass.PARADO, 310); m2.obs(2, MotionClass.PARADO, 320)
        assertNotEquals(MotionClass.APARENTEMENTE_PARADO, m2.recordFor(2)!!.state)
    }

    @Test fun apparentlyStoppedDoesNotFlickerWithIsolatedNoise() {
        val m = CrossingSessionMemory().apply { beginSession(1) }
        var t = 0L
        repeat(3) { t += 300; m.obs(1, MotionClass.PARADO, t) }
        val raws = listOf(MotionClass.MOVIMENTO_INCERTO, MotionClass.PARADO, MotionClass.MOVIMENTO_INCERTO, MotionClass.PARADO,
            MotionClass.POSSIVEL_AFASTAMENTO, MotionClass.PARADO, MotionClass.MOVIMENTO_INCERTO, MotionClass.PARADO)
        for (raw in raws) { t += 300; assertEquals(MotionClass.APARENTEMENTE_PARADO, m.obs(1, raw, t).state) }
        assertEquals(1, m.recordFor(1)!!.transitions)
    }

    @Test fun vehicleStartingToMoveRaisesAnAlertImmediately() {
        val m = CrossingSessionMemory().apply { beginSession(1) }
        var t = 0L
        repeat(4) { t += 300; m.obs(1, MotionClass.PARADO, t) }
        val r = m.obs(1, MotionClass.POTENCIAL_CONFLITO, t + 300, cx = 330f)
        assertEquals("sem esperar 3 frames", MotionClass.POTENCIAL_CONFLITO, r.state)
        assertTrue(r.possibleConflict)
        val car = com.travessia.segura.detection.VeiculoInfo("car", .9f, 330f, 220f, r.state, 40f, box(330f), 80f, 1)
        assertTrue(CrossingRiskAnalyzer.analyze(listOf(car), W, H, cameraReliable = true).reasons.contains(RiskReason.POTENTIAL_TRAFFIC_CONFLICT))
        // EM_MOVIMENTO também é imediato a partir de "parado"; descer de conflito para movimento exige 3 observações.
        assertEquals(MotionClass.POTENCIAL_CONFLITO, m.obs(1, MotionClass.EM_MOVIMENTO, t + 600).state)
        val m2 = CrossingSessionMemory().apply { beginSession(1) }
        repeat(3) { m2.obs(5, MotionClass.PARADO, 300L * (it + 1)) }
        assertEquals(MotionClass.EM_MOVIMENTO, m2.obs(5, MotionClass.EM_MOVIMENTO, 1200).state)
    }

    @Test fun uncompensatedCameraMotionInvalidatesApparentlyStoppedImmediately() {
        val m = CrossingSessionMemory().apply { beginSession(1) }
        var t = 0L
        repeat(3) { t += 300; m.obs(1, MotionClass.PARADO, t) }
        assertEquals(MotionClass.MOVIMENTO_INCERTO, m.obs(1, MotionClass.PARADO, t + 300, cameraInvalid = true).state)
        // E não volta a "parado" enquanto a câmera não estiver compensada.
        repeat(5) { assertNotEquals(MotionClass.APARENTEMENTE_PARADO, m.obs(1, MotionClass.PARADO, t + 600 + it * 300L, cameraInvalid = true).state) }
    }

    @Test fun rightHistorySurvivesLeftAndLeftSurvivesFrontWithDecayingValidity() {
        val m = CrossingSessionMemory().apply { beginSession(7) }
        var t = 0L
        repeat(4) { t += 300; m.obs(1, MotionClass.PARADO, t, Direction.RIGHT) }
        repeat(4) { t += 300; m.obs(2, MotionClass.EM_MOVIMENTO, t, Direction.LEFT, cx = 200f) }
        val right = m.historyFor(Direction.RIGHT, t)
        assertEquals(1, right.size)
        assertEquals(MotionClass.APARENTEMENTE_PARADO, right.single().first.state)
        assertTrue("validade decai fora de vista", right.single().second < 1f)
        repeat(4) { t += 300; m.obs(3, MotionClass.PARADO, t, Direction.FRONT, cx = 320f) }
        assertEquals(1, m.historyFor(Direction.LEFT, t).size)
        assertEquals(1, m.historyFor(Direction.RIGHT, t).size)
        assertTrue(m.records().all { it.sessionId == 7L })
        assertFalse("histórico antigo não reflete a cena atual", m.isFresh(m.historyFor(Direction.RIGHT, t).single().first, t))
    }

    @Test fun returningToTheRightReEvaluatesThePreviousVehicle() {
        val m = CrossingSessionMemory().apply { beginSession(1) }
        var t = 0L
        repeat(4) { t += 300; m.obs(1, MotionClass.PARADO, t, Direction.RIGHT, cx = 300f) }
        val before = m.recordFor(1)!!.memoryId
        t += 6000 // esquerda e frente
        // De volta à direita, o tracker deu um ID novo ao mesmo carro (mesma posição).
        val back = m.obs(9, MotionClass.PARADO, t, Direction.RIGHT, cx = 305f)
        assertEquals("mesmo veículo da memória", before, back.memoryId)
        assertTrue(back.revisited)
        assertEquals("estado antigo não continua valendo: precisa ser reconfirmado", MotionClass.MOVIMENTO_INCERTO, back.state)
        t += 300; m.obs(9, MotionClass.PARADO, t, Direction.RIGHT, cx = 305f)
        t += 300; assertEquals(MotionClass.APARENTEMENTE_PARADO, m.obs(9, MotionClass.PARADO, t, Direction.RIGHT, cx = 305f).state)
    }

    @Test fun oldIdIsNotLinkedToADifferentOrAmbiguousVehicle() {
        val m = CrossingSessionMemory().apply { beginSession(1) }
        var t = 0L
        repeat(3) { t += 300; m.obs(1, MotionClass.PARADO, t, Direction.RIGHT, cx = 150f) }
        t += 4000
        assertNotEquals("longe da posição anterior", m.recordFor(1)!!.memoryId, m.obs(5, MotionClass.PARADO, t, Direction.RIGHT, cx = 450f).memoryId)
        assertNotEquals("outra direção", m.recordFor(1)!!.memoryId, m.obs(6, MotionClass.PARADO, t, Direction.LEFT, cx = 150f).memoryId)
        // Dois registros antigos próximos: ambíguo, nenhum vínculo.
        val a = CrossingSessionMemory().apply { beginSession(1) }
        a.obs(1, MotionClass.PARADO, 300, cx = 300f); a.obs(2, MotionClass.PARADO, 300, cx = 330f)
        val n = a.obs(3, MotionClass.PARADO, 5000, cx = 315f)
        assertFalse(n.revisited)
        assertEquals(3, a.size)
    }

    @Test fun expiredRecordsAreRemovedAndNeverCountAsFresh() {
        val m = CrossingSessionMemory().apply { beginSession(1) }
        repeat(3) { m.obs(1, MotionClass.PARADO, 300L * (it + 1)) }
        val r = m.recordFor(1)!!
        assertTrue(m.isFresh(r, 1000))
        assertFalse(m.isFresh(r, 3000))
        assertTrue(m.validity(r, 20_000) < .05f)
        m.obs(2, MotionClass.PARADO, 40_000, cx = 500f) // dispara a limpeza
        assertNull("expirado após 30 s sem observação real", m.recordFor(1))
    }

    @Test fun distantSmallVehicleDoesNotFlickerExcessively() {
        val m = CrossingSessionMemory().apply { beginSession(1) }
        val noisy = listOf(MotionClass.PARADO, MotionClass.MOVIMENTO_INCERTO, MotionClass.PARADO, MotionClass.POSSIVEL_AFASTAMENTO,
            MotionClass.PARADO, MotionClass.MOVIMENTO_INCERTO, MotionClass.PARADO, MotionClass.PARADO, MotionClass.MOVIMENTO_INCERTO,
            MotionClass.PARADO, MotionClass.PARADO, MotionClass.PARADO, MotionClass.MOVIMENTO_INCERTO, MotionClass.PARADO)
        var rawChanges = 0
        noisy.forEachIndexed { i, raw ->
            if (i > 0 && raw != noisy[i - 1]) rawChanges++
            m.obs(1, raw, 300L * (i + 1), cx = 600f, cy = 60f, w = 14f)
        }
        val r = m.recordFor(1)!!
        assertTrue("estabilizado ${r.transitions} x bruto $rawChanges", r.transitions <= 1 && rawChanges >= 8)
        assertTrue("pequeno: confiança proporcional", r.confidence < .8f)
        assertEquals("alto e pequeno: relevância operacional menor (não ignorado)", .5f, r.relevance)
    }

    @Test fun memoryIsBounded() {
        val m = CrossingSessionMemory(maxRecords = 20).apply { beginSession(1) }
        for (i in 0 until 500) m.obs(i, MotionClass.MOVIMENTO_INCERTO, 100L + i, dir = null, cx = (i * 37 % 600).toFloat())
        assertTrue(m.size <= 20)
        val r = m.obs(1000, MotionClass.PARADO, 10_000, dir = null)
        repeat(100) { m.obs(1000, MotionClass.PARADO, 10_100L + it * 100, dir = null) }
        assertTrue("histórico de posições limitado", r.positions.size <= 12)
    }

    @Test fun stopClearsTheSession() {
        val m = CrossingSessionMemory().apply { beginSession(3) }
        repeat(3) { m.obs(it, MotionClass.PARADO, 300L) }
        m.clear()
        assertEquals(0, m.size)
        assertNull(m.recordFor(0))
        m.beginSession(4)
        assertEquals(4L, m.obs(1, MotionClass.PARADO, 600).sessionId)
    }

    @Test fun updateCostIsNegligible() {
        val m = CrossingSessionMemory().apply { beginSession(1) }
        val started = System.nanoTime()
        for (f in 0 until 2000) for (v in 0 until 15) m.obs(v, if (f % 7 == 0) MotionClass.MOVIMENTO_INCERTO else MotionClass.PARADO,
            300L * f, Direction.RIGHT, cx = 30f + 40f * v)
        val perFrameMs = (System.nanoTime() - started) / 1e6 / 2000
        // Host JVM; no aparelho ver memory_ms em SenseWalkPerf. Muito abaixo de 1 ms por frame com 15 veículos.
        assertTrue("custo por frame: $perFrameMs ms", perFrameMs < 1.0)
    }
    @Test fun redYellowAlternationDoesNotFlicker() {
        // Caso do teste no celular: classe bruta alternando conflito (vermelho) e movimento (amarelo).
        val m = CrossingSessionMemory().apply { beginSession(1) }
        var t = 0L
        val raws = (0 until 12).map { if (it % 2 == 0) MotionClass.POTENCIAL_CONFLITO else MotionClass.EM_MOVIMENTO }
        val states = raws.map { t += 300; m.obs(1, it, t).state }
        assertTrue("estado mantido: $states", states.all { it == MotionClass.POTENCIAL_CONFLITO })
        assertEquals(1, m.recordFor(1)!!.transitions)
    }
}
