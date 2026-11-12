package com.travessia.segura.road

import org.junit.Assert.*
import org.junit.Test

class SignalAndGestureValidationTest {
    @Test fun briefCrosswalkHitsCannotMatureDuringMissingDetections() {
        val evidence = SceneEvidenceTracker()
        val stripe = com.travessia.segura.detection.RawDetection(80f,150f,550f,310f,8,"crosswalk",.8f)
        evidence.update(listOf(stripe),1000,640)
        evidence.update(listOf(stripe),1100,640)
        assertEquals(CrosswalkStatus.UNCERTAIN,evidence.update(emptyList(),1500,640).crosswalk)
    }
    private fun complete(signal: SignalApplicability, associated: Boolean = false): CrossingVerification {
        val c = CrossingVerification()
        val risk = CrossingRiskAssessment(emptySet(),emptyList(),SceneConfidence(true,true,true))
        fun observe(t: Long) = c.observe(risk,SceneEvidence(CrosswalkStatus.CONFIRMED,null,signal,t,1000,associated),
            RoadSegmentationResult(.4f,.2f,true,true,true,null,t,.01f),t)
        c.start(1000); observe(1100)
        for (start in listOf(2000L,4000L,6000L)) {
            c.confirmCameraDirection(start)
            for (t in start..start+1500 step 500) observe(t)
        }
        return c
    }
    @Test fun geometricGreenAloneCannotConcludeEvenWithSyntheticCoverage() {
        val c = complete(SignalApplicability.PEDESTRIAN_GREEN)
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE,c.stage)
        assertFalse(c.handSignalApplicable)
    }
    @Test fun independentlyAssociatedGreenStillRequiresAllSidesAndDoesNotRequestHand() {
        val c = complete(SignalApplicability.PEDESTRIAN_GREEN,true)
        assertEquals(CrossingVerification.Stage.FAVORABLE_OBSERVATION,c.stage)
        assertFalse(c.handSignalApplicable)
    }
    @Test fun handRequiresConfirmedSignalAbsenceAndIsClearedOnInvalidation() {
        assertFalse(complete(SignalApplicability.NOT_OBSERVED).handSignalApplicable)
        val c = complete(SignalApplicability.NOT_APPLICABLE_CONFIRMED)
        assertTrue(c.handSignalApplicable)
        c.invalidate()
        assertFalse(c.handSignalApplicable)
    }
}
