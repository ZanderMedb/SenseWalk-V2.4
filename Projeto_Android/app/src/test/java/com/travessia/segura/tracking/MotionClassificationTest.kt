package com.travessia.segura.tracking

import com.travessia.segura.detection.VeiculoInfo
import com.travessia.segura.road.CrossingRiskAnalyzer
import com.travessia.segura.road.RiskReason
import com.travessia.segura.vision.BackgroundSimilarity
import org.junit.Assert.*
import org.junit.Test

/** Movimento no plano da imagem, ~10 Hz, 640x360. Sintético: não prova comportamento em vídeo real. */
class MotionClassificationTest {
    private data class Cam(val dx: Float = 0f, val scale: Float = 1f, val rot: Float = 0f, val parallax: Boolean = false)

    /** Simula [frames] amostras: posição/largura do veículo e movimento do fundo por frame. */
    private fun run(frames: Int, cam: (Int) -> Cam = { Cam() }, cx: (Int) -> Float, width: (Int) -> Float = { 80f },
        gapAt: Int = -1): Pair<Rastreador, String> {
        val r = Rastreador { Rastreador.Settings() }
        var t = 1.0
        for (i in 0 until frames) {
            if (i == gapAt) t += 2.0 // perda de tracking > 1,5 s
            val c = cam(i)
            r.novoFrameCamera(c.dx, 0f, .9f, .35f, c.scale, c.rot, c.parallax, t)
            r.atualizar(1, cx(i), 200f, t, width(i).toInt())
            t += .1
        }
        return r to r.classificar(1, 640, 360).first
    }

    @Test fun parkedCarWithStillCameraIsStopped() {
        assertEquals(MotionClass.PARADO, run(15, cx = { 320f }).second)
    }

    @Test fun stillBoxForTooShortIsNotYetCalledStopped() {
        assertEquals(MotionClass.MOVIMENTO_INCERTO, run(7, cx = { 320f }).second)
    }

    @Test fun parkedCarWhileUserWalksTowardItIsNotApproach() {
        // Fundo expande 1%/frame; o carro próximo cresce mais rápido (paralaxe de profundidade).
        val cls = run(15, cam = { Cam(scale = 1.01f) }, cx = { 320f }, width = { 80f * Math.pow(1.03, it.toDouble()).toFloat() }).second
        assertNotEquals(MotionClass.POSSIVEL_APROXIMACAO, cls)
        assertEquals(MotionClass.MOVIMENTO_INCERTO, cls)
    }

    @Test fun parkedCarWhileCameraRotatesIsUncertain() {
        assertEquals(MotionClass.MOVIMENTO_INCERTO, run(15, cam = { Cam(dx = 6f, rot = 2f) }, cx = { 320f + 6f * it }).second)
    }

    @Test fun parkedCarWithLateralCameraMotionIsCompensated() {
        assertEquals(MotionClass.PARADO, run(15, cam = { Cam(dx = 6f) }, cx = { 200f + 6f * it }).second)
    }

    @Test fun lateralCameraMotionWithParallaxIsRejected() {
        assertEquals(MotionClass.MOVIMENTO_INCERTO,
            run(15, cam = { Cam(dx = 6f, parallax = true) }, cx = { 200f + 9f * it }).second)
    }

    @Test fun carApproachingStillCameraIsPossibleApproachAndRaisesAnAlert() {
        val (r, cls) = run(15, cx = { 320f }, width = { 80f * Math.pow(1.04, it.toDouble()).toFloat() })
        assertEquals(MotionClass.POSSIVEL_APROXIMACAO, cls)
        assertTrue(r.avaliar(1, 640, 360).confidence in .3f..1f)
        val car = VeiculoInfo("car", .9f, 320f, 200f, cls, 0f, floatArrayOf(250f, 170f, 390f, 230f), 140f, 1)
        val risk = CrossingRiskAnalyzer.analyze(listOf(car), 640, 360, cameraReliable = true)
        assertTrue(risk.reasons.contains(RiskReason.POTENTIAL_TRAFFIC_CONFLICT))
        assertTrue(risk.danger)
    }

