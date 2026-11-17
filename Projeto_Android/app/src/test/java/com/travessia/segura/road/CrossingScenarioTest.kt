package com.travessia.segura.road

import com.travessia.segura.detection.RawDetection
import com.travessia.segura.road.CrossingVerification.Scenario
import com.travessia.segura.road.CrossingVerification.Stage
import com.travessia.segura.tracking.CrossingSessionMemory
import com.travessia.segura.vision.SparseOpticalFlowEstimator.MotionEstimate
import org.junit.Assert.*
import org.junit.Test

/**
 * Novo fluxo: faixa sem semáforo / sinal vermelho / sinal verde / rua sem sinalização confirmada.
 * Entradas sintéticas testam as TRANSIÇÕES; não medem a precisão visual do YOLO.
 */
class CrossingScenarioTest {
    private val still = MotionEstimate(dx = 1f, confidence = .8f, validPoints = 30, translationSupported = true)
    private val clear = CrossingRiskAssessment(emptySet(), emptyList(), SceneConfidence(true, true, false))
    private val covered = CrossingRiskAssessment(emptySet(), emptyList(), SceneConfidence(true, true, true))
    private val danger = CrossingRiskAssessment(setOf(RiskReason.CONFLICTING_APPROACH),
        listOf(VehicleMotionAssessment(7, "APROXIMANDO", true, false, true)), SceneConfidence(true, true, false))

    private fun scene(t: Long, signal: SignalApplicability = SignalApplicability.NOT_OBSERVED,
        strong: Boolean = false, candidate: Boolean = strong, reliable: Boolean = false, stable: Boolean = reliable,
        crosswalk: CrosswalkStatus = if (strong) CrosswalkStatus.CONFIRMED else CrosswalkStatus.UNCERTAIN) =
        SceneEvidence(crosswalk, null, signal, t, signalColorStable = stable, crosswalkStrong = strong,
            crosswalkCandidate = candidate, signalReliable = reliable)

    private class Rec { val stages = mutableListOf<Stage>()
        fun build() = CrossingVerification(onChange = { stages += it }, contextWindowMs = 1500) }

    /** Alimenta quadros a cada 250 ms (≈ YOLO 4 fps) até [until]. */
    private fun feed(c: CrossingVerification, from: Long, until: Long, risk: CrossingRiskAssessment = clear,
        sceneAt: (Long) -> SceneEvidence): Long {
        var t = from
        while (t < until) { t += 250; c.observe(risk, sceneAt(t), null, t, still, 640) }
        return t
    }

    // 1. Faixa confirmada (forte) + contexto sem semáforo confirmado -> levantar a mão.
    @Test fun strongCrosswalkWithConfirmedNoSignalAsksToRaiseHand() {
        val r = Rec(); val c = r.build(); c.start(0)
        feed(c, 0, 500) { scene(it, SignalApplicability.NOT_APPLICABLE_CONFIRMED, strong = true) }
        assertEquals(Stage.CROSSWALK_WAIT, c.stage)
        assertEquals(Scenario.CROSSWALK_NO_SIGNAL, c.scenario)
        assertFalse("sem roteiro direita/esquerda automático", Stage.AWAIT_RIGHT in r.stages)
        // Uma única entrada no estado (a fala é uma só).
        feed(c, 500, 2000) { scene(it, SignalApplicability.NOT_APPLICABLE_CONFIRMED, strong = true) }
        assertEquals(1, r.stages.count { it == Stage.CROSSWALK_WAIT })
    }

    @Test fun crosswalkWaitKeepsWatchingVehiclesAndNeverConcludesFromStoppedCarsAlone() {
        val c = Rec().build(); c.start(0)
        var t = feed(c, 0, 500) { scene(it, SignalApplicability.NOT_APPLICABLE_CONFIRMED, strong = true) }
        t = feed(c, t, t + 500, danger) { scene(it, SignalApplicability.NOT_APPLICABLE_CONFIRMED, strong = true) }
        assertEquals("perigo é informado imediatamente", Stage.DANGER, c.stage)
        assertEquals(setOf(7), c.dangerTrackIds)
        t = feed(c, t, t + 2500) { scene(it, SignalApplicability.NOT_APPLICABLE_CONFIRMED, strong = true) }
        assertEquals("volta ao mesmo cenário", Stage.CROSSWALK_WAIT, c.stage)
        feed(c, t, t + 4000) { scene(it, SignalApplicability.NOT_APPLICABLE_CONFIRMED, strong = true) }
        // Frente limpa não comprova as laterais: pede uma observação breve (fluxo direcional), nunca "favorável".
        assertEquals(Stage.AWAIT_RIGHT, c.stage)
        assertNotEquals(Stage.FAVORABLE_OBSERVATION, c.stage)
    }

