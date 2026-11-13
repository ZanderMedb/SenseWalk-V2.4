package com.travessia.segura.road

import com.travessia.segura.detection.RawDetection
import com.travessia.segura.detection.VeiculoInfo
import com.travessia.segura.tracking.VehicleMotionRules
import org.junit.Assert.*
import org.junit.Test

class CrossingVerificationTest {
    private val crosswalk = RawDetection(80f,150f,550f,310f,8,"crosswalk",.8f)
    // Cobertura e ausência de sinal ATESTADAS são entradas sintéticas: não existem no adaptador Android.
    private fun scene(t: Long, signal: SignalApplicability = SignalApplicability.NOT_APPLICABLE_CONFIRMED) =
        SceneEvidence(CrosswalkStatus.CONFIRMED,crosswalk,signal,t)
    private fun road(t: Long) = RoadSegmentationResult(.4f,.2f,true,true,true,null,t,.01f)
    private val clear = CrossingRiskAssessment(emptySet(),emptyList(),SceneConfidence(true,true,true))
    private fun samples(c: CrossingVerification, from: Long, signal: SignalApplicability = SignalApplicability.NOT_APPLICABLE_CONFIRMED,
        risk: CrossingRiskAssessment = clear) {
        for (i in 0..3) {
            val t = from + 500L * i
            c.observe(risk,scene(t,signal),road(t),t)
        }
    }
    private val uncovered = CrossingRiskAssessment(emptySet(),emptyList(),SceneConfidence(true,true,false))

    @Test fun lateralVerificationNeedsButtonAndFreshDistinctSamples() {
        val c = CrossingVerification()
        c.start(1000)
        c.observe(clear,scene(1100),road(1100),1100)
        assertEquals(CrossingVerification.Stage.AWAIT_RIGHT,c.stage)
        // Sem cobertura comprovada, frames não avançam a etapa. (Com cobertura de sensor ela avança sozinha.)
        samples(c,1200,risk=uncovered)
        assertEquals(CrossingVerification.Stage.AWAIT_RIGHT,c.stage)
        c.confirmCameraDirection(3000); samples(c,3100)
        assertEquals(CrossingVerification.Stage.AWAIT_LEFT,c.stage)
        c.confirmCameraDirection(5000); samples(c,5100)
        assertEquals(CrossingVerification.Stage.AWAIT_FRONT,c.stage)
        c.confirmCameraDirection(7000); samples(c,7100)
        assertEquals(CrossingVerification.Stage.FAVORABLE_OBSERVATION,c.stage)
        c.observe(clear,scene(9000),road(9000),9000)
        assertEquals(CrossingVerification.Stage.MONITORING,c.stage)
        val blocked = clear.copy(reasons=setOf(RiskReason.CROSSWALK_OCCUPIED))
        c.observe(blocked,scene(9500),road(9500),9500)
        assertEquals(CrossingVerification.Stage.DANGER,c.stage)
    }

    @Test fun oldSidesAndRedSignalCannotProduceFavorableObservation() {
        val c = CrossingVerification()
        c.start(1000); c.observe(clear,scene(1100),road(1100),1100)
        c.confirmCameraDirection(2000); samples(c,2100)
        c.confirmCameraDirection(4000); samples(c,4100)
        c.confirmCameraDirection(19000); samples(c,19100)
        assertNotEquals(CrossingVerification.Stage.FAVORABLE_OBSERVATION,c.stage)
        c.observe(clear,scene(21000,SignalApplicability.PEDESTRIAN_RED),road(21000),21000)
        assertEquals(CrossingVerification.Stage.DANGER,c.stage)
    }

    @Test fun stationaryGrowthIsNotIndependentApproachButCentralObstacleIsDanger() {
        assertFalse(VehicleMotionRules.independentlyApproaching(0f,.9f,true,8,true,false,30f))
        assertFalse(VehicleMotionRules.independentlyApproaching(80f,.3f,true,8,false,true,30f))
        assertTrue(VehicleMotionRules.independentlyApproaching(80f,0f,true,8,true,false,30f))
        val parked = VeiculoInfo("car",.9f,320f,250f,"PARADO",0f,
            floatArrayOf(215f,120f,440f,350f),225f,1)
        val risk = CrossingRiskAnalyzer.analyze(listOf(parked),640,360)
        assertTrue(risk.reasons.contains(RiskReason.OBSTACLE_AHEAD))
        assertFalse(risk.reasons.contains(RiskReason.CONFLICTING_APPROACH))
    }