    @Test fun carRecedingIsOnlyPossibleRecedingAndNeverConclusive() {
        val cls = run(15, cx = { 320f }, width = { 120f * Math.pow(.96, it.toDouble()).toFloat() }).second
        assertEquals(MotionClass.POSSIVEL_AFASTAMENTO, cls)
        val car = VeiculoInfo("car", .9f, 320f, 120f, cls, 0f, floatArrayOf(290f, 100f, 350f, 140f), 60f, 1)
        val risk = CrossingRiskAnalyzer.analyze(listOf(car), 640, 360, coverageVerified = true, cameraReliable = true)
        assertTrue(risk.reasons.contains(RiskReason.MOTION_UNCERTAIN))
        assertFalse("retração não é prova de afastamento seguro", risk.conclusive)
    }

    @Test fun carCrossingLaterallyTowardTheCentreIsPotentialConflict() {
        assertEquals(MotionClass.POTENCIAL_CONFLITO, run(15, cx = { 60f + 8f * it }).second)
    }

    @Test fun carMovingAwayFromTheCentreLaterallyIsMoving() {
        assertEquals(MotionClass.EM_MOVIMENTO, run(15, cx = { 400f + 8f * it }).second)
    }

    @Test fun carStartingToMoveLeavesTheStoppedState() {
        val cls = run(30, cx = { if (it < 15) 100f else 100f + 10f * (it - 15) }).second
        assertNotEquals(MotionClass.PARADO, cls)
        assertTrue(cls in MotionClass.MOVING)
    }

    @Test fun cameraShakeWithoutVehicleMotionStaysStopped() {
        val shake = { i: Int -> if (i % 2 == 0) 2f else -2f }
        var pos = 320f
        val cls = run(20, cam = { Cam(dx = shake(it)) }, cx = { i -> pos += if (i == 0) 0f else shake(i); pos }).second
        assertEquals(MotionClass.PARADO, cls)
    }

    @Test fun trackingGapRestartsTheHistory() {
        assertEquals(MotionClass.MOVIMENTO_INCERTO, run(14, cx = { 320f }, gapAt = 10).second)
    }

    // ---- Compensação do fundo (BackgroundSimilarity) ----
    private fun grid(f: (Float, Float) -> Pair<Float, Float>) = (0 until 6).flatMap { gx -> (0 until 4).map { gy ->
        val x = 15f + 25f * gx; val y = 10f + 22f * gy; val (dx, dy) = f(x, y)
        BackgroundSimilarity.Match(x, y, dx, dy) } }

    @Test fun pureTranslationIsATranslationModel() {
        assertEquals(BackgroundSimilarity.Model.TRANSLATION, BackgroundSimilarity.fit(grid { _, _ -> 3f to 0f }, 160, 90)!!.model)
    }

    @Test fun rotationAndZoomAreSimilarityNotTranslation() {
        val r = Math.toRadians(3.0)
        val rot = BackgroundSimilarity.fit(grid { x, y ->
            val px = x - 80f; val py = y - 45f
            ((px * Math.cos(r) - py * Math.sin(r)) - px).toFloat() to ((px * Math.sin(r) + py * Math.cos(r)) - py).toFloat()
        }, 160, 90)!!
        assertEquals(BackgroundSimilarity.Model.SIMILARITY, rot.model)
        assertEquals(3f, rot.rotationDegrees, .3f)
        val zoom = BackgroundSimilarity.fit(grid { x, y -> (x - 80f) * .03f to (y - 45f) * .03f }, 160, 90)!!
        assertEquals(BackgroundSimilarity.Model.SIMILARITY, zoom.model)
        assertEquals(1.03f, zoom.scale, .005f)
        assertFalse(zoom.translationSupported)
    }

    @Test fun twoDepthLayersAreReportedAsParallax() {
        val fit = BackgroundSimilarity.fit(grid { _, y -> (if (y < 45f) 1f else 7f) to 0f }, 160, 90)!!
        assertTrue(fit.parallaxSuspected)
        assertEquals(BackgroundSimilarity.Model.PARALLAX_SUSPECTED, fit.model)
        assertFalse(fit.translationSupported)
    }

    @Test fun independentVehicleIsAnOutlierNotTheBackground() {
        val points = grid { x, y -> if (x > 100f && y > 50f) 15f to 0f else 2f to 0f }
        val fit = BackgroundSimilarity.fit(points, 160, 90)!!
        assertTrue(fit.translationSupported)
    }

    @Test fun tooFewPointsGiveNoModel() {
        assertNull(BackgroundSimilarity.fit(grid { _, _ -> 1f to 0f }.take(5), 160, 90))
    }
}
