package com.travessia.segura.voice

import com.travessia.segura.road.AutoStartPolicy
import org.junit.Assert.*
import org.junit.Test

class SpeechPolicyTest {
    // 8. Alerta de perigo interrompe fala comum; fala comum não interrompe outra.
    @Test fun dangerInterruptsOrdinarySpeechButNormalMessagesDoNot() {
        assertTrue(SpeechInterruptPolicy.interrupts(VoicePriority.INFO, VoicePriority.SAFETY))
        assertTrue(SpeechInterruptPolicy.interrupts(VoicePriority.CROSSING, VoicePriority.SAFETY))
        assertTrue("parar/cancelar", SpeechInterruptPolicy.interrupts(VoicePriority.CROSSING, VoicePriority.CONTROL))
        assertFalse(SpeechInterruptPolicy.interrupts(VoicePriority.INFO, VoicePriority.CROSSING))
        assertFalse(SpeechInterruptPolicy.interrupts(VoicePriority.CROSSING, VoicePriority.CROSSING))
        assertFalse(SpeechInterruptPolicy.interrupts(VoicePriority.SAFETY, VoicePriority.SAFETY))
        assertFalse("nada falando", SpeechInterruptPolicy.interrupts(null, VoicePriority.SAFETY))
    }

    @Test fun instructionsFinishTheSentenceButStaleConclusionsAreCut() {
        for (s in listOf("AWAIT_RIGHT", "AWAIT_LEFT", "AWAIT_FRONT", "SIGNAL_RED", "CROSSWALK_WAIT", "DANGER"))
            assertFalse(s, SpeechInterruptPolicy.cutOnStateChange(s))
        for (s in listOf("FAVORABLE_OBSERVATION", "SIGNAL_GREEN", "INCONCLUSIVE"))
            assertTrue(s, SpeechInterruptPolicy.cutOnStateChange(s))
    }

    // 9. TTS não repete continuamente: o mesmo estado observado a cada quadro pede fala uma única vez.
    @Test fun sameStateEveryFrameIsSpokenOnce() {
        val gate = SpeechStateGate()
        var needs = 0
        for (t in 0L..10_000L step 100) {
            gate.observe("crossing_stage", "SIGNAL_RED", t)
            if (gate.needsSpeech("crossing_stage", "SIGNAL_RED")) { needs++; gate.markSpoken("crossing_stage", "SIGNAL_RED") }
        }
        assertEquals(1, needs)
    }

    @Test fun oneDangerAlertPerVehicle() {
        val gate = DangerAlertGate(repeatAfterMs = 15_000)
        assertTrue(gate.shouldAlert(setOf(4), 0))
        assertFalse("mesmo carro oscilando", gate.shouldAlert(setOf(4), 3000))
        assertTrue("carro novo alerta na hora", gate.shouldAlert(setOf(4, 9), 3500))
        assertFalse(gate.shouldAlert(setOf(9), 6000))
        assertTrue("perigo persistente é lembrado depois de um tempo", gate.shouldAlert(setOf(9), 21_000))
        gate.reset()
        assertTrue(gate.shouldAlert(setOf(4), 22_000))
    }

    // 12. FFNet não reinicia a análise depois de "Terminei"/"Parar".
    @Test fun ffnetDoesNotRestartAfterUserEndedTheCrossing() {
        assertTrue(AutoStartPolicy.shouldAutoStart(true, userStopped = false, analysisActive = false, monitoring = true,
            tokenCurrent = true, ready = true))
        assertFalse(AutoStartPolicy.shouldAutoStart(true, userStopped = true, analysisActive = false, monitoring = true,
            tokenCurrent = true, ready = true))
    }
}
