package com.travessia.segura.orientation

import com.travessia.segura.orientation.OrientationCoverageTracker.Direction
import com.travessia.segura.orientation.OrientationCoverageTracker.Status
import com.travessia.segura.road.CrossingRiskAssessment
import com.travessia.segura.road.CrossingVerification
import com.travessia.segura.road.CrosswalkStatus
import com.travessia.segura.road.SceneConfidence
import com.travessia.segura.road.SceneEvidence
import com.travessia.segura.road.SignalApplicability
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Sensores simulados (rumo por amostra a 50 Hz). Não substitui teste físico no aparelho. */
class OrientationCoverageTest {
    /** Matriz aparelho->mundo cujo eixo óptico traseiro (−Z) aponta no rumo [deg] e inclinação [pitchDeg]. */
    private fun matrix(deg: Double, pitchDeg: Double = 0.0): FloatArray {
        val h = Math.toRadians(deg); val p = Math.toRadians(pitchDeg)
        val ax = sin(h) * cos(p); val ay = cos(h) * cos(p); val az = sin(p)
        return floatArrayOf(0f, 0f, (-ax).toFloat(), 0f, 0f, (-ay).toFloat(), 0f, 0f, (-az).toFloat())
    }

    @Test fun headingFollowsTheBackCameraAxisAndWraps() {
        assertEquals(0f, OrientationMath.cameraHeadingDegrees(matrix(0.0))!!, .01f)
        assertEquals(90f, OrientationMath.cameraHeadingDegrees(matrix(90.0))!!, .01f) // giro à direita = positivo
        assertEquals(-90f, OrientationMath.cameraHeadingDegrees(matrix(-90.0))!!, .01f)
        assertNull("câmera apontando para o chão: rumo indefinido", OrientationMath.cameraHeadingDegrees(matrix(0.0, -80.0)))
        assertEquals(-170f, OrientationMath.wrap(190f), .01f)
        assertEquals(170f, OrientationMath.wrap(-190f), .01f)
    }

    @Test fun horizontalFovAccountsForPortraitAndCrop() {
        // Sensor 4:3 (6,4 x 4,8 mm), foco 5,4 mm. Paisagem 16:9 usa a largura toda; retrato usa o lado curto recortado.
        val landscape = OrientationMath.horizontalFovDegrees(5.4f, 6.4f, 4.8f, 1280, 720)!!
        val portrait = OrientationMath.horizontalFovDegrees(5.4f, 6.4f, 4.8f, 720, 1280)!!
        assertEquals(61.3f, landscape, .5f)
        assertEquals(36.9f, portrait, .5f)
        val t = OrientationCoverageTracker(); t.setHorizontalFov(portrait)
        val wide = OrientationCoverageTracker(); wide.setHorizontalFov(landscape)
        // O quadro precisa alcançar ~75° do eixo frontal: FOV estreito exige girar mais.
        assertTrue("retrato: ${t.lateralMinDeg}", t.lateralMinDeg > 60f)
        assertTrue("paisagem: ${wide.lateralMinDeg}", wide.lateralMinDeg < 50f)
    }

    private fun tracker(): OrientationCoverageTracker = OrientationCoverageTracker(horizontalFovDeg = 70f).also { it.available = true }
    /** Mantém [deg] por [ms] a 50 Hz a partir de [from]; retorna o instante final. */
    private fun hold(t: OrientationCoverageTracker, from: Long, deg: Float, ms: Long = 800, jitter: Float = 0f): Long {
        var now = from
        while (now < from + ms) { now += 20; t.onSample(now, deg + if ((now / 20) % 2 == 0L) jitter else -jitter) }
        return now
    }
    private fun turn(t: OrientationCoverageTracker, from: Long, a: Float, b: Float, ms: Long = 600): Long {
        var now = from; val steps = ms / 20
        for (i in 1..steps) { now += 20; t.onSample(now, a + (b - a) * i / steps) }
        return now
    }
    private fun calibrated(base: Float = 37f): Pair<OrientationCoverageTracker, Long> {
        val t = tracker(); t.requestCalibration()
        val now = hold(t, 0, base)
        assertTrue(t.calibrated)
        return t to now
    }

    @Test fun correctTurnRightIsCoveredOnlyAfterStabilizing() {
        val (t, start) = calibrated(37f)
        var now = turn(t, start, 37f, 37f + 85f)
        assertEquals(Status.UNSTABLE, t.evaluate(Direction.RIGHT, now).status)
        now = hold(t, now, 37f + 85f)
        val c = t.evaluate(Direction.RIGHT, now)
        assertEquals(Status.COVERED, c.status); assertEquals(85f, c.relativeYawDeg!!, .5f)
        assertFalse(t.evaluate(Direction.LEFT, now).covered)
    }

