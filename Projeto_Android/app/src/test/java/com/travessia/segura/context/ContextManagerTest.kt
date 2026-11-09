package com.travessia.segura.context

import com.travessia.segura.navigation.CrossingPoint
import com.travessia.segura.navigation.CrossingType
import com.travessia.segura.navigation.GeoPoint
import com.travessia.segura.navigation.LocationSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {
    private var time = 10_000L
    private fun manager(cooldown: Long = 90_000L) = ContextManager(
        alertDistanceMeters = 30.0,
        maximumAccuracyMeters = 35f,
        cooldownMs = cooldown,
        analysisDurationMs = 20_000L,
        now = { time }
    )
    private val point = GeoPoint(-15.7800, -47.9290)
    private val crossing = CrossingPoint("node/1", -15.7799, -47.9290, CrossingType.PEDESTRIAN_CROSSING, 12.0)
    private fun location(accuracy: Float = 10f) = LocationSnapshot(point, accuracy, 1f, 0f, time)

    @Test fun `state machine follows walking possible crossing and analysis`() {
        val context = manager()
        context.start()
        context.onWalkingChanged(true)
        val first = context.onLocation(location(), listOf(crossing))
        val second = context.onLocation(location(), listOf(crossing))
        assertTrue(first.any { it is ContextAction.AnnouncePossibleCrossing })
        assertTrue(second.any { it is ContextAction.StartCrossingAnalysis })
        assertTrue(context.state == MonitoringState.ANALYSING_CROSSING)
    }

    @Test fun `low GPS accuracy never starts analysis`() {
        val context = manager()
        context.start(); context.onWalkingChanged(true)
        val actions = context.onLocation(location(80f), listOf(crossing))
        assertTrue(actions.any { it is ContextAction.LocationImprecise })
        assertFalse(actions.any { it is ContextAction.StartCrossingAnalysis })
    }

    @Test fun `cooldown prevents repeated activation`() {
        val context = manager(cooldown = 90_000)
        context.start(); context.onWalkingChanged(true)
        context.onLocation(location(), listOf(crossing))
        context.onLocation(location(), listOf(crossing))
        context.analysisStoppedExternally()
        time += 1_000
        context.onLocation(location(), listOf(crossing))
        val actions = context.onLocation(location(), listOf(crossing))
        assertFalse(actions.any { it is ContextAction.StartCrossingAnalysis })
    }

    @Test fun `hysteresis avoids leaving possible crossing on a small distance oscillation`() {
        val context = ContextManager(
            alertDistanceMeters = 30.0,
            maximumAccuracyMeters = 35f,
            cooldownMs = 90_000L,
            analysisDurationMs = 20_000L,
            exitHysteresisMeters = 15.0,
            now = { time }
        )
        context.start(); context.onWalkingChanged(true)
        context.onLocation(location(), listOf(crossing))
        val oscillating = crossing.copy(distanceMeters = 40.0)
        context.onLocation(location(), listOf(oscillating))
        assertTrue(context.state == MonitoringState.POSSIBLE_CROSSING)
    }

    @Test fun `analysis is stopped after controlled duration`() {
        val context = manager()
        context.start(); context.onWalkingChanged(true)
        context.onLocation(location(), listOf(crossing))
        context.onLocation(location(), listOf(crossing))
        time += 20_000
        assertTrue(context.tick().any { it is ContextAction.StopCrossingAnalysis })
    }
}
