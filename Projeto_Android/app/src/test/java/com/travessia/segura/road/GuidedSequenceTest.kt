package com.travessia.segura.road

import com.travessia.segura.road.CrossingVerification.Direction
import com.travessia.segura.road.CrossingVerification.DirectionStatus
import com.travessia.segura.road.CrossingVerification.Stage
import com.travessia.segura.vision.SparseOpticalFlowEstimator.MotionEstimate
import org.junit.Assert.*
import org.junit.Test

/**
 * Sequência direita -> esquerda -> frente sem botão. Entradas sintéticas: testam transições,
 * não precisão visual. Cobertura do Android real é sempre `false` (sem calibração de yaw/FOV).
 */
class GuidedSequenceTest {
    private val width = 640
    /** Como no app: coverageVerified=false sempre. */
    private val real = CrossingRiskAssessment(emptySet(), emptyList(), SceneConfidence(true, true, false))
    private val attested = CrossingRiskAssessment(emptySet(), emptyList(), SceneConfidence(true, true, true))
    private val danger = real.copy(reasons = setOf(RiskReason.CONFLICTING_APPROACH))
    private fun scene(t: Long, crosswalk: CrosswalkStatus = CrosswalkStatus.CONFIRMED) =
        SceneEvidence(crosswalk, null, SignalApplicability.NOT_APPLICABLE_CONFIRMED, t)
    private val panning = MotionEstimate(dx = 40f, confidence = .8f, validPoints = 30, translationSupported = true)
    private val still = MotionEstimate(dx = 1f, confidence = .8f, validPoints = 30, translationSupported = true)

    private class Recorder {
        val stages = mutableListOf<Stage>()
        val reprompts = mutableListOf<Stage>()
        fun build() = CrossingVerification(onChange = { stages += it }, onReprompt = { reprompts += it })
    }

    /** Pan físico simulado: movimento visual seguido de estabilização. */
    private fun pan(c: CrossingVerification, from: Long, risk: CrossingRiskAssessment = real): Long {
        var t = from
        repeat(4) { t += 100; c.observe(risk, scene(t), null, t, panning, width) }
        repeat(2) { t += 100; c.observe(risk, scene(t), null, t, still, width) }
        return t
    }
    /** Mantém a câmera parada observando o trânsito. */
    private fun hold(c: CrossingVerification, from: Long, ms: Long = 2000, risk: CrossingRiskAssessment = real): Long {
        var t = from
        while (t < from + ms) { t += 250; c.observe(risk, scene(t), null, t, still, width) }
        return t
    }

    /** "Pronto" (voz) seguido de observação; nenhum botão. */
    private fun ready(c: CrossingVerification, from: Long, risk: CrossingRiskAssessment = real): Long {
        c.confirmCameraDirection(from)
        return hold(c, from, risk = risk)
    }

    @Test fun readyByVoiceAdvancesRightLeftFrontWithoutAnyButton() {
        val r = Recorder(); val c = r.build()
        c.start(1000); c.observe(real, scene(1100), null, 1100, still, width)
        assertEquals(Stage.AWAIT_RIGHT, c.stage)
        var t = pan(c, 1100)
        // Movimento é registrado como evidência, mas direita/esquerda esperam o "pronto".
        assertEquals(Stage.AWAIT_RIGHT, c.stage)
        assertEquals(DirectionStatus.MOTION_DETECTED, c.status(Direction.RIGHT))
        t = ready(c, t + 100)
        assertEquals(Stage.AWAIT_LEFT, c.stage)
        t = ready(c, pan(c, t) + 100)
        assertEquals(Stage.AWAIT_FRONT, c.stage)
        // Frente: sem pedido de "pronto"; começa a observar sozinha.
        t = hold(c, t, 5500)
        hold(c, t)
        assertEquals(listOf(Stage.ANALYZING, Stage.AWAIT_RIGHT, Stage.VERIFY_RIGHT, Stage.AWAIT_LEFT, Stage.VERIFY_LEFT,
            Stage.AWAIT_FRONT, Stage.VERIFY_FRONT, Stage.INCONCLUSIVE), r.stages)
        // Declaração e movimento != cobertura confirmada: resultado real permanece inconclusivo.
        for (d in Direction.values()) assertEquals(DirectionStatus.COVERAGE_INCONCLUSIVE, c.status(d))
        assertTrue(c.declaredByUser(Direction.RIGHT) && c.declaredByUser(Direction.LEFT))
        assertTrue(c.cannotVerifyDirection)
        assertTrue(c.finished)
    }