    @Test fun insufficientTurnAndWrongDirectionAreNotCoverage() {
        val (t, start) = calibrated()
        var now = hold(t, turn(t, start, 37f, 37f + 30f), 37f + 30f)
        assertEquals(Status.INSUFFICIENT_ROTATION, t.evaluate(Direction.RIGHT, now).status)
        now = hold(t, turn(t, now, 67f, 37f - 80f), 37f - 80f)
        assertEquals(Status.WRONG_DIRECTION, t.evaluate(Direction.RIGHT, now).status)
        assertEquals(Status.COVERED, t.evaluate(Direction.LEFT, now).status)
    }

    @Test fun returnToFrontIsCoveredAndAcrossTheWrapBoundary() {
        val (t, start) = calibrated(170f) // referência perto de ±180°
        var now = hold(t, turn(t, start, 170f, 170f + 90f), 170f + 90f)
        assertEquals(Status.COVERED, t.evaluate(Direction.RIGHT, now).status)
        now = hold(t, turn(t, now, 260f, 175f), 175f)
        assertEquals(Status.COVERED, t.evaluate(Direction.FRONT, now).status)
    }

    @Test fun shakeWithoutRotationIsNeitherCoverageNorStable() {
        val (t, start) = calibrated()
        // Estabilidade = variação total na janela: ±6° (12°) é tremor; ±1,5° (3°) é mão firme normal.
        var now = hold(t, start, 37f, jitter = 6f)
        assertEquals(Status.UNSTABLE, t.evaluate(Direction.FRONT, now).status)
        now = hold(t, now, 37f, jitter = 1.5f)
        assertEquals(Status.COVERED, t.evaluate(Direction.FRONT, now).status)
        assertFalse("3° de variação: estável, mas não 'parada'", t.isStillAt(now))
        now = hold(t, now, 37f, jitter = 1f)
        assertTrue("2° de variação: câmera parada (substitui o fluxo óptico)", t.isStillAt(now))
    }

    @Test fun missingSensorCalibrationOrRecentSampleIsInconclusive() {
        val none = OrientationCoverageTracker()
        assertEquals(Status.UNAVAILABLE, none.evaluate(Direction.RIGHT, 1000).status)
        val t = tracker()
        hold(t, 0, 10f)
        assertEquals("sem pedido de calibração (ex.: USB)", Status.NOT_CALIBRATED, t.evaluate(Direction.FRONT, 800).status)
        val (c, now) = calibrated()
        assertEquals("frame sem amostra de sensor próxima (cobertura expirada)", Status.NO_RECENT_SAMPLE,
            c.evaluate(Direction.FRONT, now + 2000).status)
    }

    @Test fun recalibrationAfterResetRequiresANewStableReference() {
        val (t, now) = calibrated()
        t.reset()
        assertEquals(Status.NOT_CALIBRATED, t.evaluate(Direction.FRONT, now).status)
        t.requestCalibration()
        hold(t, now, 200f)
        assertEquals(200f, t.referenceHeading!!, .01f)
    }

    // ---- Integração com a sequência guiada (cobertura por frame) ----
    private fun scene(ms: Long) = SceneEvidence(CrosswalkStatus.UNCERTAIN, null, SignalApplicability.NOT_OBSERVED, ms)
    private fun risk(covered: Boolean) = CrossingRiskAssessment(emptySet(), emptyList(), SceneConfidence(true, true, covered))

    @Test fun sensorCoverageAdvancesTheWholeSequenceWithoutReadyOrButton() {
        val o = tracker(); o.requestCalibration()
        val stages = mutableListOf<CrossingVerification.Stage>()
        val c = CrossingVerification(onChange = { stages += it })
        var now = hold(o, 0, 0f)
        c.start(now)
        // Frames a ~10 Hz, cada um avaliado com a amostra de sensor do mesmo instante.
        fun frames(ms: Long) { val end = now + ms; while (now < end) {
            now += 100; val yaw = o.relativeYawAt(now)
            o.onSample(now, (o.referenceHeading ?: 0f) + (yaw ?: 0f))
            val dir = c.coverageDirection
            val covered = dir != null && o.evaluate(Direction.valueOf(dir.name), now).covered
            c.observe(risk(covered), scene(now), null, now)
        } }
        frames(200)
        assertEquals(CrossingVerification.Stage.AWAIT_RIGHT, c.stage)
        now = hold(o, turn(o, now, 0f, 88f), 88f, 700); frames(2500)
        assertEquals(CrossingVerification.Stage.AWAIT_LEFT, c.stage)
        now = hold(o, turn(o, now, 88f, -88f, 1000), -88f, 700); frames(2500)
        assertEquals(CrossingVerification.Stage.AWAIT_FRONT, c.stage)
        now = hold(o, turn(o, now, -88f, 0f), 0f, 700); frames(2500)
        assertEquals(CrossingVerification.Stage.INCONCLUSIVE, c.stage) // sem faixa/sinal verificáveis
        for (d in CrossingVerification.Direction.values())
            assertEquals("$d comprovada por sensor", CrossingVerification.DirectionStatus.COVERAGE_CONFIRMED, c.status(d))
        assertFalse(c.declaredByUser(CrossingVerification.Direction.RIGHT))
        assertFalse(stages.contains(CrossingVerification.Stage.DANGER))
    }

