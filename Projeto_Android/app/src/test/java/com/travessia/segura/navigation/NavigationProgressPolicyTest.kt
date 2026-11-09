package com.travessia.segura.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationProgressPolicyTest {
    @Test fun `navigation continues with an instruction when near its route point`() {
        assertTrue(NavigationProgressPolicy.shouldAnnounceInstruction(24.9))
        assertFalse(NavigationProgressPolicy.shouldAnnounceInstruction(25.1))
    }

    @Test fun `route recalculation waits for three consecutive deviations`() {
        var readings = 0
        repeat(2) { readings = NavigationProgressPolicy.nextOffRouteReadings(40.0, readings) }
        assertFalse(NavigationProgressPolicy.shouldRecalculate(readings))
        readings = NavigationProgressPolicy.nextOffRouteReadings(40.0, readings)
        assertTrue(NavigationProgressPolicy.shouldRecalculate(readings))
        assertTrue(NavigationProgressPolicy.nextOffRouteReadings(10.0, readings) == 0)
    }

    @Test fun `cancelling navigation clears the shared active route`() {
        NavigationSessionStore.currentRoute = PedestrianRoute(
            destination = DestinationPlace("Destino", GeoPoint(0.0, 0.0)),
            distanceMeters = 10.0,
            timeMillis = 1_000,
            geometry = listOf(GeoPoint(0.0, 0.0)),
            instructions = emptyList()
        )
        NavigationSessionStore.clear()
        assertNull(NavigationSessionStore.currentRoute)
    }
}
