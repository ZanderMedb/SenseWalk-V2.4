package com.travessia.segura.road

import org.junit.Assert.*
import org.junit.Test

/** Contraprovas da auditoria. Cobertura atestada é injetada, não medida pelo Android. */
class AstraAuditFindingsTest {
    private val clear = CrossingRiskAssessment(emptySet(), emptyList(), SceneConfidence(true,true,true))
    private fun scene(t: Long, signal: SignalApplicability = SignalApplicability.NOT_APPLICABLE_CONFIRMED) =
        SceneEvidence(CrosswalkStatus.CONFIRMED, null, signal, t)
    private fun road(t: Long) = RoadSegmentationResult(.4f,.2f,true,true,true,null,t,.01f)
    private fun window(c: CrossingVerification, start: Long, risk: CrossingRiskAssessment = clear) {
        c.confirmCameraDirection(start)
        for (t in start..start+1500 step 500) c.observe(risk,scene(t),road(t),t)
    }
    private fun favorable() = CrossingVerification().also {
        it.start(1000); it.observe(clear,scene(1100),road(1100),1100)
        window(it,2000); window(it,4000); window(it,6000)
        assertEquals(CrossingVerification.Stage.FAVORABLE_OBSERVATION,it.stage)
    }

    @Test fun missingOrientationCannotCompleteEvenTheFirstSide() {
        val c = CrossingVerification()
        c.start(1000); c.observe(clear,scene(1100),road(1100),1100)
        window(c,2000,clear.copy(confidence=SceneConfidence(true,true,false)))
        // Não verificada, mas o usuário não fica preso: segue para a esquerda sem voltar à direita.
        assertEquals(CrossingVerification.DirectionStatus.COVERAGE_INCONCLUSIVE,c.status(CrossingVerification.Direction.RIGHT))
        assertTrue(c.cannotVerifyDirection)
        assertEquals(CrossingVerification.Stage.AWAIT_LEFT,c.stage)
        window(c,4000); window(c,6000)
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE,c.stage)
        c.observe(clear,scene(9000),road(9000),9000)
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE,c.stage)
    }

    @Test fun monitoringExpiresLateralEvidenceEvenWithFreshFrontFrames() {
        val c = favorable()
        for (t in 8000L..16500L step 500) c.observe(clear,scene(t),road(t),t)
        assertNotEquals(CrossingVerification.Stage.MONITORING,c.stage)
    }

    @Test fun monitoringCannotKeepFavorableAfterCoverageLoss() {
        val c = favorable()
        c.observe(clear.copy(confidence=SceneConfidence(true,true,false)),scene(8000),road(8000),8000)
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE,c.stage)
    }

    @Test fun monitoringCannotReplaceVerifiedSignalAbsenceWithMissingDetection() {
        val c = favorable()
        c.observe(clear,scene(8000,SignalApplicability.NOT_OBSERVED),road(8000),8000)
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE,c.stage)
    }

    @Test fun mismatchedSceneTimestampCannotCompleteWindow() {
        val c = CrossingVerification()
        c.start(1000); c.observe(clear,scene(1100),road(1100),1100)
        c.confirmCameraDirection(2000)
        c.observe(clear,scene(1900),road(2000),2000)
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE,c.stage)
    }

    /** Antes: idade da máscara FFNet. A FFNet não decide mais a travessia; a latência continua contando
     *  na expiração das evidências laterais do YOLO. */
    @Test fun sideEvidenceAgeMustIncludeCurrentInferenceLatency() {
        val c = CrossingVerification()
        c.start(1000); c.observe(clear,scene(1100),road(1100),1100)
        window(c,2000); window(c,4000)
        c.confirmCameraDirection(14000)
        val delayed = CrossingRiskAnalyzer.analyze(emptyList(),640,360,
            frameAgeMs=1400,coverageVerified=true,cameraReliable=true)
        // Direita registrada em 3500. Captura em 15500 -> 12000 ms (válido); decisão em 16900 -> expirado.
        for (t in 14000L..15500L step 500) c.observe(delayed,scene(t),null,t)
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE,c.stage)
    }

    @Test fun ffnetMaskIsNotRequiredForTheGuidedSequence() {
        val c = CrossingVerification()
        c.start(1000); c.observe(clear,scene(1100),null,1100)
        for (start in listOf(2000L,4000L,6000L)) {
            c.confirmCameraDirection(start)
            for (t in start..start+1500 step 500) c.observe(clear,scene(t),null,t)
        }
        // Sintético com cobertura atestada: a ausência de máscara FFNet não bloqueia o YOLO.
        assertEquals(CrossingVerification.Stage.FAVORABLE_OBSERVATION,c.stage)
    }
}
