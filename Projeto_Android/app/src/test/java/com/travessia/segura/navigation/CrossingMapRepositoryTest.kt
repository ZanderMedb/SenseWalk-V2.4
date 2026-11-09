package com.travessia.segura.navigation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingMapRepositoryTest {
    @Test fun `network failure is returned without inventing crossings`() = runBlocking {
        val repository = CrossingMapRepository(endpoint = "not-a-valid-url")
        val result = repository.crossingsNearby(GeoPoint(-15.78, -47.93), 100)
        assertNotNull(result.error)
        assertTrue(result.crossings.isEmpty())
    }
}