    // 2. Faixa incerta (ou ausência de semáforo apenas por não detectar) -> fluxo convencional.
    @Test fun uncertainCrosswalkDoesNotEnableSimplifiedFlow() {
        val c = Rec().build(); c.start(0)
        feed(c, 0, 3500) { scene(it, SignalApplicability.NOT_APPLICABLE_CONFIRMED, candidate = true,
            crosswalk = CrosswalkStatus.CONFIRMED) }
        assertEquals(Stage.AWAIT_RIGHT, c.stage)
        assertEquals(Scenario.DIRECTIONAL, c.scenario)
    }

    @Test fun noDetectedSignalIsNotProofOfNoSignal() {
        val c = Rec().build(); c.start(0)
        feed(c, 0, 3500) { scene(it, SignalApplicability.NOT_OBSERVED, strong = true) }
        assertEquals("faixa forte sem sinal detectado != faixa sem semáforo", Stage.AWAIT_RIGHT, c.stage)
    }

    // 3. Sinal vermelho de pedestres aplicável -> aguardar, sem roteiro direita/esquerda.
    @Test fun stableRedSignalWaitsAndDoesNotStartDirectionalScript() {
        val r = Rec(); val c = r.build(); c.start(0)
        var t = feed(c, 0, 600) { scene(it, SignalApplicability.PEDESTRIAN_RED, strong = true, stable = true) }
        assertEquals(Stage.SIGNAL_RED, c.stage)
        // Carros passando no vermelho do pedestre: o usuário já aguarda; o estado não oscila.
        t = feed(c, t, t + 5000, danger) { scene(it, SignalApplicability.PEDESTRIAN_RED, strong = true, stable = true) }
        assertEquals(Stage.SIGNAL_RED, c.stage)
        assertEquals("sem repetir 'Sinal vermelho' por quadro", 1, r.stages.count { it == Stage.SIGNAL_RED })
        assertFalse(Stage.AWAIT_RIGHT in r.stages)
        // Mudou para verde confiável: um único aviso novo.
        feed(c, t, t + 500) { scene(it, SignalApplicability.PEDESTRIAN_GREEN, strong = true, reliable = true) }
        assertEquals(Stage.SIGNAL_GREEN, c.stage)
    }

    @Test fun redSignalThatDisappearsFallsBackToConventionalCheck() {
        val c = Rec().build(); c.start(0)
        val t = feed(c, 0, 600) { scene(it, SignalApplicability.PEDESTRIAN_RED, strong = true, stable = true) }
        feed(c, t, t + 3000) { scene(it) }
        assertEquals("sumiu sem verde: não presume verde", Stage.AWAIT_RIGHT, c.stage)
    }

    // 4. Verde de pedestres aplicável -> monitorar veículos; nunca "seguro" pela cor.
    @Test fun reliableGreenMonitorsVehiclesWithoutDirectionalScript() {
        val r = Rec(); val c = r.build(); c.start(0)
        var t = feed(c, 0, 300) { scene(it, SignalApplicability.PEDESTRIAN_GREEN, strong = true, reliable = true) }
        assertEquals(Stage.SIGNAL_GREEN, c.stage)
        // Veículo se aproximando no verde: alerta tem prioridade e depois volta a monitorar.
        t = feed(c, t, t + 500, danger) { scene(it, SignalApplicability.PEDESTRIAN_GREEN, strong = true, reliable = true) }
        assertEquals(Stage.DANGER, c.stage)
        t = feed(c, t, t + 2250) { scene(it, SignalApplicability.PEDESTRIAN_GREEN, strong = true, reliable = true) }
        assertEquals(Stage.SIGNAL_GREEN, c.stage)
        // Conclusão só com quadros limpos observados (não por tempo fixo de espera).
        t = feed(c, t, t + 2500) { scene(it, SignalApplicability.PEDESTRIAN_GREEN, strong = true, reliable = true) }
        assertTrue(Stage.FAVORABLE_OBSERVATION in r.stages)
        assertEquals("continua monitorando enquanto o verde vale", Stage.MONITORING, c.stage)
        assertFalse(Stage.AWAIT_RIGHT in r.stages)
        // Sinal fica vermelho: o verde anterior é invalidado.
        feed(c, t, t + 500) { scene(it, SignalApplicability.PEDESTRIAN_RED, strong = true, stable = true) }
        assertEquals(Stage.SIGNAL_RED, c.stage)
    }

