package com.travessia.segura.camera

import org.junit.Assert.*
import org.junit.Test

class UsbVideoQualityTest {
    @Test fun `prefer native HD over low resolution`() {
        assertEquals(1,UsbVideoQuality.preferredIndex(listOf(UsbVideoQuality.Mode(640,360,true),UsbVideoQuality.Mode(1280,720,true))))
    }
    @Test fun `prefer compressed transport only when resolution matches`() {
        assertEquals(1,UsbVideoQuality.preferredIndex(listOf(UsbVideoQuality.Mode(1280,720,false),UsbVideoQuality.Mode(1280,720,true))))
    }
    @Test fun `do not invent HD or select unbounded modes`() {
        assertEquals(0,UsbVideoQuality.preferredIndex(listOf(UsbVideoQuality.Mode(640,360,true),UsbVideoQuality.Mode(4096,2160,true))))
        assertNull(UsbVideoQuality.preferredIndex(emptyList()))
    }
}
