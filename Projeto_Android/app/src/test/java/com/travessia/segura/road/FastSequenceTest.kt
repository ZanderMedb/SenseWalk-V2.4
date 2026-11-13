package com.travessia.segura.road

import com.travessia.segura.detection.VeiculoInfo
import com.travessia.segura.road.CrossingVerification.Direction
import com.travessia.segura.road.CrossingVerification.ObservationOutcome
import com.travessia.segura.road.CrossingVerification.Stage
import com.travessia.segura.tracking.MotionClass
import org.junit.Assert.*
import org.junit.Test

/** Sequência rápida com frames a ~3,3 Hz (custo real do YOLO no aparelho). Entradas sintéticas. */
class FastSequenceTest {
    private fun scene(t: Long) = SceneEvidence(CrosswalkStatus.UNCERTAIN, null, SignalApplicability.NOT_OBSERVED, t)
    private fun risk(covered: Boolean, vararg reasons: RiskReason) =
        CrossingRiskAssessment(reasons.toSet(), emptyList(), SceneConfidence(true, RiskReason.MOTION_UNCERTAIN !in reasons, covered))

    private class Run { val stages = mutableListOf<Pair<Long, Stage>>(); val retries = mutableListOf<Stage>(); var now = 0L }
    private fun build(r: Run) = CrossingVerification(onChange = { r.stages += r.now to it }, onRetry = { r.retries += it })
    /** Frames a cada 300 ms enquanto [covered]/[reasons] valem. */
    private fun frames(c: CrossingVerification, r: Run, ms: Long, covered: Boolean = true, risk: (Long) -> CrossingRiskAssessment = { risk(covered) }) {
        val end = r.now + ms
        while (r.now < end) { r.now += 300; c.observe(risk(r.now), scene(r.now), null, r.now) }
    }
    private fun started(r: Run): CrossingVerification = build(r).also { it.start(0); frames(it, r, 300, covered = false) }

    @Test fun rightObservedImmediatelyRequestsLeftThenFrontWithoutReady() {
        val r = Run(); val c = started(r)
        assertEquals(Stage.AWAIT_RIGHT, c.stage)
        frames(c, r, 1800) // primeiro frame coberto já observa; 3 frames limpos em 1,5 s
        assertEquals(Stage.AWAIT_LEFT, c.stage)
        assertEquals(ObservationOutcome.NO_CONFLICT_OBSERVED, c.outcome(Direction.RIGHT))
        val rightDone = r.stages.first { it.second == Stage.AWAIT_LEFT }.first
        val rightStart = r.stages.first { it.second == Stage.VERIFY_RIGHT }.first
        assertTrue("observação da direita em <= 1,8 s: ${rightDone - rightStart}", rightDone - rightStart <= 1800)
        frames(c, r, 1800)
        assertEquals(Stage.AWAIT_FRONT, c.stage)
        assertEquals(ObservationOutcome.NO_CONFLICT_OBSERVED, c.outcome(Direction.LEFT))
        frames(c, r, 1800)
        assertFalse("orientação válida não exige pronto", c.declaredByUser(Direction.RIGHT) || c.declaredByUser(Direction.LEFT))
        assertTrue(c.finished)
        assertEquals("sem faixa/sinal verificável: nunca favorável", Stage.INCONCLUSIVE, c.stage)
        assertTrue(r.retries.isEmpty())
    }

    @Test fun inconclusiveDirectionIsNeverNoConflictAndRetriesOnlyOnce() {
        val r = Run(); val c = started(r)
        frames(c, r, 8000) { risk(true, RiskReason.MOTION_UNCERTAIN) }
        assertEquals(listOf(Stage.VERIFY_RIGHT), r.retries)
        assertNotEquals(ObservationOutcome.NO_CONFLICT_OBSERVED, c.outcome(Direction.RIGHT))
        assertEquals(ObservationOutcome.INCONCLUSIVE, c.outcome(Direction.RIGHT))
        assertEquals("avança após uma nova tentativa (sem ficar preso)", Stage.VERIFY_LEFT, c.stage)
    }

