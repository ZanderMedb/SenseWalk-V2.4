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
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE,c.stage)
        c.observe(clear,scene(4000),road(4000),4000)
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

    @Test fun roadAgeMustIncludeCurrentInferenceLatency() {
        val c = CrossingVerification()
        c.start(1000); c.observe(clear,scene(1100),road(1100),1100)
        window(c,2000); window(c,4000)
        c.confirmCameraDirection(6000)
        val delayed = CrossingRiskAnalyzer.analyze(emptyList(),640,360,
            frameAgeMs=1400,coverageVerified=true,cameraReliable=true)
        // Contexto 500 ms anterior à captura + 1400 ms até a decisão = 1900 ms.
        for (t in 6000L..7500L step 500) c.observe(delayed,scene(t),road(t-500),t)
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE,c.stage)
    }
}
