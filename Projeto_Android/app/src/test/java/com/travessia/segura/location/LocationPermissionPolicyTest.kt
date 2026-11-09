package com.travessia.segura.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPermissionPolicyTest {
    @Test fun `precise or approximate foreground permission enables location`() {
        assertTrue(LocationPermissionPolicy.hasForegroundLocationPermission(true, false))
        assertTrue(LocationPermissionPolicy.hasForegroundLocationPermission(false, true))
        assertFalse(LocationPermissionPolicy.hasForegroundLocationPermission(false, false))
    }
}
