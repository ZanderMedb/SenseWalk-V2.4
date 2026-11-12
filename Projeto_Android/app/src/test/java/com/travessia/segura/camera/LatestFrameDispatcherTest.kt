package com.travessia.segura.camera

import org.junit.Assert.*
import org.junit.Test

class LatestFrameDispatcherTest {
    @Test fun blockedUiKeepsOneDeliveryAndReleasesEverySupersededFrame() {
        val posted = mutableListOf<() -> Unit>()
        val released = mutableListOf<Int>()
        val displayed = mutableListOf<Int>()
        val dispatcher = LatestFrameDispatcher<Int>({ posted.add(it) },{ released.add(it) },{ displayed.add(it) })
        for (i in 1..1000) dispatcher.offer(i)
        assertEquals(1,posted.size)
        assertEquals((1..999).toList(),released)
        posted.removeAt(0).invoke()
        assertEquals(listOf(1000),displayed)
        dispatcher.clear()
        assertFalse(released.contains(1000)) // a UI agora é dona do bitmap entregue
    }
    @Test fun stoppingDiscardsPendingFrameAndAllowsRestart() {
        val posted = mutableListOf<() -> Unit>()
        val released = mutableListOf<Int>()
        val displayed = mutableListOf<Int>()
        val dispatcher = LatestFrameDispatcher<Int>({ posted.add(it) },{ released.add(it) },{ displayed.add(it) })
        dispatcher.offer(1); dispatcher.clear()
        posted.removeAt(0).invoke()
        assertEquals(listOf(1),released)
        assertTrue(displayed.isEmpty())
        dispatcher.offer(2); posted.removeAt(0).invoke()
        assertEquals(listOf(2),displayed)
    }
}
