package com.travessia.segura.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test fun `distance between identical coordinates is zero`() {
        val point = GeoPoint(-15.7801, -47.9292)
        assertEquals(0.0, GeoMath.distanceMeters(point, point), 0.001)
    }

    @Test fun `distance calculation is approximately correct`() {
        val origin = GeoPoint(-15.7801, -47.9292)
        val nearby = GeoPoint(-15.7801, -47.9282)
        assertTrue(GeoMath.distanceMeters(origin, nearby) in 100.0..115.0)
    }

    @Test fun `bearing difference recognises opposite directions`() {
        assertEquals(180.0, GeoMath.angularDifferenceDegrees(0.0, 180.0), 0.001)
    }
}
