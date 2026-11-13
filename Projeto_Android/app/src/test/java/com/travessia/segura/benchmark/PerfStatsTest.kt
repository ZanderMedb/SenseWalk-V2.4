package com.travessia.segura.benchmark

import org.junit.Assert.*
import org.junit.Test

class PerfStatsTest {
    @Test fun percentilesAndRatesAreReportedPerWindow() {
        var now = 0L
        val out = mutableListOf<String>()
        val stats = PerfStats(reportEveryMs = 1000, clock = { now }, sink = { out += it })
        assertNull(stats.maybeReport()) // abre a janela
        for (v in 1..100) stats.record("yolo_infer_ms", v.toFloat())
        repeat(30) { stats.count("camera_frames") }
        now = 500; assertNull(stats.maybeReport())
        now = 1000
        val report = stats.maybeReport()!!
        assertTrue(report, report.contains("camera_frames=30.0/s"))
        assertTrue(report, report.contains("yolo_infer_ms(avg=50.5 p50=50.0 p95=95.0 n=100)"))
        assertEquals(listOf(report), out)
        now = 2000
        assertEquals("janela zerada após o relatório", "", stats.maybeReport())
    }

    @Test fun ringKeepsOnlyRecentSamples() {
        var now = 0L
        val stats = PerfStats(reportEveryMs = 10, capacity = 4, clock = { now }, sink = {})
        stats.maybeReport()
        for (v in listOf(100f, 100f, 1f, 2f, 3f, 4f)) stats.record("x", v)
        now = 10
        assertTrue(stats.maybeReport()!!.contains("x(avg=2.5 p50=2.0 p95=4.0 n=4)"))
    }

    @Test fun percentileOfEmptyIsZero() { assertEquals(0f, PerfStats.percentile(FloatArray(0), .95f)) }
}