    @Test fun waitingTimeAloneNeverConcludesGreen() {
        val c = Rec().build(); c.start(0)
        val t = feed(c, 0, 300) { scene(it, SignalApplicability.PEDESTRIAN_GREEN, strong = true, reliable = true) }
        // Veículo com movimento incerto em todos os quadros: o verde nunca vira "concluído".
        val uncertain = clear.copy(reasons = setOf(RiskReason.MOTION_UNCERTAIN), confidence = SceneConfidence(true, false, false))
        feed(c, t, t + 20_000, uncertain) { scene(it, SignalApplicability.PEDESTRIAN_GREEN, strong = true, reliable = true) }
        assertEquals(Stage.SIGNAL_GREEN, c.stage)
    }

    @Test fun greenLostOrContradictoryIsInvalidated() {
        val c = Rec().build(); c.start(0)
        var t = feed(c, 0, 300) { scene(it, SignalApplicability.PEDESTRIAN_GREEN, strong = true, reliable = true) }
        t = feed(c, t, t + 500) { scene(it, SignalApplicability.UNCERTAIN, strong = true) }
        assertEquals("informação contraditória: verificação convencional", Stage.AWAIT_RIGHT, c.stage)
        assertEquals(Scenario.DIRECTIONAL, c.scenario)
        val c2 = Rec().build(); c2.start(0)
        t = feed(c2, 0, 300) { scene(it, SignalApplicability.PEDESTRIAN_GREEN, strong = true, reliable = true) }
        feed(c2, t, t + 2000) { scene(it) } // verde sumiu (câmera saiu / falha)
        assertEquals(Stage.AWAIT_RIGHT, c2.stage)
    }

    // 5. Semáforo de VEÍCULOS verde não é sinal de pedestres.
    @Test fun vehicleGreenLightIsNotPedestrianGreen() {
        val c = Rec().build(); c.start(0)
        feed(c, 0, 3500) { scene(it, SignalApplicability.VEHICLE_ONLY, strong = true) }
        assertEquals(Stage.AWAIT_RIGHT, c.stage)
        assertFalse(c.pedestrianGreenObserved)
    }

    // 6. Sinal de pedestre verde sem associação confiável -> inconclusivo / convencional.
    @Test fun unassociatedPedestrianGreenUsesConventionalCheck() {
        val r = Rec(); val c = r.build(); c.start(0)
        feed(c, 0, 3500) { scene(it, SignalApplicability.PEDESTRIAN_GREEN, strong = true, reliable = false, stable = true) }
        assertEquals(Stage.AWAIT_RIGHT, c.stage)
        assertFalse(Stage.SIGNAL_GREEN in r.stages)
    }

    // 7. Rua sem sinalização confirmada -> direita, esquerda, frente (automático, sem "pronto").
    @Test fun unsignalledStreetKeepsRightLeftFrontFlow() {
        val r = Rec(); val c = r.build(); c.start(0)
        var t = feed(c, 0, 1250) { scene(it) }
        assertEquals("janela curta de contexto (câmera à frente)", Stage.ANALYZING, c.stage)
        t = feed(c, t, t + 500) { scene(it) }
        assertEquals(Stage.AWAIT_RIGHT, c.stage)
        feed(c, t, t + 6000, covered) { scene(it) }
        assertTrue(c.finished)
        assertTrue(c.noConflictObservedAllDirections)
        assertEquals(listOf(Stage.ANALYZING, Stage.AWAIT_RIGHT, Stage.VERIFY_RIGHT, Stage.AWAIT_LEFT, Stage.VERIFY_LEFT,
            Stage.AWAIT_FRONT, Stage.VERIFY_FRONT, Stage.INCONCLUSIVE), r.stages)
    }

    // 10. Veículo parado fica no histórico (RAM) sem virar garantia de segurança.
    @Test fun stoppedVehicleStaysInHistoryButIsNotEvidenceOfSafety() {
        val memory = CrossingSessionMemory()
        memory.beginSession(1)
        var t = 0L
        repeat(5) { t += 300; memory.observe(3, "car", floatArrayOf(100f, 200f, 200f, 260f), "PARADO", .9f, t,
            CrossingSessionMemory.Direction.RIGHT, 640, 360, cameraMotionInvalid = false) }
        val record = memory.recordFor(3)!!
        t += 10_000 // câmera foi para outra direção: o carro saiu da imagem
        assertEquals("histórico mantido", 1, memory.historyFor(CrossingSessionMemory.Direction.RIGHT, t).size)
        assertFalse("estado antigo não reflete a cena atual", memory.isFresh(record, t))
        assertTrue(memory.validity(record, t) < .2f)
        // Ao voltar a ver o veículo, um alerta novo prevalece imediatamente sobre o estado antigo.
        memory.observe(3, "car", floatArrayOf(150f, 200f, 260f, 280f), com.travessia.segura.tracking.MotionClass.POSSIVEL_APROXIMACAO, .9f, t + 100,
            CrossingSessionMemory.Direction.RIGHT, 640, 360, cameraMotionInvalid = false)
        assertTrue(memory.recordFor(3)!!.possibleConflict)
    }