    @Test fun frontStartsObservingAfterStabilizingMovement() {
        val c = CrossingVerification()
        c.start(1000); c.observe(real, scene(1100), null, 1100)
        c.requestContinue(1200); c.requestContinue(1300) // direita
        c.requestContinue(1400); c.requestContinue(1500) // esquerda
        assertEquals(Stage.AWAIT_FRONT, c.stage)
        c.instructionHeard(1500, Stage.AWAIT_FRONT) // "Agora, à frente" terminou de ser falado
        // Movimento + estabilização antecipam a observação (após o giro mínimo de 1,2 s), antes do tempo-limite.
        hold(c, pan(c, 1500), 700)
        assertEquals(Stage.VERIFY_FRONT, c.stage)
    }

    @Test fun instructionsAreEmittedOncePerStepWithAtMostOneReminder() {
        val r = Recorder(); val c = r.build()
        c.start(0)
        // 80 s de frames sem nenhum "pronto": "aponte e mantenha" avança por tempo, sem loop nem lembretes.
        for (t in 100L..80000L step 100) c.observe(real, scene(t), null, t, still, width)
        assertEquals(1, r.stages.count { it == Stage.AWAIT_RIGHT })
        assertEquals(1, r.stages.count { it == Stage.AWAIT_LEFT })
        assertEquals(1, r.stages.count { it == Stage.AWAIT_FRONT })
        assertTrue("sem lembretes de \"pronto\"", r.reprompts.isEmpty())
        assertTrue("sequência inteira em < 20 s sem nenhuma resposta do usuário",
            r.stages.last().let { it == Stage.INCONCLUSIVE } && c.finished)
        // Sem resposta, cada direção avança como não verificada e a sessão termina.
        assertEquals(Stage.INCONCLUSIVE, c.stage)
        for (d in Direction.values()) assertNotEquals(DirectionStatus.COVERAGE_CONFIRMED, c.status(d))
        val after = r.stages.size
        for (t in 80100L..110000L step 100) c.observe(real, scene(t), null, t, still, width)
        assertEquals("resultado terminal não reinicia sozinho", after, r.stages.size)
    }

    @Test fun secondReadyDuringObservationIsIgnoredNoDoubleConfirmation() {
        val r = Recorder(); val c = r.build()
        c.start(1000); c.observe(real, scene(1100), null, 1100)
        c.confirmCameraDirection(1500) // "Pronto"
        assertEquals(Stage.VERIFY_RIGHT, c.stage)
        assertTrue(c.declaredByUser(Direction.RIGHT))
        assertFalse(c.directionConfirmed)
        c.confirmCameraDirection(1600) // "Pronto" repetido
        c.confirmCameraDirection(1700)
        assertEquals(Stage.VERIFY_RIGHT, c.stage)
        hold(c, 1700)
        assertEquals("avança sozinho após observar, sem segunda confirmação", Stage.AWAIT_LEFT, c.stage)
        assertEquals(1, r.stages.count { it == Stage.VERIFY_RIGHT })
        assertEquals(DirectionStatus.COVERAGE_INCONCLUSIVE, c.status(Direction.RIGHT))
    }

    @Test fun continueCommandWorksLikeReadyAndNeverCountsAsCoverage() {
        val c = CrossingVerification()
        c.start(1000); c.observe(attested, scene(1100), null, 1100)
        c.requestContinue(1200); assertEquals(Stage.VERIFY_RIGHT, c.stage)
        c.requestContinue(1300); assertEquals(Stage.AWAIT_LEFT, c.stage)
        c.requestContinue(1400); c.requestContinue(1500); assertEquals(Stage.AWAIT_FRONT, c.stage)
        c.requestContinue(1600); c.requestContinue(1700); assertEquals(Stage.INCONCLUSIVE, c.stage)
        for (d in Direction.values()) assertEquals(DirectionStatus.COVERAGE_INCONCLUSIVE, c.status(d))
    }

    @Test fun commandHeardBeforeAStageChangeIsDiscarded() {
        val c = CrossingVerification()
        c.start(1000); c.observe(real, scene(1100), null, 1100)
        val heardDuring = c.stage // AWAIT_RIGHT quando o microfone abriu
        ready(c, 1200)
        assertEquals(Stage.AWAIT_LEFT, c.stage)
        c.confirmCameraDirection(5000, heardDuring)
        c.requestContinue(5000, heardDuring)
        assertEquals("sem confirmação duplicada para a etapa anterior", Stage.AWAIT_LEFT, c.stage)
    }

