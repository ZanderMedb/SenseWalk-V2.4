package com.travessia.segura.tracking

import org.junit.Assert.*
import org.junit.Test

class RastreadorAuditTest {
    private fun tracker() = Rastreador { Rastreador.Settings() }

    @Test fun `parked growth with walking translation never proves independent approach`() {
        val tracker = tracker()
        for (i in 0..12) {
            tracker.novoFrameCamera(5f,2f,1f,.35f)
            tracker.atualizar(1,200f + i*5,100f + i*2,1.0+i*.1,70+i*6)
            assertNotEquals("APROXIMANDO",tracker.classificar(1).first)
        }
        assertEquals("MOVIMENTO_INCERTO",tracker.classificar(1).first)
    }
    @Test fun `independent lateral approach after stationary history is detected`() {
        val tracker = tracker()
        for (i in 0..15) {
            tracker.novoFrameCamera(0f,0f,1f,.35f)
            tracker.atualizar(1,90f + (i-6).coerceAtLeast(0)*8,100f,1.0+i*.1,80)
        }
        assertEquals("POTENCIAL_CONFLITO",tracker.classificar(1).first)
    }
    @Test fun `low fps is not stationary by invalid time window`() {
        val tracker = tracker()
        for (i in 0..8) {
            tracker.novoFrameCamera(0f,0f,1f,.35f)
            tracker.atualizar(1,50f+i*20,100f,1.0+i*.6,30)
        }
        assertNotEquals("PARADO",tracker.classificar(1).first)
    }
    @Test fun `failed compensation and abrupt turn yield uncertain then recover`() {
        val tracker = tracker()
        // "Parado" exige >= 1 s de histórico com câmera estimada (antes 0,8 s bastava).
        for (i in 0..10) {
            tracker.novoFrameCamera(0f,0f,1f,.35f)
            tracker.atualizar(1,50f,100f,1.0+i*.1,30)
        }
        assertEquals("PARADO",tracker.classificar(1).first)
        tracker.novoFrameCamera(30f,0f,1f,.35f)
        tracker.atualizar(1,80f,100f,2.1,30)
        assertEquals("MOVIMENTO_INCERTO",tracker.classificar(1).first)
        tracker.novoFrameCamera(0f,0f,0f,.35f)
        assertEquals("MOVIMENTO_INCERTO",tracker.classificar(1).first)
    }
    @Test fun `gap cannot reuse old stationary history`() {
        val tracker = tracker()
        for (i in 0..6) {
            tracker.novoFrameCamera(0f,0f,1f,.35f)
            tracker.atualizar(1,50f,100f,1.0+i*.1,30)
        }
        tracker.atualizar(1,50f,100f,5.0,30)
        assertEquals("MOVIMENTO_INCERTO",tracker.classificar(1).first)
    }
}
