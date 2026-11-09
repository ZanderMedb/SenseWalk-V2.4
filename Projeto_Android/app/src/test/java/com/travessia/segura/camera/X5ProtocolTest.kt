package com.travessia.segura.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class X5ProtocolTest {
    @Test
    fun `parses rtsp url from validated control response`() {
        val url = "rtsp://192.168.1.1:7070/webcam/jpeg_dvp"
        val response = byteArrayOf(0x86.toByte(), 0x86.toByte(), 0x00, 0x36) + url.toByteArray()

        assertEquals(url, X5Protocol.parseControlResponse(response))
    }

    @Test
    fun `rejects response without x5 marker or valid rtsp`() {
        assertNull(X5Protocol.parseControlResponse(byteArrayOf(0x00, 0x00, 0x00, 0x00)))
        assertNull(X5Protocol.parseControlResponse(byteArrayOf(0x86.toByte(), 0x86.toByte(), 0x00, 0x01, 0x00)))
    }

    @Test
    fun `reports stale frames and x5 rotation deterministically`() {
        assertTrue(X5Protocol.isStale(1_000_000_000L, 4_001_000_000L, 3_000L))
        assertFalse(X5Protocol.isStale(1_000_000_000L, 3_999_000_000L, 3_000L))
        assertEquals(180, X5Protocol.normalizedRotation(true))
        assertEquals(0, X5Protocol.normalizedRotation(false))
    }

    @Test
    fun `declares all x5 lifecycle states`() {
        assertEquals(
            setOf("DESLIGADA", "CONECTANDO", "AGUARDANDO_STREAM", "REPRODUZINDO", "RECONECTANDO", "ERRO"),
            X5StreamState.entries.map { it.name }.toSet()
        )
    }

    @Test
    fun `limits reconnect backoff`() {
        assertEquals(1_000L, X5Protocol.reconnectDelayMs(1))
        assertEquals(5_000L, X5Protocol.reconnectDelayMs(9))
    }

    @Test
    fun `accepts jpeg static RTP payload without rtpmap`() {
        val sdp = """
            v=0
            m=video 0 RTP/AVP 26
            a=control:track1
        """.trimIndent()

        val format = X5Protocol.findJpegVideoFormat(sdp)

        assertEquals(26, format?.payloadType)
        assertEquals("JPEG", format?.codec)
        assertTrue(format?.usesStaticPayloadType == true)
    }

    @Test
    fun `accepts JPEG declared by rtpmap and rejects other codecs`() {
        val jpeg = "m=video 0 RTP/AVP 96\na=rtpmap:96 JPEG/90000"
        val h264 = "m=video 0 RTP/AVP 96\na=rtpmap:96 H264/90000"

        assertEquals(96, X5Protocol.findJpegVideoFormat(jpeg)?.payloadType)
        assertNull(X5Protocol.findJpegVideoFormat(h264))
    }
}
