package com.travessia.segura.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente que busca frames JPEG do ESP32 receptor via HTTP.
 * O ESP32 cria a rede WiFi "ESP-CAM-VIEW" e serve frames em 192.168.4.1.
 */
class EspCameraClient(
    private val baseUrl: String = "http://192.168.4.1",
    private val frameEndpoint: String = "/frame", // ajuste conforme seu firmware
    private val onFrame: (Bitmap) -> Unit,
    private val onError: (String) -> Unit,
    private val onFpsUpdate: (Int) -> Unit
) {
    companion object {
        private const val TAG = "EspCameraClient"
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS = 5000
        private const val MAX_FRAME_SIZE = 100_000 // 100KB max
    }

    private var job: Job? = null
    private var scope: CoroutineScope? = null

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var isConnected = false
        private set

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0

    /**
     * Inicia o loop de captura de frames.
     * O usuário deve estar conectado na rede WiFi "ESP-CAM-VIEW" antes de chamar.
     */
    fun iniciar() {
        if (isRunning) return

        isRunning = true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        job = scope?.launch {
            Log.i(TAG, "Iniciando captura de frames de $baseUrl$frameEndpoint")

            while (isActive && isRunning) {
                try {
                    val bitmap = fetchFrame()
                    if (bitmap != null) {
                        isConnected = true
                        atualizarFps()

                        // Entregar frame na main thread ou thread do chamador
                        withContext(Dispatchers.Main) {
                            onFrame(bitmap)
                        }
                    } else {
                        // Frame nulo mas sem exceção - tentar novamente rapidamente
                        delay(50)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    isConnected = false
                    Log.w(TAG, "Erro ao buscar frame: ${e.message}")
                    withContext(Dispatchers.Main) {
                        onError("Erro de conexão: ${e.message}")
                    }
                    // Esperar antes de tentar novamente
                    delay(1000)
                }
            }

            Log.i(TAG, "Loop de captura encerrado")
        }
    }

    /**
     * Para o loop de captura.
     */
    fun parar() {
        isRunning = false
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        isConnected = false
        Log.i(TAG, "Captura parada")
    }

    /**
     * Busca um frame JPEG do ESP32 e decodifica para Bitmap.
     */
    private fun fetchFrame(): Bitmap? {
        val url = URL("$baseUrl$frameEndpoint")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            // Evitar cache
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.useCaches = false

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP response: $responseCode")
                return null
            }

            val inputStream = connection.inputStream
            val buffer = ByteArrayOutputStream()
            val data = ByteArray(4096)
            var totalRead = 0
            var bytesRead: Int

            while (inputStream.read(data).also { bytesRead = it } != -1) {
                totalRead += bytesRead
                if (totalRead > MAX_FRAME_SIZE) {
                    Log.w(TAG, "Frame muito grande (>$MAX_FRAME_SIZE bytes), descartando")
                    return null
                }
                buffer.write(data, 0, bytesRead)
            }

            val jpegBytes = buffer.toByteArray()
            if (jpegBytes.size < 100) {
                Log.w(TAG, "Frame muito pequeno: ${jpegBytes.size} bytes")
                return null
            }

            // Decodificar JPEG para Bitmap
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)

        } catch (e: Exception) {
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Se o ESP32 serve um MJPEG stream em vez de frames individuais,
     * use esta versão alternativa.
     */
    fun iniciarMjpeg(mjpegEndpoint: String = "/stream") {
        if (isRunning) return
        isRunning = true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        job = scope?.launch {
            Log.i(TAG, "Conectando ao MJPEG stream: $baseUrl$mjpegEndpoint")

            while (isActive && isRunning) {
                try {
                    val url = URL("$baseUrl$mjpegEndpoint")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = 10000 // Maior para stream
                    connection.requestMethod = "GET"

                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        delay(1000)
                        continue
                    }

                    isConnected = true
                    val inputStream = connection.inputStream.buffered()

                    // Ler MJPEG: boundary entre frames
                    val headerBuffer = StringBuilder()
                    var contentLength = -1

                    while (isActive && isRunning) {
                        // Ler headers até linha vazia
                        headerBuffer.clear()
                        var line = readLine(inputStream)

                        while (line != null && line.isNotEmpty()) {
                            if (line.lowercase().startsWith("content-length:")) {
                                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                            }
                            line = readLine(inputStream)
                        }

                        if (contentLength <= 0) {
                            // Tentar ler até próximo boundary
                            // Buscar JPEG SOI (0xFF 0xD8) e EOI (0xFF 0xD9)
                            val jpegData = readUntilJpegEnd(inputStream) ?: continue
                            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                            if (bitmap != null) {
                                atualizarFps()
                                withContext(Dispatchers.Main) { onFrame(bitmap) }
                            }
                        } else {
                            // Ler exatamente contentLength bytes
                            val jpegData = ByteArray(contentLength)
                            var offset = 0
                            while (offset < contentLength) {
                                val read = inputStream.read(jpegData, offset, contentLength - offset)
                                if (read == -1) break
                                offset += read
                            }

                            if (offset == contentLength) {
                                val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                                if (bitmap != null) {
                                    atualizarFps()
                                    withContext(Dispatchers.Main) { onFrame(bitmap) }
                                }
                            }
                        }
                    }

                    connection.disconnect()

                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    isConnected = false
                    Log.w(TAG, "Erro no MJPEG stream: ${e.message}")
                    withContext(Dispatchers.Main) {
                        onError("Stream desconectado: ${e.message}")
                    }
                    delay(2000)
                }
            }
        }
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (c == '\n'.code) {
                val s = sb.toString().trimEnd('\r')
                return s
            }
            sb.append(c.toChar())
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    private fun readUntilJpegEnd(input: java.io.InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        var prev = -1
        var started = false
        var bytesRead = 0

        while (true) {
            val b = input.read()
            if (b == -1) return null
            bytesRead++
            if (bytesRead > MAX_FRAME_SIZE) return null

            if (!started) {
                if (prev == 0xFF && b == 0xD8) {
                    started = true
                    buffer.write(0xFF)
                    buffer.write(0xD8)
                }
            } else {
                buffer.write(b)
                if (prev == 0xFF && b == 0xD9) {
                    return buffer.toByteArray()
                }
            }
            prev = b
        }
    }

    private fun atualizarFps() {
        frameCount++
        val agora = System.currentTimeMillis()
        if (agora - lastFpsTime >= 1000L) {
            currentFps = frameCount
            frameCount = 0
            lastFpsTime = agora
            scope?.launch(Dispatchers.Main) {
                onFpsUpdate(currentFps)
            }
        }
    }

    /**
     * Testa se o ESP32 está acessível.
     */
    suspend fun testarConexao(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(baseUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.requestMethod = "GET"
                val code = connection.responseCode
                connection.disconnect()
                code == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                false
            }
        }
    }
}