    @Test fun noStageGetsStuckEvenWithContinuousUncertainty() {
        val r = Run(); val c = started(r)
        frames(c, r, 30_000) { risk(true, RiskReason.TRACK_LOST) }
        assertTrue(c.finished)
        assertTrue("terminou em ${r.now} ms: ${r.stages} retries=${r.retries}", r.stages.last().first <= 20_000)
        assertEquals(3, r.retries.size) // no máximo uma por direção
        for (d in Direction.values()) assertEquals(ObservationOutcome.INCONCLUSIVE, c.outcome(d))
    }

    @Test fun parkedVehicleIsNotAutomaticallySafe() {
        // Carro parado bem à frente no corredor: obstáculo = possível perigo, não "sem conflito".
        val parked = VeiculoInfo("car", .9f, 320f, 300f, MotionClass.PARADO, 0f, floatArrayOf(200f, 240f, 440f, 350f), 240f, 1)
        val obstacle = CrossingRiskAnalyzer.analyze(listOf(parked), 640, 360, coverageVerified = true, cameraReliable = true)
        val r = Run(); val c = started(r)
        frames(c, r, 900) { obstacle }
        assertEquals(Stage.DANGER, c.stage)
        assertEquals(ObservationOutcome.POTENTIAL_DANGER, c.outcome(Direction.RIGHT))
        // Carro parado distante: a direção pode ficar "sem conflito observado", mas a travessia não fica segura.
        val far = parked.copy(cy = 60f, bbox = floatArrayOf(20f, 40f, 60f, 70f), bboxW = 40f)
        val r2 = Run(); val c2 = started(r2)
        frames(c2, r2, 8000) { CrossingRiskAnalyzer.analyze(listOf(far), 640, 360, coverageVerified = true, cameraReliable = true) }
        assertNotEquals(Stage.FAVORABLE_OBSERVATION, c2.stage)
        assertNotEquals(Stage.MONITORING, c2.stage)
    }

    @Test fun recedingVehicleIsNotAutomaticallySafe() {
        val receding = VeiculoInfo("car", .9f, 320f, 120f, MotionClass.POSSIVEL_AFASTAMENTO, 0f, floatArrayOf(290f, 100f, 350f, 140f), 60f, 1)
        val risk = CrossingRiskAnalyzer.analyze(listOf(receding), 640, 360, coverageVerified = true, cameraReliable = true)
        val r = Run(); val c = started(r)
        frames(c, r, 7000) { risk }
        assertEquals(ObservationOutcome.INCONCLUSIVE, c.outcome(Direction.RIGHT))
    }

    @Test fun newDangerInvalidatesPreviousConclusions() {
        val r = Run(); val c = started(r)
        frames(c, r, 1800)
        assertEquals(ObservationOutcome.NO_CONFLICT_OBSERVED, c.outcome(Direction.RIGHT))
        frames(c, r, 600) { risk(true, RiskReason.CONFLICTING_APPROACH) }
        assertEquals(Stage.DANGER, c.stage)
        assertEquals(ObservationOutcome.POTENTIAL_DANGER, c.outcome(Direction.LEFT))
        assertEquals("conclusão anterior invalidada", ObservationOutcome.INCONCLUSIVE, c.outcome(Direction.RIGHT))
        frames(c, r, 2400) // perigo some: retoma a MESMA direção (esquerda) e a observa de novo
        frames(c, r, 4000)
        assertNotEquals(Stage.FAVORABLE_OBSERVATION, c.stage)
        assertEquals(ObservationOutcome.INCONCLUSIVE, c.outcome(Direction.RIGHT))
    }

    @Test fun usbWithoutOrientationStaysInconclusive() {
        val r = Run(); val c = started(r)
        for (d in 0 until 3) { c.confirmCameraDirection(r.now); frames(c, r, 4000, covered = false) }
        assertTrue(c.finished)
        for (d in Direction.values()) assertEquals(ObservationOutcome.INCONCLUSIVE, c.outcome(d))
        assertTrue(r.retries.isEmpty())
    }
    @Test fun turnTimeCountsFromTheEndOfTheSpokenInstruction() {
        val r = Run(); val c = started(r) // AWAIT_RIGHT em ~300 ms
        frames(c, r, 1500, covered = false) // instrução ainda sendo falada
        c.instructionHeard(r.now, Stage.AWAIT_RIGHT)
        val heard = r.now
        frames(c, r, 1200, covered = false)
        assertEquals("ainda girando: não começa a observar", Stage.AWAIT_RIGHT, c.stage)
        frames(c, r, 600, covered = false)
        assertEquals(Stage.VERIFY_RIGHT, c.stage)
        val began = r.stages.first { it.second == Stage.VERIFY_RIGHT }.first
        assertTrue("começa ~1,5 s após ouvir: ${began - heard}", began - heard in 1500..1800)
    }

