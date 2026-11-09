package com.travessia.segura.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceMessageSchedulerTest {
    @Test fun `safety alerts are emitted before navigation messages`() {
        val scheduler = VoiceMessageScheduler()
        scheduler.offer(ScheduledVoiceMessage("gps", "Continue", VoicePriority.NAVIGATION, 2))
        scheduler.offer(ScheduledVoiceMessage("red", "Aguarde", VoicePriority.SAFETY, 3))
        assertEquals("red", scheduler.poll()?.key)
    }

    @Test fun `lower priority message is rejected when queue is full`() {
        val scheduler = VoiceMessageScheduler(maxSize = 1)
        scheduler.offer(ScheduledVoiceMessage("crossing", "Travessia", VoicePriority.CROSSING, 1))
        scheduler.offer(ScheduledVoiceMessage("info", "Info", VoicePriority.INFO, 2))
        assertEquals("crossing", scheduler.poll()?.key)
    }
}
