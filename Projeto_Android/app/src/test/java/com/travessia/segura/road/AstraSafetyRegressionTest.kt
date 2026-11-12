package com.travessia.segura.road

import com.travessia.segura.detection.RawDetection
import com.travessia.segura.detection.VeiculoInfo
import org.junit.Assert.*
import org.junit.Test

/** Sintético: testa contratos e transições, não precisão visual ou movimento real. */
class AstraSafetyRegressionTest {
    private val stripe = RawDetection(80f,150f,550f,310f,8,"crosswalk",.8f)
    private val clear = CrossingRiskAssessment(emptySet(),emptyList(),SceneConfidence(true,true,true))
    private fun scene(t: Long, signal: SignalApplicability = SignalApplicability.NOT_APPLICABLE_CONFIRMED) =
        SceneEvidence(CrosswalkStatus.CONFIRMED,stripe,signal,t)
    private fun road(t: Long) = RoadSegmentationResult(.4f,.2f,true,true,true,null,t,.01f)
    private fun vehicle(x: Float, width: Float, classification: String) = VeiculoInfo("car",.8f,x,70f,
        classification,40f,floatArrayOf(x-width/2,50f,x+width/2,90f),width,1)
    private fun window(c: CrossingVerification, start: Long, risk: CrossingRiskAssessment = clear) {
        c.confirmCameraDirection(start)
        for (i in 0..3) {
            val t = start + i * 500
            c.observe(risk,scene(t),road(t),t)
        }
    }
    private fun atFront(): CrossingVerification = CrossingVerification().also { c ->
        c.start(1000); c.observe(clear,scene(1100),road(1100),1100)
        window(c,2000); window(c,4000)
    }

    @Test fun `small uncertain vehicle is not evidence of absence of conflict`() {
        val risk = CrossingRiskAnalyzer.analyze(listOf(vehicle(320f,20f,"MOVIMENTO_INCERTO")),640,360,
            coverageVerified=true, cameraReliable=true)
        assertFalse(risk.conclusive)
    }
    @Test fun `approach at side must not be ignored just because outside central corridor`() {
        val risk = CrossingRiskAnalyzer.analyze(listOf(vehicle(40f,20f,"APROXIMANDO")),640,360,
            coverageVerified=true, cameraReliable=true)
        assertTrue(risk.danger)
    }
    @Test fun `moving lateral trajectory cannot be cleared by small box size`() {
        assertFalse(CrossingRiskAnalyzer.analyze(listOf(vehicle(320f,20f,"EM_MOVIMENTO")),640,360,
            coverageVerified=true, cameraReliable=true).conclusive)
    }
    @Test fun `pre button frames must not count toward a direction`() {
        val c = CrossingVerification()
        c.start(1000); c.observe(clear,scene(1100),road(1100),1100)
        c.confirmCameraDirection(9000)
        for (t in listOf(2000L,2500L,3000L,3500L)) c.observe(clear,scene(t),road(t),t)
        assertEquals(CrossingVerification.Stage.VERIFY_RIGHT,c.stage)
    }
    @Test fun `button never certifies physical direction and all three windows need coverage`() {
        val c = CrossingVerification()
        c.start(1000); c.observe(clear,scene(1100),road(1100),1100)
        val unknownCoverage = clear.copy(confidence=SceneConfidence(true,true,false))
        c.confirmCameraDirection(2000)
        assertFalse(c.directionConfirmed)
        for (t in 2000L..3500L step 500) c.observe(unknownCoverage,scene(t),road(t),t)
        window(c,4000); window(c,6000)
        assertNotEquals(CrossingVerification.Stage.FAVORABLE_OBSERVATION,c.stage)
    }
    @Test fun `no traffic light detection does not prove no applicable signal`() {
        val c = atFront(); c.confirmCameraDirection(6000)
        for (t in 6000L..7500L step 500) c.observe(clear,scene(t,SignalApplicability.NOT_OBSERVED),road(t),t)
        assertNotEquals(CrossingVerification.Stage.FAVORABLE_OBSERVATION,c.stage)
    }
    @Test fun `brief occlusion is uncertainty even if visible list is empty`() {
        assertFalse(CrossingRiskAnalyzer.analyze(emptyList(),640,360,coverageVerified=true,
            cameraReliable=true,unobservedVehicles=1).conclusive)
    }
    @Test fun `vehicle signal alone does not establish an unsignalized crossing`() {
        val c = atFront(); c.confirmCameraDirection(6000)
        for (t in 6000L..7500L step 500) c.observe(clear,scene(t,SignalApplicability.VEHICLE_ONLY),road(t),t)
        assertNotEquals(CrossingVerification.Stage.FAVORABLE_OBSERVATION,c.stage)
    }
    @Test fun `first frame after favorable must invalidate immediately on information loss`() {
        val c = atFront(); window(c,6000)
        c.observe(clear.copy(confidence=SceneConfidence(false,false,false)),scene(8000),null,8000)
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE,c.stage)
    }
    @Test fun `crosswalk confirmation needs spatial continuity not alternating stripes`() {
        val evidence = SceneEvidenceTracker()
        evidence.update(listOf(stripe),1000,640)
        val unrelated = stripe.copy(x1=0f,x2=40f,y1=20f,y2=60f)
        assertEquals(CrosswalkStatus.UNCERTAIN,evidence.update(listOf(unrelated),1500,640).crosswalk)
    }
    @Test fun `green timer must reset across missing frames even if next color matches`() {
        val evidence = SceneEvidenceTracker()
        val green = RawDetection(280f,20f,310f,80f,9,"pedestrian-light-green",.9f)
        evidence.update(listOf(stripe,green),1000,640)
        evidence.update(listOf(stripe,green),1500,640)
        evidence.update(listOf(stripe,green),2200,640)
        // Delay less than crosswalk hold, but not a distinct green transition: timer is observation only.
        val regressed = evidence.update(listOf(stripe,green),1400,640)
        assertNotEquals(SignalApplicability.PEDESTRIAN_GREEN,regressed.signal)
    }
}
