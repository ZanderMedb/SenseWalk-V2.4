package com.travessia.segura.vision

import org.junit.Assert.*
import org.junit.Test

/** Perfis sintéticos de uma "cena" mais larga que o quadro; girar = deslizar a janela de 160 colunas. */
class VisualYawEstimatorTest {
    private val rnd = java.util.Random(3)
    /** Panorama de 1200 colunas com textura (prédios, postes, sombras). */
    private val panorama = FloatArray(1200).also { p ->
        var v = 120f
        for (i in p.indices) { if (i % 37 == 0) v = 60f + rnd.nextFloat() * 140f; p[i] = v + rnd.nextFloat() * 6f }
    }
    private fun view(start: Int) = FloatArray(160) { panorama[(start + it).coerceIn(0, panorama.lastIndex)] }

    @Test fun turningRightIsPositiveAndLeftIsNegative() {
        val e = VisualYawEstimator(horizontalFovDeg = 70f)
        e.updateProfile(view(500))
        val right = e.updateProfile(view(510)) // janela andou para a direita: a cena desliza para a esquerda
        assertTrue(right.accepted)
        assertEquals(10, right.shiftColumns)
        assertEquals(10 * 70f / 160f, e.yawDeg, .01f)
        e.updateProfile(view(490))
        assertEquals(-10 * 70f / 160f, e.yawDeg, .01f)
    }

    @Test fun ninetyDegreeTurnAccumulatesAndStillCameraDoesNotDrift() {
        val e = VisualYawEstimator(horizontalFovDeg = 70f)
        var start = 300
        e.updateProfile(view(start))
        // 90° = 205,7 colunas; giro em passos de 12 colunas por quadro (~5°/quadro).
        repeat(17) { start += 12; e.updateProfile(view(start)) }
        assertEquals(17 * 12 * 70f / 160f, e.yawDeg, 1f)
        val before = e.yawDeg
        repeat(30) { e.updateProfile(view(start)) } // parado
        assertEquals("sem deriva com a câmera parada", before, e.yawDeg, .01f)
    }

    @Test fun featurelessSceneIsUnknownNotInvented() {
        val e = VisualYawEstimator()
        e.updateProfile(FloatArray(160) { 100f })
        val s = e.updateProfile(FloatArray(160) { 100f })
        assertFalse(s.accepted)
        assertEquals(0f, e.yawDeg, 0f)
    }

    @Test fun largeFastTurnWithinRangeIsTracked() {
        val e = VisualYawEstimator(horizontalFovDeg = 70f)
        e.updateProfile(view(400))
        val s = e.updateProfile(view(445)) // 45 colunas ≈ 19,7° num quadro
        assertTrue(s.accepted)
        assertEquals(45, s.shiftColumns)
    }

    @Test fun resetZeroesTheAccumulatedYaw() {
        val e = VisualYawEstimator()
        e.updateProfile(view(100)); e.updateProfile(view(120))
        assertNotEquals(0f, e.yawDeg)
        e.reset()
        assertEquals(0f, e.yawDeg, 0f)
        assertFalse(e.updateProfile(view(120)).accepted) // sem quadro anterior após reset
    }
    @Test fun returningToTheFrontViewReanchorsAndRemovesDrift() {
        val e = VisualYawEstimator(horizontalFovDeg = 70f)
        e.updateProfile(view(500)) // frente (referência)
        var start = 500
        repeat(10) { start += 15; e.updateProfile(view(start)) } // gira ~65° para a direita
        // Volta rápida demais: um salto maior que o alcance por quadro não é rastreado (deriva).
        e.updateProfile(view(560))
        val drifted = e.yawDeg
        e.updateProfile(view(505)); e.updateProfile(view(502))
        assertTrue("deriva antes de reancorar: $drifted", kotlin.math.abs(drifted) > 10f)
        assertTrue(e.reanchored)
        assertEquals("vista da frente reconhecida: ~1°", 2 * 70f / 160f, e.yawDeg, .5f)
    }
    @Test fun widerSearchRangeTracksFastUsbTurns() {
        // Webcam a poucos quadros/s: 70 colunas (~44% da largura) num quadro.
        val narrow = VisualYawEstimator(horizontalFovDeg = 90f)
        narrow.updateProfile(view(300)); val n = narrow.updateProfile(view(370))
        val wide = VisualYawEstimator(horizontalFovDeg = 90f, maxShiftFraction = .5f)
        wide.updateProfile(view(300)); val w = wide.updateProfile(view(370))
        assertNotEquals("alcance de 35% não alcança", 70, n.shiftColumns)
        assertTrue(w.accepted)
        assertEquals(70, w.shiftColumns)
        assertEquals(70 * 90f / 160f, wide.yawDeg, .01f)
    }
}