    @Test fun withoutReadyOrSensorTheSequenceStillFinishesQuickly() {
        val r = Run(); val c = started(r)
        frames(c, r, 20_000, covered = false)
        assertTrue(c.finished)
        // Sem aviso de fim de fala (TTS falhou): limite de 4 s por direção + observação; com o TTS é ~15 s.
        assertTrue("terminou em ${r.stages.last().first} ms", r.stages.last().first <= 20_000)
        for (d in Direction.values()) assertEquals(ObservationOutcome.INCONCLUSIVE, c.outcome(d))
        assertFalse(c.noConflictObservedAllDirections)
    }

    @Test fun factualSummaryOnlyWhenAllThreeDirectionsHadNoConflict() {
        val r = Run(); val c = started(r)
        frames(c, r, 6000)
        assertTrue(c.finished)
        assertTrue(c.noConflictObservedAllDirections)
        assertEquals("resumo factual não é favorável: continua sem autorização", Stage.INCONCLUSIVE, c.stage)
        // Um perigo em qualquer momento da sessão impede o resumo.
        val r2 = Run(); val c2 = started(r2)
        frames(c2, r2, 1800)
        frames(c2, r2, 300) { risk(true, RiskReason.POTENTIAL_TRAFFIC_CONFLICT) }
        frames(c2, r2, 12_000)
        assertFalse(c2.noConflictObservedAllDirections)
        c2.restart(r2.now)
        assertFalse(c2.noConflictObservedAllDirections)
    }
    @Test fun cleanButUnverifiedDirectionsAreReportedForTheSummary() {
        // Webcam USB: nenhum conflito nos frames, mas sem orientação comprovada.
        val r = Run(); val c = started(r)
        frames(c, r, 20_000, covered = false)
        assertTrue(c.finished)
        for (d in Direction.values()) {
            assertEquals(ObservationOutcome.INCONCLUSIVE, c.outcome(d))
            assertTrue("$d visto sem conflito", c.cleanButUnverified(d))
        }
        // Frames com incerteza não contam como "visto sem conflito".
        val r2 = Run(); val c2 = started(r2)
        frames(c2, r2, 30_000) { risk(false, RiskReason.MOTION_UNCERTAIN) }
        for (d in Direction.values()) assertFalse(c2.cleanButUnverified(d))
    }
    @Test fun emptyRoadAtNightCanBeObservedWithoutCameraMotionEstimate() {
        // Rua vazia e escura: o fluxo óptico falha (cameraReliable=false), mas não há veículo a compensar.
        val night = { covered: Boolean -> CrossingRiskAnalyzer.analyze(emptyList(), 854, 480, coverageVerified = covered, cameraReliable = false) }
        val r = Run(); val c = started(r)
        frames(c, r, 6000) { night(true) }
        assertTrue(c.finished)
        assertTrue("três direções vazias e comprovadas pelo sensor", c.noConflictObservedAllDirections)
        assertEquals("ainda não autoriza: resultado continua sem 'favorável'", Stage.INCONCLUSIVE, c.stage)
        // Sem sensor (USB): descreve "visto sem conflito, direção não confirmada".
        val r2 = Run(); val c2 = started(r2)
        frames(c2, r2, 20_000) { night(false) }
        for (d in Direction.values()) assertTrue(c2.cleanButUnverified(d))
    }