    @Test fun dangerInterruptsAndResumesTheSameDirectionNotTheRight() {
        val r = Recorder(); val c = r.build()
        c.start(1000); c.observe(real, scene(1100), null, 1100, still, width)
        var t = ready(c, 1200)
        assertEquals(Stage.AWAIT_LEFT, c.stage)
        t += 100; c.observe(danger, scene(t), null, t, still, width)
        assertEquals(Stage.DANGER, c.stage)
        c.requestContinue(t); c.confirmCameraDirection(t)
        assertEquals("Continuar/Pronto não pulam um perigo", Stage.DANGER, c.stage)
        // Oscilação curta do perigo não gera falas repetidas.
        t += 300; c.observe(real, scene(t), null, t, still, width)
        t += 300; c.observe(danger, scene(t), null, t, still, width)
        assertEquals(1, r.stages.count { it == Stage.DANGER })
        t = hold(c, t, 2600)
        assertEquals(Stage.AWAIT_LEFT, c.stage)
        assertEquals(1, r.stages.count { it == Stage.AWAIT_RIGHT })
        // Mesmo com cobertura atestada, um perigo na sessão impede conclusão favorável.
        t = ready(c, t + 100, attested)
        t = hold(c, t, 5500, attested)
        hold(c, t, risk = attested)
        assertEquals(Stage.INCONCLUSIVE, c.stage)
    }

    @Test fun crosswalkConfirmedMidSequenceDoesNotRestartSteps() {
        val r = Recorder(); val c = r.build()
        c.start(1000)
        c.observe(real, scene(1100, CrosswalkStatus.UNCERTAIN), null, 1100, still, width)
        val t = ready(c, 1200)
        assertEquals(Stage.AWAIT_LEFT, c.stage)
        c.observe(real, scene(t + 100), null, t + 100, still, width)
        assertTrue(c.hasCrosswalk)
        assertEquals(Stage.AWAIT_LEFT, c.stage)
        assertEquals(1, r.stages.count { it == Stage.AWAIT_RIGHT })
    }

    @Test fun restartBeginsCleanFromTheRightAndCancelStops() {
        val c = CrossingVerification()
        c.start(1000)
        c.observe(real, scene(1100), null, 1100)
        for (t in 1200L..1700L step 100) c.requestContinue(t)
        assertEquals(Stage.INCONCLUSIVE, c.stage)
        c.restart(2000)
        assertEquals(Stage.AWAIT_RIGHT, c.stage)
        assertFalse(c.cannotVerifyDirection)
        assertEquals(DirectionStatus.REQUESTED, c.status(Direction.RIGHT))
        assertEquals(DirectionStatus.NOT_REQUESTED, c.status(Direction.LEFT))
        c.cancel()
        assertEquals(Stage.IDLE, c.stage)
        c.observe(real, scene(3000), null, 3000)
        assertEquals(Stage.IDLE, c.stage)
    }

    @Test fun isolatedLateFrameDoesNotEndTheSequenceButCameraInvalidationDoes() {
        val c = CrossingVerification()
        c.start(1000); c.observe(real, scene(1100), null, 1100)
        c.confirmCameraDirection(1200)
        c.degrade()
        c.observe(real.copy(confidence = SceneConfidence(false, false, false, 1600)), scene(1300), null, 1300)
        assertEquals(Stage.VERIFY_RIGHT, c.stage)
        c.invalidate()
        assertEquals(Stage.INCONCLUSIVE, c.stage)
        assertTrue(c.finished)
    }

    @Test fun externalCameraWithoutMotionEstimateStillProgressesUnverified() {
        // USB sem fluxo óptico confiável: nenhuma evidência inventada, mas sem travar.
        val c = CrossingVerification()
        c.start(0)
        for (t in 100L..80000L step 200) c.observe(real, scene(t), null, t, null, width)
        assertEquals(Stage.INCONCLUSIVE, c.stage)
        for (d in Direction.values()) assertNotEquals(DirectionStatus.MOTION_DETECTED, c.status(d))
    }
    @Test fun repeatedInvalidationWithoutCameraChangesStageOnlyOnce() {
        // Câmera sem imagem: o watchdog/fluxo pode invalidar várias vezes; só a primeira muda a etapa.
        val r = Recorder(); val c = r.build()
        c.start(1000); c.observe(real, scene(1100), null, 1100)
        assertTrue(c.invalidate())
        repeat(30) { assertFalse(c.invalidate()) }
        assertEquals(1, r.stages.count { it == Stage.INCONCLUSIVE })
        val idle = CrossingVerification(); assertFalse(idle.invalidate())
    }
}