    @Test fun readyWithoutSensorCoverageStaysUnverified() {
        val c = CrossingVerification()
        c.start(0); c.observe(risk(false), scene(100), null, 100)
        c.confirmCameraDirection(200)
        for (t in 300L..2500L step 100) c.observe(risk(false), scene(t), null, t)
        assertEquals(CrossingVerification.Stage.AWAIT_LEFT, c.stage)
        assertEquals(CrossingVerification.DirectionStatus.COVERAGE_INCONCLUSIVE, c.status(CrossingVerification.Direction.RIGHT))
    }

    @Test fun wrongDirectionProducesOneHintNotALoop() {
        var hints = 0
        val c = CrossingVerification(onWrongDirection = { hints++ })
        c.start(0); c.observe(risk(false), scene(100), null, 100)
        // Dentro do tempo de giro (4 s): uma dica, sem repetir; a etapa não é pulada por causa do giro errado.
        for (t in 200L..3800L step 100) c.observe(risk(false), scene(t), null, t, wrongDirection = true)
        assertEquals(1, hints)
        assertEquals(CrossingVerification.Stage.AWAIT_RIGHT, c.stage)
    }

    @Test fun lostCoverageDuringObservationRetriesInsteadOfConfirming() {
        val c = CrossingVerification()
        c.start(0); c.observe(risk(false), scene(100), null, 100)
        c.observe(risk(true), scene(200), null, 200); c.observe(risk(true), scene(300), null, 300)
        assertEquals(CrossingVerification.Stage.VERIFY_RIGHT, c.stage)
        // Alterna coberto/não coberto: nunca completa 1,5 s contínuos com cobertura.
        for (t in 400L..2000L step 100) c.observe(risk(t % 400 != 0L), scene(t), null, t)
        assertEquals(CrossingVerification.Stage.VERIFY_RIGHT, c.stage)
        for (t in 2100L..8000L step 100) c.observe(risk(false), scene(t), null, t)
        assertEquals("tempo limite: segue como não verificada", CrossingVerification.Stage.AWAIT_LEFT, c.stage)
        assertEquals(CrossingVerification.DirectionStatus.COVERAGE_INCONCLUSIVE, c.status(CrossingVerification.Direction.RIGHT))
    }
    @Test fun calibrationNeverBlocksWhenTheUserKeepsMoving() {
        val t = tracker(); t.requestCalibration()
        var now = 0L; var deg = 0f
        repeat(120) { now += 20; deg += if (it % 2 == 0) 3f else -2f; t.onSample(now, deg) } // ~2,4 s instável
        assertTrue("após 1,5 s usa a mediana recente como frente", t.calibrated)
    }
    @Test fun comingBackFromTheRightTowardTheLeftIsProgressNotWrongDirection() {
        // Caso do teste USB: terminou a direita em +73° e começou a voltar para a esquerda.
        val (t, start) = calibrated(0f)
        var now = hold(t, turn(t, start, 0f, 73f), 73f)
        assertEquals("parado na direita ao pedir esquerda: lado errado", Status.WRONG_DIRECTION, t.evaluate(Direction.LEFT, now).status)
        now = turn(t, now, 73f, 40f, 800) // girando rumo à esquerda, ainda do lado direito
        assertNotEquals("indo rumo à esquerda não é lado errado", Status.WRONG_DIRECTION, t.evaluate(Direction.LEFT, now).status)
    }

    @Test fun wrongDirectionHintWaitsThreeSecondsAfterTheInstruction() {
        var hints = 0
        val c = CrossingVerification(onWrongDirection = { hints++ })
        c.start(0); c.observe(risk(false), scene(100), null, 100)
        c.instructionHeard(100, CrossingVerification.Stage.AWAIT_RIGHT)
        for (ms in 200L..2900L step 100) c.observe(risk(false), scene(ms), null, ms, wrongDirection = true, coverageAvailable = true)
        assertEquals("antes de 3 s: sem reclamar", 0, hints)
        for (ms in 3000L..3600L step 100) c.observe(risk(false), scene(ms), null, ms, wrongDirection = true, coverageAvailable = true)
        assertEquals(1, hints)
    }
}