    @Test fun visibleVehicleWithUnreliableCameraIsStillNotClean() {
        val car = VeiculoInfo("car", .9f, 100f, 200f, MotionClass.APARENTEMENTE_PARADO, 0f, floatArrayOf(60f, 180f, 140f, 220f), 80f, 1)
        val risk = CrossingRiskAnalyzer.analyze(listOf(car), 854, 480, coverageVerified = true, cameraReliable = false)
        assertFalse(risk.confidence.motionReliable)
        val r = Run(); val c = started(r)
        frames(c, r, 7000) { risk }
        assertNotEquals(ObservationOutcome.NO_CONFLICT_OBSERVED, c.outcome(Direction.RIGHT))
        // Fantasma (rastreio perdido) também impede "vazio".
        assertFalse(CrossingRiskAnalyzer.analyze(emptyList(), 854, 480, coverageVerified = true, cameraReliable = false,
            unobservedVehicles = 1).conclusive)
    }
    @Test fun withSensorTheStepWaitsForASlowTurnInsteadOfClosingUnverified() {
        // Caso do teste na rua: observação começou antes de o usuário terminar de girar (rua vazia = quadros limpos).
        val r = Run(); val c = started(r)
        c.instructionHeard(r.now, Stage.AWAIT_RIGHT)
        fun obs(ms: Long, covered: Boolean) { val end = r.now + ms; while (r.now < end) { r.now += 300
            c.observe(risk(covered), scene(r.now), null, r.now, coverageAvailable = true) } }
        obs(1800, covered = false)
        assertEquals("câmera ainda na frente: não começa a analisar a direita", Stage.AWAIT_RIGHT, c.stage)
        obs(1200, covered = false) // ainda girando
        assertEquals("espera a direção ser comprovada", Stage.AWAIT_RIGHT, c.stage)
        obs(2400, covered = true)  // chegou e segurou
        assertEquals(ObservationOutcome.NO_CONFLICT_OBSERVED, c.outcome(Direction.RIGHT))
        assertEquals("seguiu para a esquerda", Direction.LEFT, c.currentDirection)
    }

    @Test fun withSensorButNoTurnTheStepRetriesOnceThenMovesOn() {
        val r = Run(); val retries = r.retries; val c = build(r).also { it.start(0) }
        fun obs(ms: Long) { val end = r.now + ms; while (r.now < end) { r.now += 300
            c.observe(risk(false), scene(r.now), null, r.now, coverageAvailable = true) } }
        obs(20_000) // espera até 12 s pela direção, com uma dica no meio
        assertEquals("uma dica de orientação, ainda aguardando a direção", listOf(Stage.AWAIT_RIGHT), retries.take(1))
        assertTrue(c.stage == Stage.AWAIT_LEFT || c.stage == Stage.VERIFY_LEFT)
        assertEquals(ObservationOutcome.INCONCLUSIVE, c.outcome(Direction.RIGHT))
        assertFalse("sem comprovação não é 'visto sem conflito'", c.outcome(Direction.RIGHT) == ObservationOutcome.NO_CONFLICT_OBSERVED)
    }
    @Test fun lateTurnLikeOnTheStreetIsStillAcceptedForTheRight() {
        // Teste na rua: o usuário só começou a girar ~9 s após a instrução e chegou à direita depois.
        val r = Run(); val c = started(r)
        c.instructionHeard(r.now, Stage.AWAIT_RIGHT)
        fun obs(ms: Long, covered: Boolean) { val end = r.now + ms; while (r.now < end) { r.now += 300
            c.observe(risk(covered), scene(r.now), null, r.now, coverageAvailable = true) } }
        obs(9000, covered = false)   // demora para começar a girar (antes a etapa já teria desistido)
        assertEquals(Direction.RIGHT, c.currentDirection)
        obs(2400, covered = true)    // chegou e segurou
        assertEquals(ObservationOutcome.NO_CONFLICT_OBSERVED, c.outcome(Direction.RIGHT))
        assertEquals(1, r.retries.size) // uma dica "gire mais para a direita"
    }
    @Test fun withMeasuredTurnTheDirectionIsNeverAnalyzedWhileStillLookingForward() {
        // Relato da rua: "falou para virar à direita, eu ainda estava na frente e começou a analisar".
        val r = Run(); val c = started(r)
        c.instructionHeard(r.now, Stage.AWAIT_RIGHT)
        val end = r.now + 11_000
        while (r.now < end) { r.now += 300; c.observe(risk(false), scene(r.now), null, r.now, coverageAvailable = true) }
        assertFalse("nunca entrou em VERIFY_RIGHT sem a câmera na direita", r.stages.any { it.second == Stage.VERIFY_RIGHT })
        assertEquals(Stage.AWAIT_RIGHT, c.stage)
        r.now += 300; c.observe(risk(true), scene(r.now), null, r.now, coverageAvailable = true)
        assertEquals("chegou na direita: começa a observar", Stage.VERIFY_RIGHT, c.stage)
    }
}
