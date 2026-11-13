package com.travessia.segura.road

import com.travessia.segura.detection.RawDetection
import org.junit.Assert.*
import org.junit.Test

/** Semáforos sintéticos (640 px). A aplicabilidade real à travessia nunca é dada só pela imagem. */
class SignalApplicabilityTest {
    private val stripe = RawDetection(80f, 150f, 550f, 310f, 8, "crosswalk", .8f)
    private fun ped(color: String, x: Float = 290f, h: Float = 60f) =
        RawDetection(x, 20f, x + 30f, 20f + h, if (color == "green") 9 else 10, "pedestrian-light-$color", .9f)
    private fun car(color: String) = RawDetection(200f, 30f, 220f, 70f, 5, "traffic-light-$color", .9f)
    private fun tracker() = SceneEvidenceTracker().also { it.update(listOf(stripe), 1000, 640) }

    @Test fun vehicleGreenIsNeverAPedestrianSignal() {
        val t = tracker()
        var e = t.update(listOf(stripe, car("green")), 1400, 640)
        for (ms in 1500L..3000L step 100) e = t.update(listOf(stripe, car("green")), ms, 640)
        assertEquals(SignalApplicability.VEHICLE_ONLY, e.signal)
        assertFalse(e.signalColorStable)
    }

    @Test fun pedestrianGreenBecomesStableOnlyAfterConsistentObservations() {
        val t = tracker()
        val first = t.update(listOf(stripe, ped("green")), 1400, 640)
        assertEquals(SignalApplicability.PEDESTRIAN_GREEN, first.signal)
        assertFalse("uma observação não é cor estável", first.signalColorStable)
        var e = first
        for (ms in 1500L..2200L step 100) e = t.update(listOf(stripe, ped("green")), ms, 640)
        assertTrue(e.signalColorStable)
        assertFalse("associação geométrica não é prova independente", e.signalAssociationVerified)
        assertTrue(e.signalApplicabilityUnknown)
    }

    @Test fun redIsReportedImmediatelyAndColorChangeResetsStability() {
        val t = tracker()
        var e = t.update(listOf(stripe, ped("green")), 1400, 640)
        for (ms in 1500L..2200L step 100) e = t.update(listOf(stripe, ped("green")), ms, 640)
        assertTrue(e.signalColorStable)
        e = t.update(listOf(stripe, ped("red")), 2300, 640)
        assertEquals("vermelho é imediato (conservador)", SignalApplicability.PEDESTRIAN_RED, e.signal)
        assertFalse(e.signalColorStable)
    }

    @Test fun twoPedestrianSignalsInDifferentDirectionsAreUncertainOrExcluded() {
        val t = tracker()
        // Um alinhado à faixa, outro bem ao lado (outra travessia): o lateral é excluído.
        val e = t.update(listOf(stripe, ped("green"), ped("red", x = 600f)), 1400, 640)
        assertEquals(1, e.excludedPedestrianSignals)
        // Dois alinhados com cores diferentes: incerto.
        val both = t.update(listOf(stripe, ped("green", x = 250f), ped("red", x = 330f)), 1500, 640)
        assertEquals(SignalApplicability.UNCERTAIN, both.signal)
    }

    @Test fun distantTinySignalIsNotACandidate() {
        val t = tracker()
        val e = t.update(listOf(stripe, ped("green", h = 5f)), 1400, 640)
        assertNotEquals(SignalApplicability.PEDESTRIAN_GREEN, e.signal)
        assertEquals(1, e.excludedPedestrianSignals)
    }

    @Test fun shortOcclusionKeepsTheSameSignalTrack() {
        val s = SignalTracker(holdMs = 500)
        val a = s.update(listOf(ped("green")), 1000).single().id
        s.update(emptyList(), 1200)
        val b = s.update(listOf(ped("green", x = 293f)), 1400).single().id
        assertEquals(a, b)
        s.update(emptyList(), 2000)
        assertTrue("perda longa: track expirado", s.active.isEmpty())
    }

    @Test fun lostSignalAndUncertainAssociationBlockFavorableConclusion() {
        val t = tracker()
        for (ms in 1400L..2200L step 100) t.update(listOf(stripe, ped("green")), ms, 640)
        val lost = t.update(listOf(stripe), 2300, 640)
        assertNotEquals(SignalApplicability.PEDESTRIAN_GREEN, lost.signal)
        assertEquals(-1L, lost.greenObservedAtMs)
        // Mesmo verde estável não satisfaz a verificação sem associação independente.
        val c = CrossingVerification()
        val clear = CrossingRiskAssessment(emptySet(), emptyList(), SceneConfidence(true, true, true))
        c.start(0); c.observe(clear, SceneEvidence(CrosswalkStatus.CONFIRMED, stripe, SignalApplicability.PEDESTRIAN_GREEN, 100,
            greenObservedAtMs = 100, signalColorStable = true), null, 100)
        for (start in listOf(1000L, 3000L, 5000L)) {
            c.confirmCameraDirection(start)
            for (ms in start..start + 1500 step 500) c.observe(clear, SceneEvidence(CrosswalkStatus.CONFIRMED, stripe,
                SignalApplicability.PEDESTRIAN_GREEN, ms, greenObservedAtMs = 100, signalColorStable = true), null, ms)
        }
        assertNotEquals(CrossingVerification.Stage.FAVORABLE_OBSERVATION, c.stage)
    }
}
