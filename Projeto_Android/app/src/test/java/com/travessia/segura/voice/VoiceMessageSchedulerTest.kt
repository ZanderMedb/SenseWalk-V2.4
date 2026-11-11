package com.travessia.segura.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceMessageSchedulerTest {
    @Test fun `safety alerts are emitted before informational messages`() {
        val scheduler = VoiceMessageScheduler()
        scheduler.offer(ScheduledVoiceMessage("status", "Análise em andamento", VoicePriority.INFO, 2))
        scheduler.offer(ScheduledVoiceMessage("red", "Aguarde", VoicePriority.SAFETY, 3))
        assertEquals("red", scheduler.poll()?.key)
    }

    @Test fun `lower priority message is rejected when queue is full`() {
        val scheduler = VoiceMessageScheduler(maxSize = 1)
        scheduler.offer(ScheduledVoiceMessage("crossing", "Travessia", VoicePriority.CROSSING, 1))
        scheduler.offer(ScheduledVoiceMessage("info", "Info", VoicePriority.INFO, 2))
        assertEquals("crossing", scheduler.poll()?.key)
    }

    @Test fun `new observation replaces obsolete queued state`() {
        val scheduler = VoiceMessageScheduler()
        scheduler.offer(ScheduledVoiceMessage("state:traffic","Nenhum veículo",VoicePriority.CROSSING,1,"traffic","none"))
        assertTrue(scheduler.contains("state:traffic"))
        scheduler.remove("state:traffic")
        scheduler.offer(ScheduledVoiceMessage("state:traffic","Veículo detectado",VoicePriority.SAFETY,2,"traffic","moving"))
        assertEquals(1,scheduler.size())
        val message = scheduler.poll()
        assertEquals("moving",message?.stateValue)
        assertEquals("traffic",message?.stateTopic)
        assertNull(scheduler.poll())
    }

    @Test fun `reset removes only scene messages and preserves manual replies`() {
        val scheduler = VoiceMessageScheduler()
        scheduler.offer(ScheduledVoiceMessage("state:scene_traffic","Nenhum veículo",VoicePriority.CROSSING,1))
        scheduler.offer(ScheduledVoiceMessage("state:road_status","Imagem insuficiente",VoicePriority.INFO,2))
        scheduler.offer(ScheduledVoiceMessage("voice_status","Status solicitado",VoicePriority.SAFETY,3))
        scheduler.removePrefix("state:scene_")
        assertFalse(scheduler.contains("state:scene_traffic"))
        assertTrue(scheduler.contains("state:road_status"))
        assertEquals("voice_status",scheduler.poll()?.key)
        assertEquals("state:road_status",scheduler.poll()?.key)
    }

    @Test fun `full queue does not mark unsubmitted state as spoken`() {
        val scheduler = VoiceMessageScheduler(1)
        val gate = SpeechStateGate()
        scheduler.offer(ScheduledVoiceMessage("hazard","Aguarde",VoicePriority.SAFETY,0))
        gate.observe("traffic","none",1)
        val message = ScheduledVoiceMessage("state:traffic","Nenhum veículo",VoicePriority.CROSSING,1,"traffic","none")
        assertFalse(scheduler.offer(message))
        assertTrue(gate.needsSpeech("traffic","none"))
        scheduler.poll()
        assertTrue(scheduler.offer(message))
        assertEquals(message,scheduler.poll())
    }
}
