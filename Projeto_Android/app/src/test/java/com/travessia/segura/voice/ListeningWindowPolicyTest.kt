package com.travessia.segura.voice

import org.junit.Assert.*
import org.junit.Test

class ListeningWindowPolicyTest {
    @Test fun neverOpensWhileTtsSpeaksOrAlreadyListening() {
        val p = ListeningWindowPolicy()
        assertFalse(p.shouldOpen(wanted = true, ttsBusy = true, listening = false, now = 1000))
        assertFalse(p.shouldOpen(wanted = true, ttsBusy = false, listening = true, now = 1000))
        assertFalse(p.shouldOpen(wanted = false, ttsBusy = false, listening = false, now = 1000))
        assertTrue(p.shouldOpen(wanted = true, ttsBusy = false, listening = false, now = 1000))
    }
    @Test fun waitsBrieflyAfterSpeechToAvoidHearingItself() {
        val p = ListeningWindowPolicy(afterSpeechDelayMs = 450)
        p.onSpeechFinished(1000)
        assertFalse(p.shouldOpen(true, false, false, 1200))
        assertTrue(p.shouldOpen(true, false, false, 1450))
    }
    @Test fun silenceKeepsReopeningWindowsBetweenSteps() {
        val p = ListeningWindowPolicy(reopenDelayMs = 600)
        var t = 0L
        repeat(200) { p.onError(quiet = true, now = t); t += 600; assertTrue(p.shouldOpen(true, false, false, t)) }
        assertFalse(p.disabled)
    }
    @Test fun repeatedRealFailuresAnnounceOnceAndRetryOnlySlowly() {
        val p = ListeningWindowPolicy(baseBackoffMs = 1000, maxHardErrors = 5, degradedRetryMs = 15000)
        var announcements = 0
        var t = 0L
        repeat(10) { if (p.onError(quiet = false, now = t)) announcements++; t += 100 }
        assertTrue(p.disabled)
        assertEquals("limitação comunicada uma única vez", 1, announcements)
        assertFalse("sem ciclo rápido de reinicialização", p.shouldOpen(true, false, false, t + 5000))
        assertTrue("nova tentativa permitida mais tarde", p.shouldOpen(true, false, false, t + 15000))
        p.onResult(t + 16000)
        assertFalse(p.disabled)
    }
    @Test fun backoffGrowsAfterConsecutiveHardErrors() {
        val p = ListeningWindowPolicy(baseBackoffMs = 1000, maxHardErrors = 9)
        p.onError(false, 0); assertFalse(p.shouldOpen(true, false, false, 999)); assertTrue(p.shouldOpen(true, false, false, 1000))
        p.onError(false, 1000); assertFalse(p.shouldOpen(true, false, false, 2999)); assertTrue(p.shouldOpen(true, false, false, 3000))
        p.onResult(3000); assertEquals(0, p.hardErrors)
    }
}
