package com.travessia.segura.detection

import org.junit.Assert.*
import org.junit.Test

/** Cenários sintéticos de associação (640x360, ~10 Hz). Não mede MOT real: não há ground truth anotado. */
class TrackerRobustnessTest {
    private val w = 640
    private val h = 360
    private fun car(cx: Float, cy: Float, bw: Float = 80f, bh: Float = 50f, conf: Float = .8f, cls: Int = 0, name: String = "car") =
        RawDetection(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, cls, name, conf)
    private fun visibleIds(out: List<TrackedDetection>) = out.filterNot { it.isGhost }.map { it.trackId }

    @Test fun fastCarKeepsItsIdAcrossLargeDisplacements() {
        val t = AdvancedVehicleTracker()
        val ids = mutableSetOf<Int>()
        // 150 px a cada 100 ms (~23% da largura por frame).
        for (i in 0 until 4) ids += visibleIds(t.update(listOf(car(40f + 150f * i, 200f)), w, h, timestampMs = 1000L + 100 * i))
        assertEquals("um único ID para o carro rápido: $ids", 1, ids.size)
    }

    @Test fun missingDetectionsForOneToThreeFramesAreRecoveredWithTheSameId() {
        for (gap in 1..3) {
            val t = AdvancedVehicleTracker()
            var time = 1000L; var x = 60f
            fun step(observe: Boolean): List<TrackedDetection> {
                val out = t.update(if (observe) listOf(car(x, 200f)) else emptyList(), w, h, timestampMs = time)
                time += 100; x += 40f; return out
            }
            repeat(4) { step(true) }
            repeat(gap) { val out = step(false); assertTrue("perda = previsão marcada como ghost", out.all { it.isGhost }) }
            val back = step(true)
            assertEquals("gap=$gap", listOf(0), visibleIds(back))
            assertEquals(1, t.lastStats.recoveredAfterMiss)
        }
    }

    @Test fun twoCarsCrossingKeepTheirOwnIds() {
        val t = AdvancedVehicleTracker()
        var leftToRight = -1; var rightToLeft = -1
        for (i in 0..10) {
            val a = car(60f + 50f * i, 180f)   // esquerda -> direita
            val b = car(580f - 50f * i, 195f)  // direita -> esquerda, quase na mesma linha
            val out = t.update(listOf(a, b), w, h, timestampMs = 1000L + 100 * i).filterNot { it.isGhost }
            val ab = out.minByOrNull { kotlin.math.abs(it.cx - a.cx) + kotlin.math.abs(it.cy - a.cy) }!!
            val bb = out.minByOrNull { kotlin.math.abs(it.cx - b.cx) + kotlin.math.abs(it.cy - b.cy) }!!
            if (i == 0) { leftToRight = ab.trackId; rightToLeft = bb.trackId }
            assertEquals("frame $i: carro A manteve o ID", leftToRight, ab.trackId)
            assertEquals("frame $i: carro B manteve o ID", rightToLeft, bb.trackId)
        }
    }

    @Test fun closeCarsAreNotMergedIntoOneId() {
        val t = AdvancedVehicleTracker()
        var out = emptyList<TrackedDetection>()
        for (i in 0..5) out = t.update(listOf(car(300f + 5f * i, 200f), car(390f + 5f * i, 200f)), w, h, timestampMs = 1000L + 100 * i)
        assertEquals(2, visibleIds(out).toSet().size)
        assertEquals(2, t.trackCount)
    }

    @Test fun partialOcclusionAndTemporarySizeChangeKeepTheId() {
        val t = AdvancedVehicleTracker()
        val ids = mutableSetOf<Int>()
        for (i in 0..8) {
            val width = if (i in 3..5) 45f else 90f // metade do carro oculta / caixa encolhe
            ids += visibleIds(t.update(listOf(car(200f + 20f * i, 200f, bw = width)), w, h, timestampMs = 1000L + 100 * i))
        }
        assertEquals(setOf(0), ids)
    }

    @Test fun confidenceOscillationDoesNotFragmentTheTrack() {
        val t = AdvancedVehicleTracker()
        val ids = mutableSetOf<Int>()
        for (i in 0..10) ids += visibleIds(t.update(listOf(car(150f + 15f * i, 200f, conf = if (i % 2 == 0) .7f else .22f)),
            w, h, timestampMs = 1000L + 100 * i))
        assertEquals(setOf(0), ids)
    }

    @Test fun duplicateBoxesOnTheSameVehicleDoNotCreateNewIds() {
        val t = AdvancedVehicleTracker()
        t.update(listOf(car(300f, 200f)), w, h, timestampMs = 1000)
        val out = t.update(listOf(car(305f, 200f), car(307f, 201f, conf = .5f, cls = 3, name = "truck")), w, h, timestampMs = 1100)
        assertEquals(listOf(0), visibleIds(out))
        assertEquals(1, t.lastStats.duplicatesSuppressed)
    }

    @Test fun distantUnrelatedDetectionIsNotStolenByAnExistingTrack() {
        val t = AdvancedVehicleTracker()
        repeat(3) { t.update(listOf(car(100f, 100f)), w, h, timestampMs = 1000L + 100 * it) }
        val out = t.update(listOf(car(560f, 320f)), w, h, timestampMs = 1300)
        assertTrue(visibleIds(out).single() != 0)
    }

    @Test fun predictedTracksExpireAndStructureStaysBounded() {
        val t = AdvancedVehicleTracker(maxTracks = 10)
        t.update(listOf(car(100f, 100f)), w, h, timestampMs = 1000)
        t.update(emptyList(), w, h, timestampMs = 2600)
        assertEquals("sem observação por >1,5 s: removido", 0, t.trackCount)
        for (i in 0 until 30) t.update((0 until 12).map { car(20f + 50f * it, 30f + 10f * (i % 3), bw = 30f, bh = 20f) },
            w, h, timestampMs = 3000L + 100 * i)
        assertTrue(t.trackCount <= 10)
    }

    @Test fun sessionResetAndCameraSwitchStartCleanIds() {
        val t = AdvancedVehicleTracker()
        repeat(3) { t.update(listOf(car(100f + 10f * it, 100f)), w, h, timestampMs = 1000L + 100 * it) }
        t.reset() // nova sessão / troca de câmera
        assertEquals(0, t.trackCount)
        // Timestamps de outra fonte podem recomeçar menores: aceitos após o reset.
        val out = t.update(listOf(car(400f, 200f)), w, h, timestampMs = 50)
        assertEquals(listOf(0), visibleIds(out))
    }

    @Test fun globalCameraMotionIsCompensatedInPrediction() {
        val t = AdvancedVehicleTracker()
        // Carro parado no mundo; a câmera gira e a imagem inteira desloca 60 px por frame.
        val ids = mutableSetOf<Int>()
        for (i in 0..5) ids += visibleIds(t.update(listOf(car(150f + 60f * i, 200f)), w, h,
            cameraDx = if (i == 0) 0f else 60f, timestampMs = 1000L + 100 * i))
        assertEquals(setOf(0), ids)
    }
}
