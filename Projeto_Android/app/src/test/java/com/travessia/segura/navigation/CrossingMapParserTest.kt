package com.travessia.segura.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingMapParserTest {
    private val origin = GeoPoint(-15.7800, -47.9290)

    @Test fun `parses node way center and crossing types`() {
        val response = """{"elements":[
          {"type":"node","id":1,"lat":-15.7801,"lon":-47.9291,"tags":{"highway":"crossing"}},
          {"type":"way","id":2,"center":{"lat":-15.7802,"lon":-47.9292},"tags":{"crossing":"traffic_signals"}},
          {"type":"node","id":3,"lat":-15.7803,"lon":-47.9293,"tags":{"highway":"traffic_signals"}}
        ]}"""
        val parsed = CrossingMapParser.parse(response, origin)
        assertEquals(3, parsed.size)
        assertTrue(parsed.any { it.type == CrossingType.PEDESTRIAN_CROSSING })
        assertTrue(parsed.any { it.type == CrossingType.SIGNALIZED_CROSSING })
        assertTrue(parsed.any { it.type == CrossingType.TRAFFIC_SIGNAL })
    }

    @Test fun `deduplicates repeated osm elements`() {
        val response = """{"elements":[
          {"type":"node","id":9,"lat":-15.7801,"lon":-47.9291,"tags":{"highway":"crossing"}},
          {"type":"node","id":9,"lat":-15.7801,"lon":-47.9291,"tags":{"crossing":"traffic_signals"}}
        ]}"""
        val parsed = CrossingMapParser.parse(response, origin)
        assertEquals(1, parsed.size)
        assertEquals(CrossingType.SIGNALIZED_CROSSING, parsed.single().type)
    }

    @Test fun `handles an empty mapped area`() {
        assertTrue(CrossingMapParser.parse("{\"elements\":[]}", origin).isEmpty())
    }
}
