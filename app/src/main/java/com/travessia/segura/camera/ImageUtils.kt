package com.travessia.segura.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Conversão correta de YUV_420_888 (CameraX) → Bitmap ARGB.
 * Trata rowStride e pixelStride que causam imagem corrompida se ignorados.
 */
object ImageUtils {

    private const val TAG = "ImageUtils"

    /**
     * Converte ImageProxy (YUV_420_888) → Bitmap via NV21 → JPEG.
     * Esta é a forma mais confiável e funciona em todos os dispositivos.
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val width = imageProxy.width
            val height = imageProxy.height

            val yPlane = imageProxy.planes[0]
            val uPlane = imageProxy.planes[1]
            val vPlane = imageProxy.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            // Alocar NV21: Y completo + VU interleaved
            val nv21 = ByteArray(width * height + width * (height / 2))
            var pos = 0

            // ── Copiar plano Y ──
            if (yRowStride == width) {
                // Sem padding — cópia direta (rápido)
                yBuffer.position(0)
                yBuffer.get(nv21, 0, width * height)
                pos = width * height
            } else {
                // Com padding — copiar linha a linha
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    yBuffer.get(nv21, pos, width)
                    pos += width
                }
            }

            // ── Copiar planos V e U (VU interleaved para NV21) ──
            val uvHeight = height / 2
            val uvWidth = width / 2

            if (uvPixelStride == 2 && uvRowStride == width) {
                // Caso otimizado: V e U já semi-planares
                // V buffer no formato NV21 (VUVU...)
                vBuffer.position(0)
                val vuSize = minOf(vBuffer.remaining(), width * uvHeight)
                vBuffer.get(nv21, pos, vuSize)
            } else {
                // Caso genérico: copiar pixel a pixel
                for (row in 0 until uvHeight) {
                    for (col in 0 until uvWidth) {
                        val uvOffset = row * uvRowStride + col * uvPixelStride
                        // NV21: V primeiro, U depois
                        nv21[pos++] = vBuffer.get(uvOffset)
                        nv21[pos++] = uBuffer.get(uvOffset)
                    }
                }
            }

            // ── NV21 → JPEG → Bitmap ──
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream(width * height)
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 92, out)
            val jpegBytes = out.toByteArray()

            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

        } catch (e: Exception) {
            Log.e(TAG, "Erro na conversão YUV→Bitmap: ${e.message}")
            null
        }
    }

    /**
     * Pré-processamento simplificado: ajuste de contraste.
     * Versão leve para mobile (sem OpenCV).
     * Pode ser desativado se a performance for um problema.
     */
    fun preprocessar(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // ── Histogram equalization simplificada ──
        val histogram = IntArray(256)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val lum = ((r * 77 + g * 150 + b * 29) shr 8).coerceIn(0, 255)
            histogram[lum]++
        }

        // CDF
        val cdf = IntArray(256)
        cdf[0] = histogram[0]
        for (i in 1..255) {
            cdf[i] = cdf[i - 1] + histogram[i]
        }

        val cdfMin = cdf.first { it > 0 }
        val total = pixels.size.toFloat()
        val denominator = total - cdfMin

        if (denominator <= 0f) {
            // Imagem uniforme — não processar
            return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        }

        val lut = IntArray(256) { i ->
            (((cdf[i] - cdfMin) / denominator) * 255f).toInt().coerceIn(0, 255)
        }

        // Aplicar equalização com blend 40% (não agressivo)
        val result = IntArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val lumOrig = ((r * 77 + g * 150 + b * 29) shr 8).coerceIn(0, 255)
            val lumEq = lut[lumOrig]

            if (lumOrig > 0) {
                val factor = 0.4f * (lumEq.toFloat() / lumOrig) + 0.6f
                val nr = (r * factor).toInt().coerceIn(0, 255)
                val ng = (g * factor).toInt().coerceIn(0, 255)
                val nb = (b * factor).toInt().coerceIn(0, 255)
                result[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            } else {
                result[i] = pixel
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(result, 0, width, 0, 0, width, height)
        return output
    }
}