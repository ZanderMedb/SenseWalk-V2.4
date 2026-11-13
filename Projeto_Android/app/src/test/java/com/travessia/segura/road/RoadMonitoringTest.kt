package com.travessia.segura.road

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class RoadMonitoringTest {
    private fun sample(time: Long, road: Boolean = true, boundary: Boolean = true) = RoadSegmentationResult(
        .4f, .2f, road, true, boundary, timestamp = time, boundaryRatio = .01f)
    @Test fun `persistent road evidence starts exactly one analysis but single frame does not`() {
        val machine = AutomaticDetectionManager(); machine.start()
        assertEquals(MonitorPhase.WAITING_FOR_ROAD, machine.phase)
        assertEquals(AnalysisState.POSSIBLE_CROSSING, machine.update(sample(1000),1000))
        assertEquals(AnalysisState.POSSIBLE_CROSSING, machine.update(sample(2000),2000))
        assertEquals(AnalysisState.ACTIVE_ANALYSIS, machine.update(sample(2600),2600))
        assertEquals(MonitorPhase.ANALYZING, machine.phase)
        // Rua continua visível: não há segunda ativação nem retorno a WAITING por FFNet.
        for (t in 3000L..20000L step 500) assertEquals(AnalysisState.ACTIVE_ANALYSIS, machine.update(sample(t),t))
    }
    @Test fun `stale repeated or disconnected evidence never confirms`() {
        // Gatilho aceita máscara de até 4 s (FFNet lenta no aparelho); mais velha que isso é ignorada.
        val machine = AutomaticDetectionManager(); machine.start()
        assertEquals(AnalysisState.MONITORING, machine.update(sample(0),5000))
        machine.update(sample(5500),5500)
        assertEquals(AnalysisState.POSSIBLE_CROSSING, machine.update(sample(5500),7000)) // repetida não conta
        assertEquals(AnalysisState.POSSIBLE_CROSSING, machine.update(sample(10000),10000)) // lacuna > 4 s reinicia
    }
    @Test fun `sidewalk is not required to start but road is`() {
        val machine = AutomaticDetectionManager(); machine.start()
        for (t in 1000L..10000L step 500) assertNotEquals(AnalysisState.ACTIVE_ANALYSIS, machine.update(sample(t,road=false,boundary=false),t))
        assertEquals(AnalysisState.INCONCLUSIVE,machine.state)
        machine.update(sample(10500,boundary=false),10500); machine.update(sample(11000,boundary=false),11000)
        assertEquals(AnalysisState.ACTIVE_ANALYSIS, machine.update(sample(12000,boundary=false),12000))
    }
    @Test fun `two samples are not enough even if far apart in time`() {
        val machine = AutomaticDetectionManager(500,5000,5000); machine.start()
        machine.update(sample(1000),1000)
        assertEquals(AnalysisState.POSSIBLE_CROSSING, machine.update(sample(2000),2000))
        assertEquals(AnalysisState.ACTIVE_ANALYSIS, machine.update(sample(2500),2500))
    }
    @Test fun `manual analysis starts without ffnet and normal end returns to waiting after cooldown`() {
        val machine = AutomaticDetectionManager(500,5000,5000); machine.start()
        assertTrue(machine.beginAnalysis())
        assertFalse(machine.beginAnalysis())
        assertEquals(MonitorPhase.ANALYZING, machine.phase)
        machine.finishAnalysis(10000)
        assertEquals(MonitorPhase.WAITING_FOR_ROAD, machine.phase)
        // Mesma rua logo após o término: não reinicia dentro do intervalo de rearme.
        for (t in 10500L..14500L step 500) assertNotEquals(AnalysisState.ACTIVE_ANALYSIS, machine.update(sample(t),t))
        machine.update(sample(15000),15000); machine.update(sample(15500),15500)
        assertEquals(AnalysisState.ACTIVE_ANALYSIS, machine.update(sample(16000),16000))
    }
    @Test fun `error and stop require explicit restart`() {
        val machine = AutomaticDetectionManager(); machine.start(); machine.error()
        assertEquals(AnalysisState.ERROR,machine.update(sample(1000),1000))
        machine.stop(); assertEquals(AnalysisState.STOPPED,machine.update(sample(2000),2000))
        for (t in 2500L..8000L step 500) assertEquals(MonitorPhase.STOPPED, machine.also { it.update(sample(t),t) }.phase)
    }
    @Test fun `pure road without sidewalk is not a crossing boundary`() {
        val r = RoadSceneAnalyzer.analyze(ByteArray(100),10,10,1000)
        assertTrue(r.roadDetected); assertFalse(r.possibleRoadBoundary); assertNull(r.confidence)
    }
    @Test fun `road and sidewalk boundary yields proportions not distance or confidence`() {
        val labels = ByteArray(100) { if (it % 10 < 5) 0 else 1 }
        val r = RoadSceneAnalyzer.analyze(labels,10,10,1000)
        assertTrue(r.possibleRoadBoundary); assertTrue(r.sidewalkDetected); assertNull(r.confidence)
    }
    @Test fun `nonurban labels are inconclusive`() {
        val r=RoadSceneAnalyzer.analyze(ByteArray(100){10},10,10,1000)
        assertFalse(r.roadDetected); assertFalse(r.sidewalkDetected)
    }
    @Test fun `quantization uses rgb value range and actual scale`() {
        assertEquals(0,RoadTensorCodec.quantize(0,.003920366987586021f,0))
        assertEquals(128,RoadTensorCodec.quantize(128,.003920366987586021f,0))
        assertEquals(255,RoadTensorCodec.quantize(255,.003920366987586021f,0))
    }
    @Test fun `argmax uses unsigned logits and bilinear interpolation`() {
        val scores=ByteBuffer.wrap(byteArrayOf(0,255.toByte(), 200.toByte(),1, 0,255.toByte(), 200.toByte(),1))
        assertEquals(1,RoadTensorCodec.argmax(scores,0f,0f,2,2,2))
        assertEquals(0,RoadTensorCodec.argmax(scores,1f,0f,2,2,2))
        assertEquals(1,RoadTensorCodec.argmax(scores,.5f,.5f,2,2,2))
    }
    @Test fun `speech accepts only clear yes or no`() {
        assertEquals(true,CrossingAnswer.parse("SIM!"))
        assertEquals(false,CrossingAnswer.parse("Não"))
        assertNull(CrossingAnswer.parse("não sei se sim"))
        assertNull(CrossingAnswer.parse("talvez"))
        assertNull(CrossingAnswer.parse(""))
        assertEquals(true,CrossingAnswer.parse("sim por favor"))
        assertEquals(true,CrossingAnswer.parse("sim eu quero"))
        assertNull(CrossingAnswer.parse("não quero sim"))
    }
}