    @Test fun distantVehicleOutsideCorridorDoesNotBlockButStaleFrameDoes() {
        val distant = VeiculoInfo("car",.8f,40f,90f,"PARADO",0f,
            floatArrayOf(15f,50f,60f,100f),45f,4)
        assertTrue(CrossingRiskAnalyzer.analyze(listOf(distant),640,360,frameAgeMs=100).reasons.isEmpty())
        assertTrue(CrossingRiskAnalyzer.analyze(listOf(distant),640,360,frameAgeMs=1600)
            .reasons.contains(RiskReason.STALE_IMAGE))
    }

    @Test fun uncertainVehicleInPathGetsPotentialAlertWithoutClaimingApproach() {
        val unknown = VeiculoInfo("car",.85f,320f,230f,"MOVIMENTO_INCERTO",0f,
            floatArrayOf(260f,120f,360f,250f),100f,2)
        val risk = CrossingRiskAnalyzer.analyze(listOf(unknown),640,360)
        assertTrue(risk.reasons.contains(RiskReason.POTENTIAL_TRAFFIC_CONFLICT))
        assertFalse(risk.reasons.contains(RiskReason.CONFLICTING_APPROACH))
    }

    @Test fun crosswalkNeedsTwoSeparatedDetectionsAndVehicleSignalIsNotPedestrianSignal() {
        val t = SceneEvidenceTracker()
        val vehicleGreen = RawDetection(200f,30f,220f,60f,5,"traffic-light-green",.9f)
        assertEquals(CrosswalkStatus.UNCERTAIN,t.update(listOf(crosswalk),1000,640).crosswalk)
        assertEquals(CrosswalkStatus.CONFIRMED,t.update(listOf(crosswalk,vehicleGreen),1500,640).crosswalk)
        assertEquals(SignalApplicability.VEHICLE_ONLY,t.update(listOf(vehicleGreen),1800,640).signal)
        assertEquals(CrosswalkStatus.UNCERTAIN,t.update(emptyList(),2500,640).crosswalk)
    }

    @Test fun roadWithoutCrosswalkCannotBecomeFavorableEvenAfterThreeDirections() {
        val c = CrossingVerification()
        fun noStripe(t: Long) = SceneEvidence(CrosswalkStatus.UNCERTAIN,null,SignalApplicability.NOT_OBSERVED,t)
        fun observe(from: Long) = (0..3).forEach { i ->
            val t = from + i * 500L
            c.observe(clear,noStripe(t),road(t),t)
        }
        c.start(1000); c.observe(clear,noStripe(1100),road(1100),1100)
        c.confirmCameraDirection(2000); observe(2100)
        c.confirmCameraDirection(4000); observe(4100)
        c.confirmCameraDirection(6000); observe(6100)
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE,c.stage)
        c.observe(clear,noStripe(9000),road(9000),9000)
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE,c.stage)
    }

    @Test fun crosswalkConfirmedAfterConsentUpgradesBeforeCheckingSides() {
        val c = CrossingVerification()
        c.start(1000)
        c.observe(clear,SceneEvidence(CrosswalkStatus.UNCERTAIN,null,SignalApplicability.NOT_OBSERVED,1100),road(1100),1100)
        assertFalse(c.hasCrosswalk)
        c.observe(clear,scene(1600),road(1600),1600)
        assertTrue(c.hasCrosswalk)
        // Faixa confirmada não reinicia a sequência: continua na direita (frame coberto já começa a observar).
        assertEquals(CrossingVerification.Direction.RIGHT,c.currentDirection)
    }

    @Test fun pedestrianLightRequiresAssociationAndGreenClockResetsOnLoss() {
        val t = SceneEvidenceTracker()
        val green = RawDetection(280f,20f,310f,80f,9,"pedestrian-light-green",.9f)
        val farAway = green.copy(x1=600f,x2=630f)
        t.update(listOf(crosswalk),1000,640)
        assertEquals(SignalApplicability.UNCERTAIN,t.update(listOf(crosswalk,farAway),1500,640).signal)
        val observed = t.update(listOf(crosswalk,green),2000,640)
        assertEquals(SignalApplicability.PEDESTRIAN_GREEN,observed.signal)
        assertEquals(2000L,observed.greenObservedAtMs)
        val lost = t.update(listOf(crosswalk),2300,640)
        assertEquals(-1L,lost.greenObservedAtMs)
        assertEquals(2500L,t.update(listOf(crosswalk,green),2500,640).greenObservedAtMs)
    }
}
