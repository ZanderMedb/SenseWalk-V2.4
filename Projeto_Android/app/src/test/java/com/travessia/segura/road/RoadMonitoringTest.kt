package com.travessia.segura.road

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class RoadMonitoringTest {
    private fun sample(time: Long, road: Boolean = true, boundary: Boolean = true) = RoadSegmentationResult(
        .4f, .2f, road, true, boundary, timestamp = time, boundaryRatio = .01f)
    @Test fun `persistent evidence activates but single frame does not`() {
        val machine = AutomaticDetectionManager(); machine.start()
        assertEquals(AnalysisState.POSSIBLE_CROSSING, machine.update(sample(1000),1000))
        machine.update(sample(2000),2000)
        assertEquals(AnalysisState.AWAITING_CONFIRMATION, machine.update(sample(2600),2600))
        assertTrue(machine.answer(true,2700))
        assertEquals(AnalysisState.STARTING_ANALYSIS,machine.state)
        machine.update(sample(2800),2800)
        assertEquals(AnalysisState.ACTIVE_ANALYSIS,machine.state)
    }
    @Test fun `stale repeated or disconnected evidence never confirms`() {
        val machine = AutomaticDetectionManager(); machine.start()
        assertEquals(AnalysisState.MONITORING, machine.update(sample(0),2000))
        machine.update(sample(2500),2500)
        assertEquals(AnalysisState.POSSIBLE_CROSSING, machine.update(sample(2500),4000))
        assertEquals(AnalysisState.POSSIBLE_CROSSING, machine.update(sample(6000),6000))
    }
    @Test fun `visible road alone never activates`() {
        val machine = AutomaticDetectionManager(); machine.start()
        for (t in 1000L..10000L step 500) assertNotEquals(AnalysisState.ACTIVE_ANALYSIS, machine.update(sample(t,boundary=false),t))
        assertEquals(AnalysisState.MONITORING,machine.state)
    }
    @Test fun `active analysis persists across loss and while yolo still sees hazards`() {
        val machine = AutomaticDetectionManager(500,5000,5000); machine.start()
        machine.update(sample(1000),1000); machine.update(sample(1500),1500)
        machine.answer(true,1500)
        machine.update(sample(1600),1600)
        for (t in 2000L..9000L step 500) {
            machine.observeYolo(true,t)
            assertEquals(AnalysisState.ACTIVE_ANALYSIS, machine.update(sample(t,false,false),t))
        }
        assertEquals(AnalysisState.ACTIVE_ANALYSIS,machine.update(sample(9500,false,false),9500))
        for (t in 10000L..14000L step 500) machine.update(sample(t,false,false),t)
        assertEquals(AnalysisState.MONITORING, machine.state)
        assertNotEquals(AnalysisState.ACTIVE_ANALYSIS,machine.update(sample(14500),14500))
    }
    @Test fun `error and stop require explicit restart`() {
        val machine = AutomaticDetectionManager(); machine.start(); machine.error()
        assertEquals(AnalysisState.ERROR,machine.update(sample(1000),1000))
        machine.stop(); assertEquals(AnalysisState.STOPPED,machine.update(sample(2000),2000))
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
    @Test fun `no does not start analysis or repeat for same visible road`() {
        val machine=AutomaticDetectionManager(500); machine.start()
        machine.update(sample(1000),1000); machine.update(sample(1500),1500)
        assertFalse(machine.answer(false,1600))
        for (t in 2000L..40000L step 500) assertNotEquals(AnalysisState.AWAITING_CONFIRMATION,machine.update(sample(t),t))
    }
    @Test fun `expired or stale yes cannot activate analysis`() {
        val machine=AutomaticDetectionManager(500); machine.start()
        machine.update(sample(1000),1000); machine.update(sample(1500),1500)
        assertFalse(machine.answer(true,27000))
        assertNotEquals(AnalysisState.ACTIVE_ANALYSIS,machine.state)
    }
    @Test fun `lost road cancels pending confirmation`() {
        val machine=AutomaticDetectionManager(500); machine.start()
        machine.update(sample(1000),1000); machine.update(sample(1500),1500)
        machine.update(sample(2000,false,false),2000)
        assertFalse(machine.answer(true,2100))
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
    @Test fun `road absence rearms after refusal`() {
        val machine=AutomaticDetectionManager(500,1000,1000); machine.start()
        machine.update(sample(1000),1000); machine.update(sample(1500),1500); machine.answer(false,1500)
        machine.update(sample(2000,false,false),2000); machine.update(sample(3000,false,false),3000)
        machine.update(sample(3500),3500)
        assertEquals(AnalysisState.AWAITING_CONFIRMATION,machine.update(sample(4000),4000))
    }
    @Test fun `yes after voice prompt waits for a fresh image instead of rejecting old sample`() {
        val machine=AutomaticDetectionManager(500); machine.start()
        machine.update(sample(1000),1000); machine.update(sample(1500),1500)
        assertTrue(machine.answer(true,9000))
        assertEquals(AnalysisState.STARTING_ANALYSIS,machine.state)
        machine.update(sample(9500),9600)
        assertEquals(AnalysisState.ACTIVE_ANALYSIS,machine.state)
    }
    @Test fun `old frame during listening does not reject pending question`() {
        val machine=AutomaticDetectionManager(500); machine.start()
        machine.update(sample(1000),1000); machine.update(sample(1500),1500)
        machine.update(sample(1600),4000)
        assertEquals(AnalysisState.AWAITING_CONFIRMATION,machine.state)
        assertTrue(machine.answer(true,5000))
    }
    @Test fun `accepted yes without any new usable image times out without yolo`() {
        val machine=AutomaticDetectionManager(500); machine.start()
        machine.update(sample(1000),1000); machine.update(sample(1500),1500)
        machine.answer(true,2000)
        machine.tick(12000)
        assertEquals(AnalysisState.INCONCLUSIVE,machine.state)
        machine.update(sample(12500),12500)
        assertNotEquals(AnalysisState.ACTIVE_ANALYSIS,machine.state)
    }
    @Test fun `fresh image without road after yes does not start yolo`() {
        val machine=AutomaticDetectionManager(500); machine.start()
        machine.update(sample(1000),1000); machine.update(sample(1500),1500)
        machine.answer(true,2000)
        machine.update(sample(2500,false,false),2500)
        assertEquals(AnalysisState.INCONCLUSIVE,machine.state)
    }
}
