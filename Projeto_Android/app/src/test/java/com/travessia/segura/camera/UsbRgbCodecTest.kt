package com.travessia.segura.camera

import org.junit.Assert.*
import org.junit.Test

class UsbRgbCodecTest {
    @Test fun `RGB bytes produce opaque ARGB without swapping red and blue`() {
        val rgb=byteArrayOf(255.toByte(),0,0, 0,255.toByte(),0, 0,0,255.toByte())
        assertArrayEquals(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt()),UsbRgbCodec.toArgb(rgb,3,1))
    }
    @Test(expected=IllegalArgumentException::class) fun `incorrect frame size is rejected`() {
        UsbRgbCodec.toArgb(ByteArray(1),640,480)
    }
    @Test(expected=IllegalArgumentException::class) fun `unbounded resolution is rejected`() {
        UsbRgbCodec.toArgb(ByteArray(0),Int.MAX_VALUE,Int.MAX_VALUE)
    }
}
