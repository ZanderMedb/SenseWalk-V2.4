package com.travessia.segura.camera

import java.net.URI

enum class X5StreamState {
    DESLIGADA,
    CONECTANDO,
    AGUARDANDO_STREAM,
    REPRODUZINDO,
    RECONECTANDO,
    ERRO
}

/** Small, Android-free pieces of the X5 protocol kept testable on the JVM. */
object X5Protocol {
    private const val MAX_CONTROL_RESPONSE_BYTES = 4 * 1024
    private val rtspUrl = Regex("rtsp://[^\\u0000\\s\\\"']+", RegexOption.IGNORE_CASE)
    private val mediaLine = Regex("m=video\\s+\\S+\\s+\\S+\\s+(.+)", RegexOption.IGNORE_CASE)
    private val rtpMap = Regex("a=rtpmap:(\\d+)\\s+([^/\\s]+)", RegexOption.IGNORE_CASE)

    data class RtspVideoFormat(
        val payloadType: Int,
        val codec: String,
        val usesStaticPayloadType: Boolean
    )

    fun parseControlResponse(response: ByteArray): String? {
        if (response.size !in 4..MAX_CONTROL_RESPONSE_BYTES) return null
        if ((response[0].toInt() and 0xFF) != 0x86 || (response[1].toInt() and 0xFF) != 0x86) return null

        // The device header includes a two-byte field after its 0x86 0x86 marker. Its
        // meaning is firmware-specific, so we validate bounds but search the full packet.
        val declaredLength = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        if (declaredLength > MAX_CONTROL_RESPONSE_BYTES) return null
        val text = response.toString(Charsets.ISO_8859_1)
        val candidate = rtspUrl.find(text)?.value ?: return null
        return try {
            URI(candidate).takeIf { it.scheme.equals("rtsp", ignoreCase = true) && !it.host.isNullOrBlank() }
                ?.toString()
        } catch (_: Exception) {
            null
        }
    }

    fun isStale(frameTimestampNanos: Long, nowNanos: Long, maxAgeMs: Long): Boolean {
        return frameTimestampNanos <= 0L || nowNanos - frameTimestampNanos > maxAgeMs * 1_000_000L
    }

    fun normalizedRotation(rotate180: Boolean): Int = if (rotate180) 180 else 0

    fun reconnectDelayMs(attempt: Int): Long = minOf(attempt.coerceAtLeast(1) * 1_000L, 5_000L)

    /**
     * JPEG has the static RTP payload type 26 (RFC 3551), so compliant SDP is allowed to
     * omit a=rtpmap. The initial implementation incorrectly rejected that valid form.
     */
    fun findJpegVideoFormat(sdp: String): RtspVideoFormat? {
        var videoPayloadTypes = emptySet<Int>()
        for (rawLine in sdp.lineSequence()) {
            val line = rawLine.trim()
            val media = mediaLine.find(line)
            if (media != null) {
                videoPayloadTypes = media.groupValues[1]
                    .split(Regex("\\s+"))
                    .mapNotNull { it.toIntOrNull() }
                    .toSet()
                continue
            }
            val map = rtpMap.find(line) ?: continue
            val payloadType = map.groupValues[1].toIntOrNull() ?: continue
            if (payloadType in videoPayloadTypes && map.groupValues[2].equals("JPEG", ignoreCase = true)) {
                return RtspVideoFormat(payloadType, "JPEG", usesStaticPayloadType = false)
            }
        }
        return if (26 in videoPayloadTypes) RtspVideoFormat(26, "JPEG", usesStaticPayloadType = true) else null
    }
}