    // 13. Nova análise começa com memória e estado limpos.
    @Test fun newAnalysisStartsWithCleanMemoryAndScenario() {
        val memory = CrossingSessionMemory(); memory.beginSession(1)
        memory.observe(1, "car", floatArrayOf(0f, 0f, 50f, 50f), "PARADO", .9f, 100, null, 640, 360, false)
        val c = Rec().build(); c.start(0)
        feed(c, 0, 600) { scene(it, SignalApplicability.PEDESTRIAN_RED, strong = true, stable = true) }
        assertEquals(Stage.SIGNAL_RED, c.stage)
        // "Terminei" -> cancel/clear; "Nova travessia" -> beginSession/start.
        c.cancel(); memory.clear()
        memory.beginSession(2); c.start(10_000)
        assertEquals(0, memory.size)
        assertEquals(Scenario.UNDECIDED, c.scenario)
        assertEquals(Stage.ANALYZING, c.stage)
        assertTrue(c.dangerTrackIds.isEmpty())
    }

    // Evidência temporal das faixas e da associação do sinal (SceneEvidenceTracker).
    @Test fun crosswalkNeedsConsistentTemporalEvidenceBeforeBeingStrong() {
        val tracker = SceneEvidenceTracker()
        val stripe = RawDetection(80f, 250f, 560f, 340f, 8, "crosswalk", .8f)
        val brief = (1..2).map { tracker.update(listOf(stripe), it * 250L, 640) }
        assertFalse("detecção curta não muda o cenário", brief.last().crosswalkStrong)
        var e = brief.last()
        for (i in 3..8) e = tracker.update(listOf(stripe), i * 250L, 640)
        assertTrue(e.crosswalkStrong)
        // Uma falha longa de detecção zera a evidência (não presume que a faixa continua ali).
        e = tracker.update(emptyList(), 3500, 640)
        assertFalse(e.crosswalkStrong)
    }

    @Test fun pedestrianSignalIsReliableOnlyWhenAloneStableAndPersistent() {
        val stripe = RawDetection(80f, 250f, 560f, 340f, 8, "crosswalk", .8f)
        val green = RawDetection(300f, 60f, 330f, 120f, 1, "pedestrian-light-green", .8f)
        val other = RawDetection(40f, 60f, 60f, 100f, 1, "pedestrian-light-green", .8f)
        val t1 = SceneEvidenceTracker(); var e = t1.update(listOf(stripe, green), 250, 640)
        assertFalse(e.signalReliable)
        for (i in 2..10) e = t1.update(listOf(stripe, green), i * 250L, 640)
        assertTrue(e.signalReliable)
        assertEquals(SignalApplicability.PEDESTRIAN_GREEN, e.signal)
        // Outro sinal de pedestre na imagem (outra travessia?): não é confiável.
        val t2 = SceneEvidenceTracker(); var e2 = t2.update(listOf(stripe, green, other), 250, 640)
        for (i in 2..10) e2 = t2.update(listOf(stripe, green, other), i * 250L, 640)
        assertFalse(e2.signalReliable)
    }

    // 14. Custo por quadro da lógica nova (não substitui medir FPS no aparelho).
    @Test fun perFrameCostOfScenarioLogicIsNegligible() {
        val tracker = SceneEvidenceTracker()
        val stripe = RawDetection(80f, 250f, 560f, 340f, 8, "crosswalk", .8f)
        val green = RawDetection(300f, 60f, 330f, 120f, 1, "pedestrian-light-green", .8f)
        val c = CrossingVerification(contextWindowMs = 1500); c.start(0)
        val frames = 20_000
        repeat(2000) { val t = (it + 1) * 50L; c.observe(clear, tracker.update(listOf(stripe, green), t, 640), null, t, still, 640) }
        val start = System.nanoTime()
        for (i in 2001..2000 + frames) {
            val t = i * 50L
            c.observe(clear, tracker.update(listOf(stripe, green), t, 640), null, t, still, 640)
        }
        val perFrameMs = (System.nanoTime() - start) / 1e6 / frames
        // YOLO leva ~300 ms por quadro no aparelho; a lógica de cenário precisa ser ordens de grandeza menor.
        assertTrue("custo por quadro: $perFrameMs ms", perFrameMs < 1.0)
    }
}
