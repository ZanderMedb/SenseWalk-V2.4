package com.travessia.segura.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import kotlin.math.max

/**
 * RTSP client for the X5 firmware endpoint. It deliberately supports JPEG RTP only: SDP is
 * checked before playback so an unknown codec cannot be presented as working video.
 */
class X5CameraClient(
    private val connectivityManager: ConnectivityManager,
    private val config: X5CameraConfig,
    private val onFrame: (VideoFrame) -> Unit,
    private val onStateChanged: (X5StreamState, String) -> Unit,
    private val onFpsUpdate: (Int) -> Unit
) {
    companion object {
        private const val TAG = "X5CameraClient"
        private const val SOCKET_TIMEOUT_MS = 4_000
        private const val MAX_CONTROL_RESPONSE = 4 * 1024
        private const val MAX_RTP_PACKET = 65_535
        private const val MAX_JPEG_SIZE = 2 * 1024 * 1024
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val CONTROL_PAYLOAD = byteArrayOf(
            0x86.toByte(), 0x86.toByte(), 0x00, 0x09, 0x00, 0x01, 0x00,
            0x04, 0x00, 0x02, 0x01, 0x01, 0x0E, 0x0D
        )
    }

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    @Volatile private var running = false
    @Volatile private var rtspSocket: Socket? = null
    @Volatile private var controlSocket: Socket? = null
    private var frameCount = 0
    private var fpsStartMs = 0L
    private var fpsLogCount = 0
    private var loggedResolution = false
    private var useUdpTransport = false

    fun start() {
        if (running) return
        running = true
        useUdpTransport = false
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        job = scope?.launch { streamLoop() }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        closeSockets()
        scope?.cancel()
        scope = null
        notifyState(X5StreamState.DESLIGADA, "Sessão X5 encerrada")
    }

    private suspend fun streamLoop() {
        var attempt = 0
        while (running && scope?.isActive == true) {
            try {
                notifyState(if (attempt == 0) X5StreamState.CONECTANDO else X5StreamState.RECONECTANDO,
                    if (attempt == 0) "Conectando à câmera X5 experimental" else "Reconectando à câmera X5")
                val network = activeWifiNetwork()
                val streamUrl = requestStreamUrl(network)
                notifyState(X5StreamState.AGUARDANDO_STREAM, "Negociando RTSP JPEG")
                playRtspJpeg(network, streamUrl, useUdpTransport)
                attempt = 0
            } catch (cancelled: CancellationException) {
                break
            } catch (unsupportedTransport: UnsupportedTransportException) {
                if (!useUdpTransport) {
                    useUdpTransport = true
                    Log.i(TAG, "X5 rejected RTP/TCP; retrying with RTP/UDP")
                    closeRtspSocket()
                    closeControlSocket()
                    continue
                }
                throw unsupportedTransport
            } catch (e: Exception) {
                closeRtspSocket()
                closeControlSocket()
                attempt++
                val message = e.message?.take(160) ?: "falha de conexão"
                Log.w(TAG, "X5 attempt=$attempt failed: $message")
                if (attempt >= MAX_RECONNECT_ATTEMPTS) {
                    notifyState(X5StreamState.ERRO, "X5 indisponível: $message. Toque em iniciar para tentar novamente.")
                    break
                }
                notifyState(X5StreamState.RECONECTANDO, "X5 sem vídeo, nova tentativa em breve")
                delay(X5Protocol.reconnectDelayMs(attempt))
            }
        }
    }

    private fun activeWifiNetwork(): Network? {
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        return if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) network else null
    }

    private fun requestStreamUrl(network: Network?): String {
        // The control connection remains open until stop(): some firmware stops RTSP otherwise.
        val socket = openSocket(network, config.controlHost, config.controlPort)
        controlSocket = socket
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.getOutputStream().write(CONTROL_PAYLOAD)
        socket.getOutputStream().flush()
        val response = readControlResponse(socket)
        val discovered = X5Protocol.parseControlResponse(response)
        if (discovered != null) {
            Log.i(TAG, "X5 control response supplied an RTSP URL")
            return discovered
        }
        Log.w(TAG, "X5 control response had no valid RTSP URL; using configured fallback")
        return config.fallbackRtspUrl
    }

    private fun readControlResponse(socket: Socket): ByteArray {
        val input = socket.getInputStream()
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(256)
        while (bytes.size() < MAX_CONTROL_RESPONSE) {
            val count = try { input.read(chunk) } catch (_: java.net.SocketTimeoutException) { break }
            if (count <= 0) break
            bytes.write(chunk, 0, count)
            if (count < chunk.size) break
        }
        return bytes.toByteArray()
    }

    private fun playRtspJpeg(network: Network?, url: String, useUdp: Boolean) {
        val uri = URI(url)
        require(uri.scheme.equals("rtsp", ignoreCase = true) && !uri.host.isNullOrBlank()) { "URL RTSP inválida" }
        val socket = openSocket(network, uri.host, if (uri.port > 0) uri.port else 554)
        rtspSocket = socket
        socket.soTimeout = 8_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        var cseq = 1
        request(output, "OPTIONS $url RTSP/1.0", cseq++)
        require(readRtspResponse(input).status in 200..299) { "OPTIONS RTSP recusado" }
        request(output, "DESCRIBE $url RTSP/1.0", cseq++, mapOf("Accept" to "application/sdp"))
        val describe = readRtspResponse(input)
        require(describe.status in 200..299) { "DESCRIBE RTSP recusado" }
        val videoFormat = X5Protocol.findJpegVideoFormat(describe.body)
            ?: throw IOException("Codec RTSP não suportado; SDP sem JPEG RTP")
        val trackUrl = trackUrl(url, describe)
        Log.i(
            TAG,
            "RTSP DESCRIBE selected video track=${trackUrl.substringAfterLast('/')} " +
                "contentBase=${describe.headers["content-base"].orEmpty()}"
        )
        var rtpSocket: DatagramSocket? = null
        var rtcpSocket: DatagramSocket? = null
        try {
            val transport = if (useUdp) {
                val rtp = openUdpSocket(network)
                val rtcp = openUdpSocket(network)
                rtpSocket = rtp
                rtcpSocket = rtcp
                "RTP/AVP;unicast;client_port=${rtp.localPort}-${rtcp.localPort}"
            } else {
                "RTP/AVP/TCP;unicast;interleaved=0-1"
            }
            request(output, "SETUP $trackUrl RTSP/1.0", cseq++, mapOf("Transport" to transport))
            val setup = readRtspResponse(input)
            if (setup.status == 461) throw UnsupportedTransportException(setup.statusLine)
            require(setup.status in 200..299) { "SETUP RTSP recusado: ${setup.statusLine}" }
            Log.i(TAG, "RTSP SETUP transport=${setup.headers["transport"].orEmpty()}")
            val session = setup.headers["session"]?.substringBefore(';') ?: throw IOException("RTSP sem Session")
            request(output, "PLAY $url RTSP/1.0", cseq, mapOf("Session" to session))
            require(readRtspResponse(input).status in 200..299) { "PLAY RTSP recusado" }
            val transportName = if (useUdp) "UDP" else "TCP"
            notifyState(X5StreamState.REPRODUZINDO, "X5 reproduzindo JPEG RTP/$transportName (experimental)")
            Log.i(
                TAG,
                "RTSP SDP detected codec=${videoFormat.codec}, payloadType=${videoFormat.payloadType}, " +
                    "staticPayload=${videoFormat.usesStaticPayloadType}, transport=$transportName"
            )
            if (useUdp) readUdpRtp(rtpSocket!!, videoFormat.payloadType)
            else readInterleavedRtp(input, videoFormat.payloadType)
        } finally {
            rtpSocket?.close()
            rtcpSocket?.close()
        }
    }

    private fun request(output: OutputStream, requestLine: String, cseq: Int, extra: Map<String, String> = emptyMap()) {
        val message = buildString {
            append(requestLine).append("\r\nCSeq: ").append(cseq).append("\r\nUser-Agent: SenseWalk-X5\r\n")
            extra.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        output.write(message.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private data class RtspResponse(
        val status: Int,
        val statusLine: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readRtspResponse(input: BufferedInputStream): RtspResponse {
        val statusLine = readLine(input) ?: throw IOException("RTSP sem resposta")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Resposta RTSP inválida")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("Cabeçalho RTSP truncado")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 64 * 1024) ?: 0
        val body = ByteArray(length)
        readFully(input, body, 0, length)
        return RtspResponse(status, statusLine, headers, body.toString(Charsets.UTF_8))
    }

    private fun trackUrl(baseUrl: String, describe: RtspResponse): String {
        var inVideoSection = false
        var control = ""
        for (rawLine in describe.body.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("m=", ignoreCase = true)) {
                inVideoSection = line.startsWith("m=video", ignoreCase = true)
                continue
            }
            if (inVideoSection && line.startsWith("a=control:", ignoreCase = true)) {
                control = line.substringAfter(':').trim()
                break
            }
        }
        if (control.isBlank() || control == "*") return baseUrl
        if (control.startsWith("rtsp://", ignoreCase = true)) return control
        val contentBase = describe.headers["content-base"]
            ?.takeIf { it.startsWith("rtsp://", ignoreCase = true) }
            ?: describe.headers["content-location"]
                ?.takeIf { it.startsWith("rtsp://", ignoreCase = true) }
            ?: baseUrl
        return contentBase.trimEnd('/') + "/" + control.removePrefix("/")
    }

    private fun readInterleavedRtp(input: BufferedInputStream, payloadType: Int) {
        val assembler = JpegRtpAssembler()
        while (running) {
            val marker = input.read()
            if (marker < 0) throw IOException("RTSP desconectado")
            if (marker != '$'.code) {
                // RTSP keepalive/response interspersed with RTP: discard its line safely.
                readLine(input)
                continue
            }
            val channel = input.read()
            val length = (input.read() shl 8) or input.read()
            if (channel < 0 || length !in 12..MAX_RTP_PACKET) throw IOException("Pacote RTP inválido")
            val packet = ByteArray(length)
            readFully(input, packet, 0, length)
            if (channel != 0 || (packet[1].toInt() and 0x7F) != payloadType) continue
            assembler.add(packet)?.let { jpeg ->
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@let
                deliverFrame(bitmap)
            }
        }
    }

    private fun readUdpRtp(socket: DatagramSocket, payloadType: Int) {
        val assembler = JpegRtpAssembler()
        val buffer = ByteArray(MAX_RTP_PACKET)
        var diagnosticPackets = 0
        var decodeFailures = 0
        while (running) {
            val datagram = DatagramPacket(buffer, buffer.size)
            socket.receive(datagram)
            if (datagram.length < 12) continue
            val packet = buffer.copyOfRange(datagram.offset, datagram.offset + datagram.length)
            if (diagnosticPackets < 5) {
                diagnosticPackets++
                val receivedPayloadType = packet[1].toInt() and 0x7F
                val jpegHeader = if (packet.size >= 20) {
                    " jpegType=${packet[16].toInt() and 0xFF} q=${packet[17].toInt() and 0xFF}"
                } else {
                    ""
                }
                Log.d(
                    TAG,
                    "RTP/UDP packet bytes=${packet.size} source=${datagram.address.hostAddress}:${datagram.port} " +
                        "payloadType=$receivedPayloadType expected=$payloadType$jpegHeader"
                )
            }
            if ((packet[1].toInt() and 0x7F) != payloadType) continue
            assembler.add(packet)?.let { jpeg ->
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bitmap == null) {
                    if (decodeFailures++ < 3) Log.w(TAG, "JPEG RTP frame could not be decoded, bytes=${jpeg.size}")
                    return@let
                }
                deliverFrame(bitmap)
            }
        }
    }

    private fun deliverFrame(bitmap: Bitmap) {
        frameCount++
        val now = SystemClock.elapsedRealtimeNanos()
        if (!loggedResolution) {
            loggedResolution = true
            Log.i(TAG, "X5 JPEG RTP resolution=${bitmap.width}x${bitmap.height}, rotation=${X5Protocol.normalizedRotation(config.rotate180)}")
        }
        onFrame(VideoFrame(bitmap, now, X5Protocol.normalizedRotation(config.rotate180)))
        val nowMs = now / 1_000_000L
        if (fpsStartMs == 0L) fpsStartMs = nowMs
        if (nowMs - fpsStartMs >= 1_000L) {
            onFpsUpdate(frameCount)
            fpsLogCount++
            if (fpsLogCount % 10 == 0) Log.d(TAG, "X5 received $frameCount FPS")
            frameCount = 0
            fpsStartMs = nowMs
        }
    }

    private fun openSocket(network: Network?, host: String, port: Int): Socket {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        socket.connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
        return socket
    }

    private fun openUdpSocket(network: Network?): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
        network?.bindSocket(socket)
        socket.soTimeout = 8_000
        return socket
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (out.size() < 16 * 1024) {
            val value = input.read()
            if (value < 0) return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
            if (value == '\n'.code) return out.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            out.write(value)
        }
        throw IOException("Linha RTSP excede o limite")
    }

    private fun readFully(input: BufferedInputStream, destination: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val count = input.read(destination, offset + read, length - read)
            if (count < 0) throw IOException("Stream truncado")
            read += count
        }
    }

    private fun notifyState(state: X5StreamState, message: String) {
        scope?.launch(Dispatchers.Main) { onStateChanged(state, message) }
    }

    private fun closeRtspSocket() {
        try { rtspSocket?.close() } catch (_: Exception) {}
        rtspSocket = null
    }

    private fun closeSockets() {
        closeRtspSocket()
        closeControlSocket()
    }

    private fun closeControlSocket() {
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = null
    }

    private class UnsupportedTransportException(statusLine: String) : IOException("SETUP RTSP recusado: $statusLine")

    /** Reassembles baseline RFC 2435 JPEG payloads; incomplete frames are intentionally dropped. */
    private class JpegRtpAssembler {
        private var timestamp = -1L
        private var expectedOffset = 0
        private var width = 0
        private var height = 0
        private var quality = 75
        private var type = 0
        private var hasRestartMarkers = false
        private var restartInterval = 0
        private var quantizationTables: ByteArray? = null
        private var invalid = false
        private var diagnosticFrames = 0
        private val scan = ByteArrayOutputStream()

        fun add(packet: ByteArray): ByteArray? {
            if (packet.size < 20) return null
            var packetEnd = packet.size
            if (packet[0].toInt() and 0x20 != 0) {
                val padding = packet.last().toInt() and 0xFF
                if (padding == 0 || padding > packetEnd) return null
                packetEnd -= padding
            }
            val csrcCount = packet[0].toInt() and 0x0F
            val hasExtension = packet[0].toInt() and 0x10 != 0
            var position = 12 + csrcCount * 4
            if (hasExtension) {
                if (packetEnd < position + 4) return null
                val words = ((packet[position + 2].toInt() and 0xFF) shl 8) or (packet[position + 3].toInt() and 0xFF)
                position += 4 + words * 4
            }
            if (packetEnd < position + 8) return null
            val marker = packet[1].toInt() and 0x80 != 0
            val packetTimestamp = ((packet[4].toLong() and 0xFF) shl 24) or ((packet[5].toLong() and 0xFF) shl 16) or
                ((packet[6].toLong() and 0xFF) shl 8) or (packet[7].toLong() and 0xFF)
            val offset = ((packet[position + 1].toInt() and 0xFF) shl 16) or ((packet[position + 2].toInt() and 0xFF) shl 8) or (packet[position + 3].toInt() and 0xFF)
            val currentType = packet[position + 4].toInt() and 0xFF
            val currentQuality = packet[position + 5].toInt() and 0xFF
            val currentWidth = (packet[position + 6].toInt() and 0xFF) * 8
            val currentHeight = (packet[position + 7].toInt() and 0xFF) * 8
            position += 8

            if (offset == 0) {
                timestamp = packetTimestamp
                expectedOffset = 0
                width = currentWidth
                height = currentHeight
                type = currentType and 0x3F
                hasRestartMarkers = currentType in 64..127
                quality = if (currentQuality in 1..99) currentQuality else 75
                restartInterval = 0
                quantizationTables = null
                invalid = width <= 0 || height <= 0 || type !in 0..1
                scan.reset()
                if (hasRestartMarkers) {
                    if (packetEnd < position + 4) {
                        invalid = true
                    } else {
                        restartInterval = ((packet[position].toInt() and 0xFF) shl 8) or
                            (packet[position + 1].toInt() and 0xFF)
                        position += 4
                    }
                }
                if (currentQuality >= 128) {
                    if (packetEnd < position + 4) {
                        invalid = true
                    } else {
                        val precision = packet[position + 1].toInt() and 0xFF
                        val tableLength = ((packet[position + 2].toInt() and 0xFF) shl 8) or
                            (packet[position + 3].toInt() and 0xFF)
                        val tableEnd = position + 4 + tableLength
                        if (precision != 0 || tableLength !in setOf(64, 128) || tableEnd > packetEnd) {
                            invalid = true
                        } else {
                            quantizationTables = packet.copyOfRange(position + 4, tableEnd)
                            position = tableEnd
                        }
                    }
                }
            } else if (currentType and 0x3F != type || (currentType in 64..127) != hasRestartMarkers) {
                invalid = true
            } else if (hasRestartMarkers) {
                if (packetEnd < position + 4) invalid = true else position += 4
            }
            if (packetTimestamp != timestamp || offset != expectedOffset || position > packetEnd) {
                invalid = true
            } else if (!invalid) {
                val payloadLength = packetEnd - position
                if (scan.size() + payloadLength > MAX_JPEG_SIZE) invalid = true
                else {
                    scan.write(packet, position, payloadLength)
                    expectedOffset += payloadLength
                }
            }
            if (!marker) return null
            if (invalid || scan.size() == 0) {
                if (diagnosticFrames++ < 3) {
                    Log.w(
                        TAG,
                        "Dropped JPEG RTP frame invalid=$invalid scan=${scan.size()} offset=$offset " +
                            "expected=$expectedOffset type=$type restart=$restartInterval tables=${quantizationTables?.size ?: 0}"
                    )
                }
                return null
            }
            if (diagnosticFrames++ < 3) {
                val scanBytes = scan.toByteArray()
                Log.d(
                    TAG,
                    "Assembled JPEG RTP frame scan=${scan.size()} type=$type restart=$restartInterval " +
                        "tables=${quantizationTables?.size ?: 0} rst=${restartMarkerCount(scanBytes)} " +
                        "start=${scanSignature(scanBytes, 0)} end=${scanSignature(scanBytes, (scanBytes.size - 8).coerceAtLeast(0))}"
                )
            }
            return buildJpeg(scan.toByteArray(), width, height, type, quality, restartInterval, quantizationTables)
        }

        private fun restartMarkerCount(bytes: ByteArray): Int = bytes.indices.count { index ->
            index + 1 < bytes.size && bytes[index] == 0xFF.toByte() &&
                (bytes[index + 1].toInt() and 0xF8) == 0xD0
        }

        private fun scanSignature(bytes: ByteArray, start: Int): String = bytes
            .copyOfRange(start, (start + 8).coerceAtMost(bytes.size))
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }

        private fun buildJpeg(
            payload: ByteArray,
            width: Int,
            height: Int,
            type: Int,
            quality: Int,
            restartInterval: Int,
            dynamicQuantizationTables: ByteArray?
        ): ByteArray {
            val out = ByteArrayOutputStream(payload.size + 700)
            out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            out.write(JFIF)
            writeDqt(out, quality, dynamicQuantizationTables)
            if (restartInterval > 0) writeDri(out, restartInterval)
            writeSof0(out, width, height, type)
            out.write(DHT)
            out.write(SOS)
            val scanEnd = rtpScanEnd(payload)
            out.write(payload, 0, scanEnd)
            out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
            return out.toByteArray()
        }

        private fun rtpScanEnd(payload: ByteArray): Int {
            var end = payload.size
            while (end > 0 && payload[end - 1] == 0.toByte()) end--
            return if (end >= 2 && payload[end - 2] == 0xFF.toByte() && payload[end - 1] == 0xD9.toByte()) {
                end - 2
            } else {
                payload.size
            }
        }

        private fun writeDqt(out: ByteArrayOutputStream, quality: Int, dynamicTables: ByteArray?) {
            if (dynamicTables != null) {
                val tableCount = if (dynamicTables.size == 128) 2 else 1
                for (index in 0 until tableCount) {
                    // Separate DQT segments follow the RFC 2435 reference implementation.
                    out.write(byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, index.toByte()))
                    out.write(dynamicTables, index * 64, 64)
                }
                return
            }
            val scale = if (quality < 50) 5000 / max(quality, 1) else 200 - quality * 2
            out.write(byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x00, 0x84.toByte(), 0x00))
            LUMA_Q.forEach { out.write(((it * scale + 50) / 100).coerceIn(1, 255)) }
            out.write(0x01)
            CHROMA_Q.forEach { out.write(((it * scale + 50) / 100).coerceIn(1, 255)) }
        }

        private fun writeDri(out: ByteArrayOutputStream, restartInterval: Int) {
            out.write(byteArrayOf(0xFF.toByte(), 0xDD.toByte(), 0x00, 0x04,
                (restartInterval shr 8).toByte(), restartInterval.toByte()))
        }

        private fun writeSof0(out: ByteArrayOutputStream, width: Int, height: Int, type: Int) {
            out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08,
                (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 0x03,
                0x00, if (type == 0) 0x21 else 0x22, 0x00, 0x01, 0x11, 0x01, 0x02, 0x11, 0x01))
        }

        private companion object {
            val JFIF = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)
            val SOS = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x0C, 0x03, 0x00, 0x00, 0x01, 0x11, 0x02, 0x11, 0x00, 0x3F, 0x00)
            val LUMA_Q = intArrayOf(16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99)
            val CHROMA_Q = intArrayOf(17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99)
            // Standard baseline Huffman tables, encoded as the four valid DHT segments.
            val DHT = Base64.decode("/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6", Base64.DEFAULT)
            val LEGACY_DHT = byteArrayOf(
                0xFF.toByte(), 0xC4.toByte(), 0x01, 0xA2.toByte(), 0x00, 0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
                0x10, 0x00, 0x02, 0x01, 0x03, 0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00, 0x01, 0x7D, 0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81.toByte(), 0x91.toByte(), 0xA1.toByte(), 0x08, 0x23, 0x42, 0xB1.toByte(), 0xC1.toByte(), 0x15, 0x52, 0xD1.toByte(), 0xF0.toByte(), 0x24, 0x33, 0x62, 0x72, 0x82.toByte(), 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83.toByte(), 0x84.toByte(), 0x85.toByte(), 0x86.toByte(), 0x87.toByte(), 0x88.toByte(), 0x89.toByte(), 0x8A.toByte(), 0x92.toByte(), 0x93.toByte(), 0x94.toByte(), 0x95.toByte(), 0x96.toByte(), 0x97.toByte(), 0x98.toByte(), 0x99.toByte(), 0x9A.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(), 0xA8.toByte(), 0xA9.toByte(), 0xAA.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte(), 0xB6.toByte(), 0xB7.toByte(), 0xB8.toByte(), 0xB9.toByte(), 0xBA.toByte(), 0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte(), 0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(), 0xC8.toByte(), 0xC9.toByte(), 0xCA.toByte(), 0xD2.toByte(), 0xD3.toByte(), 0xD4.toByte(), 0xD5.toByte(), 0xD6.toByte(), 0xD7.toByte(), 0xD8.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xE1.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xE4.toByte(), 0xE5.toByte(), 0xE6.toByte(), 0xE7.toByte(), 0xE8.toByte(), 0xE9.toByte(), 0xEA.toByte(), 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(), 0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(), 0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte(),
                0x01, 0x00, 0x03, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
                0x11, 0x00, 0x02, 0x01, 0x02, 0x04, 0x04, 0x03, 0x04, 0x07, 0x05, 0x04, 0x04, 0x00, 0x01, 0x02, 0x77, 0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71, 0x13, 0x22, 0x32, 0x81.toByte(), 0x08, 0x14, 0x42, 0x91.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0xC1.toByte(), 0x09, 0x23, 0x33, 0x52, 0xF0.toByte(), 0x15, 0x62, 0x72, 0xD1.toByte(), 0x0A, 0x16, 0x24, 0x34, 0xE1.toByte(), 0x25, 0xF1.toByte(), 0x17, 0x18, 0x19, 0x1A, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x82.toByte(), 0x83.toByte(), 0x84.toByte(), 0x85.toByte(), 0x86.toByte(), 0x87.toByte(), 0x88.toByte(), 0x89.toByte(), 0x8A.toByte(), 0x92.toByte(), 0x93.toByte(), 0x94.toByte(), 0x95.toByte(), 0x96.toByte(), 0x97.toByte(), 0x98.toByte(), 0x99.toByte(), 0x9A.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte(), 0xA8.toByte(), 0xA9.toByte(), 0xAA.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte(), 0xB6.toByte(), 0xB7.toByte(), 0xB8.toByte(), 0xB9.toByte(), 0xBA.toByte(), 0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte(), 0xC5.toByte(), 0xC6.toByte(), 0xC7.toByte(), 0xC8.toByte(), 0xC9.toByte(), 0xCA.toByte(), 0xD2.toByte(), 0xD3.toByte(), 0xD4.toByte(), 0xD5.toByte(), 0xD6.toByte(), 0xD7.toByte(), 0xD8.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xE4.toByte(), 0xE5.toByte(), 0xE6.toByte(), 0xE7.toByte(), 0xE8.toByte(), 0xE9.toByte(), 0xEA.toByte(), 0xF2.toByte(), 0xF3.toByte(), 0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(), 0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte()
            )
        }
    }
}
