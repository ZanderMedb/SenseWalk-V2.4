package com.travessia.segura.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechStateGateTest {
    @Test fun `unchanged conditions remain silent even after days`() {
        for ((topic,state) in listOf("light" to "red", "traffic" to "none", "alignment" to "left", "road" to "inconclusive")) {
            val gate = SpeechStateGate()
            assertTrue(gate.observe(topic,state,0))
            assertTrue(gate.needsSpeech(topic,state))
            gate.markSpoken(topic,state)
            for (time in listOf(1L,8_000L,60_000L,86_400_000L,604_800_000L)) {
                assertTrue(gate.observe(topic,state,time))
                assertFalse("$topic repeated at $time",gate.needsSpeech(topic,state))
            }
        }
    }

    @Test fun `vehicle appearance and confirmed disappearance each rearm once`() {
        val gate = SpeechStateGate()
        gate.observe("traffic","none",0)
        gate.markSpoken("traffic","none")
        gate.observe("traffic","moving",10)
        assertTrue(gate.needsSpeech("traffic","moving"))
        assertFalse(gate.needsSpeech("traffic","none"))
        gate.markSpoken("traffic","moving")
        assertFalse(gate.observe("traffic","none",20,3000))
        assertTrue(gate.observe("traffic","none",3020,3000))
        assertTrue(gate.needsSpeech("traffic","none"))
        gate.markSpoken("traffic","none")
        assertFalse(gate.needsSpeech("traffic","none"))
    }

    @Test fun `brief loss of approaching vehicle does not repeat warning`() {
        val gate = SpeechStateGate()
        gate.observe("approach","approaching",0)
        gate.markSpoken("approach","approaching")
        assertFalse(gate.observe("approach","absent",100,1500))
        gate.observe("approach","approaching",500)
        assertFalse(gate.needsSpeech("approach","approaching"))
        assertFalse(gate.observe("approach","absent",600,1500))
        assertTrue(gate.observe("approach","absent",2100,1500))
        gate.observe("approach","approaching",2200)
        assertTrue(gate.needsSpeech("approach","approaching"))
    }

    @Test fun `alignment must persist and oscillations restart settling`() {
        val gate = SpeechStateGate()
        assertFalse(gate.observe("alignment","left",0,1200))
        assertFalse(gate.observe("alignment","right",1000,1200))
        assertFalse(gate.observe("alignment","left",1500,1200))
        assertFalse(gate.observe("alignment","left",2699,1200))
        assertTrue(gate.observe("alignment","left",2700,1200))
        assertTrue(gate.needsSpeech("alignment","left"))
    }

    @Test fun `silent observations rearm speech without announcing absence`() {
        val gate = SpeechStateGate()
        gate.observe("road","error",0)
        gate.markSpoken("road","error")
        gate.observe("road","healthy",100,1500)
        gate.observe("road","healthy",1600,1500)
        gate.observe("road","error",1700)
        assertTrue(gate.needsSpeech("road","error"))
    }

    @Test fun `topics do not rearm each other`() {
        val gate = SpeechStateGate()
        gate.observe("traffic","none",0)
        gate.markSpoken("traffic","none")
        gate.observe("light","red",100)
        gate.observe("light","green",200)
        assertFalse(gate.needsSpeech("traffic","none"))
    }

    @Test fun `only successful submission marks speech and stale completion is ignored`() {
        val gate = SpeechStateGate()
        gate.observe("traffic","none",0)
        assertTrue(gate.needsSpeech("traffic","none"))
        // Uma mensagem expirada, recusada ou cancelada ainda pode ser tentada.
        gate.observe("traffic","none",10_000)
        assertTrue(gate.needsSpeech("traffic","none"))
        gate.observe("traffic","moving",10_001)
        gate.markSpoken("traffic","none")
        assertTrue(gate.needsSpeech("traffic","moving"))
        gate.markSpoken("traffic","moving")
        assertFalse(gate.needsSpeech("traffic","moving"))
    }

    @Test fun `reset rearms only matching topics`() {
        val gate = SpeechStateGate()
        for (topic in listOf("scene_traffic","scene_light","road_status")) {
            gate.observe(topic,"same",0)
            gate.markSpoken(topic,"same")
        }
        gate.reset("scene_")
        assertNull(gate.current("scene_traffic"))
        assertNull(gate.current("scene_light"))
        assertEquals("same",gate.current("road_status"))
        assertFalse(gate.needsSpeech("road_status","same"))
        gate.observe("scene_traffic","same",100)
        assertTrue(gate.needsSpeech("scene_traffic","same"))
    }
}
