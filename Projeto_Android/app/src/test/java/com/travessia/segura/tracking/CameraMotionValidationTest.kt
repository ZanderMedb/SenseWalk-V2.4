package com.travessia.segura.tracking

import com.travessia.segura.vision.BackgroundSimilarity
import com.travessia.segura.detection.RawDetection
import com.travessia.segura.detection.VeiculoInfo
import com.travessia.segura.road.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class CameraMotionValidationTest {
    private fun matches(angle: Double, scale: Double) = (10..110 step 20).flatMap { y ->
        (10..150 step 20).map { x ->
            val px = x-80.0; val py = y-60.0
            val a = Math.toRadians(angle)
            BackgroundSimilarity.Match(x.toFloat(),y.toFloat(),
                (scale*(cos(a)*px-sin(a)*py)-px+1).toFloat(),
                (scale*(sin(a)*px+cos(a)*py)-py-2).toFloat())
        }
    }
    @Test fun distributedTranslationSupportsExistingCompensation() {
        val fit = BackgroundSimilarity.fit(matches(0.0,1.0),160,120)!!
        assertTrue(fit.translationSupported); assertEquals(0f,fit.rotationDegrees,1e-4f)
    }
    @Test fun parkedVehicleUnderRotationCannotBecomeApproaching() {
        val fit = BackgroundSimilarity.fit(matches(3.0,1.0),160,120)!!
        assertEquals(3f,fit.rotationDegrees,.01f); assertFalse(fit.translationSupported)
        uncertainUnderCamera(fit)
    }
    @Test fun parkedVehicleUnderZoomCannotBecomeApproaching() {
        val fit = BackgroundSimilarity.fit(matches(0.0,1.03),160,120)!!
        assertEquals(1.03f,fit.scale,.001f); assertFalse(fit.translationSupported)
        uncertainUnderCamera(fit)
    }
    @Test fun conflictingDepthLayersDoNotCertifySingleBackgroundTransform() {
        val points = matches(0.0,1.0).mapIndexed { i,p -> p.copy(dx=p.dx+(i%3-1)*4) }
        val fit = BackgroundSimilarity.fit(points,160,120)
        assertFalse(fit?.translationSupported == true)
    }
    private fun uncertainUnderCamera(fit: BackgroundSimilarity.Fit) {
        val tracker = Rastreador { Rastreador.Settings() }
        for (i in 0..12) {
            tracker.novoFrameCamera(1f,-2f,if (fit.translationSupported) 1f else 0f,.35f)
            tracker.atualizar(1,40f+i*5,60f,1+i*.1,20+i)
        }
        assertEquals("MOVIMENTO_INCERTO",tracker.classificar(1,160,120).first)
    }
    @Test fun sweptTrajectoryFindsIntermediateConflictEvenWhenEndpointPassesCrosswalk() {
        val trajectory = ImageTrajectory(500f,0f,0f,true)
        assertTrue(trajectory.mayIntersect(floatArrayOf(0f,100f,30f,130f),floatArrayOf(100f,90f,140f,140f)))
        assertFalse(trajectory.mayIntersect(floatArrayOf(0f,200f,30f,230f),floatArrayOf(100f,90f,140f,140f)))
        val car = VeiculoInfo("car",.8f,15f,115f,"EM_MOVIMENTO",500f,floatArrayOf(0f,100f,30f,130f),30f,1,trajectory)
        val risk = CrossingRiskAnalyzer.analyze(listOf(car),640,360,RawDetection(100f,90f,140f,140f,8,"crosswalk",.8f))
        assertTrue(RiskReason.POTENTIAL_TRAFFIC_CONFLICT in risk.reasons)
        assertFalse(RiskReason.CONFLICTING_APPROACH in risk.reasons)
    }
    @Test fun shrinkingBoxDoesNotCertifyStationaryOrSafe() {
        val tracker = Rastreador { Rastreador.Settings() }
        for (i in 0..12) {
            tracker.novoFrameCamera(0f,0f,1f,.35f)
            tracker.atualizar(1,100f,100f,1+i*.1,100-i*4)
        }
        assertEquals("SHRINKING",tracker.trajectory(1,640,480).scaleTrend)
        assertEquals("MOVIMENTO_INCERTO",tracker.classificar(1).first)
    }
    @Test fun quantizationLookupPreservesEveryPossibleChannel() {
        val scale = .00392036699f
        val table = RoadTensorCodec.quantizationTable(scale,0)
        for (c in 0..255) assertEquals(RoadTensorCodec.quantize(c,scale,0),table[c].toInt() and 255)
    }
}
