package com.travessia.segura.vision

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Estimador leve de movimento global da câmera baseado em fluxo óptico esparso.
 *
 * Para manter o projeto Android simples e sem uma biblioteca nativa extra, esta versão
 * usa block matching em uma malha de pontos de fundo e mediana robusta dos vetores.
 * O resultado é equivalente ao que precisamos do optical flow neste protótipo:
 * estimar a translação dominante entre dois frames consecutivos.
 *
 * Não tenta estimar rotação 3D da cabeça; isso fica para uma etapa futura com IMU ou
 * homografia. Mesmo assim, remove boa parte das pequenas tremidas horizontais/verticais.
 */
class SparseOpticalFlowEstimator(
    private val sampleWidth: Int = 160,
    private val gridStep: Int = 18,
    private val blockRadius: Int = 2,
    private val searchRadius: Int = 5
) {
    data class MotionEstimate(
        val dx: Float = 0f,
        val dy: Float = 0f,
        val confidence: Float = 0f,
        val validPoints: Int = 0,
        val processingMs: Long = 0L
    ) {
        val magnitude: Float get() = hypot(dx, dy)
    }

    private var previousGray: IntArray? = null
    private var previousW: Int = 0
    private var previousH: Int = 0

    fun estimate(bitmap: Bitmap, rotationDegrees: Int = 0): MotionEstimate {
        val started = SystemClock.elapsedRealtimeNanos()
        val sampled = sampleGray(bitmap)

        val prev = previousGray
        if (prev == null || previousW != sampled.width || previousH != sampled.height) {
            previousGray = sampled.gray
            previousW = sampled.width
            previousH = sampled.height
            return MotionEstimate(processingMs = elapsedMs(started))
        }

        val vectorsX = mutableListOf<Int>()
        val vectorsY = mutableListOf<Int>()
        val margin = blockRadius + searchRadius + 1

        var y = margin
        while (y < sampled.height - margin) {
            var x = margin
            while (x < sampled.width - margin) {
                if (hasEnoughTexture(prev, sampled.width, x, y)) {
                    val match = findBestDisplacement(
                        prev = prev,
                        curr = sampled.gray,
                        width = sampled.width,
                        x = x,
                        y = y
                    )
                    if (match != null) {
                        vectorsX.add(match.first)
                        vectorsY.add(match.second)
                    }
                }
                x += gridStep
            }
            y += gridStep
        }

        previousGray = sampled.gray
        previousW = sampled.width
        previousH = sampled.height

        if (vectorsX.size < 4) {
            return MotionEstimate(
                confidence = 0f,
                validPoints = vectorsX.size,
                processingMs = elapsedMs(started)
            )
        }

        val medX = median(vectorsX)
        val medY = median(vectorsY)

        // Remove vetores discordantes. Objetos em movimento tendem a virar outliers,
        // enquanto o fundo normalmente forma o movimento dominante.
        val inliers = vectorsX.indices.filter { i ->
            abs(vectorsX[i] - medX) <= 2 && abs(vectorsY[i] - medY) <= 2
        }

        val robustX: Float
        val robustY: Float
        if (inliers.size >= 3) {
            robustX = medianFloat(inliers.map { vectorsX[it] })
            robustY = medianFloat(inliers.map { vectorsY[it] })
        } else {
            robustX = medX.toFloat()
            robustY = medY.toFloat()
        }

        val scaleX = bitmap.width.toFloat() / sampled.width.toFloat()
        val scaleY = bitmap.height.toFloat() / sampled.height.toFloat()
        val rawDx = robustX * scaleX
        val rawDy = robustY * scaleY

        val rotated = rotateVector(rawDx, rawDy, rotationDegrees)
        val inlierRatio = inliers.size.toFloat() / vectorsX.size.toFloat()
        val pointFactor = min(1f, vectorsX.size / 14f)
        val confidence = (inlierRatio * pointFactor).coerceIn(0f, 1f)

        return MotionEstimate(
            dx = rotated.first,
            dy = rotated.second,
            confidence = confidence,
            validPoints = vectorsX.size,
            processingMs = elapsedMs(started)
        )
    }

    fun reset() {
        previousGray = null
        previousW = 0
        previousH = 0
    }

    private data class SampledGray(val gray: IntArray, val width: Int, val height: Int)

    private fun sampleGray(bitmap: Bitmap): SampledGray {
        val targetW = min(sampleWidth, bitmap.width).coerceAtLeast(32)
        val targetH = max(24, (bitmap.height * targetW.toFloat() / bitmap.width).toInt())
        val scaled = if (bitmap.width == targetW && bitmap.height == targetH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        }

        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaled !== bitmap) scaled.recycle()

        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (77 * r + 150 * g + 29 * b) shr 8
        }
        return SampledGray(gray, targetW, targetH)
    }

    private fun hasEnoughTexture(gray: IntArray, width: Int, x: Int, y: Int): Boolean {
        var minV = 255
        var maxV = 0
        for (dy in -blockRadius..blockRadius) {
            val row = (y + dy) * width
            for (dx in -blockRadius..blockRadius) {
                val v = gray[row + x + dx]
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
        }
        return (maxV - minV) >= 18
    }

    private fun findBestDisplacement(
        prev: IntArray,
        curr: IntArray,
        width: Int,
        x: Int,
        y: Int
    ): Pair<Int, Int>? {
        var bestScore = Int.MAX_VALUE
        var secondBest = Int.MAX_VALUE
        var bestDx = 0
        var bestDy = 0

        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                var sad = 0
                for (by in -blockRadius..blockRadius) {
                    val prevRow = (y + by) * width
                    val currRow = (y + by + dy) * width
                    for (bx in -blockRadius..blockRadius) {
                        sad += abs(prev[prevRow + x + bx] - curr[currRow + x + bx + dx])
                    }
                }

                if (sad < bestScore) {
                    secondBest = bestScore
                    bestScore = sad
                    bestDx = dx
                    bestDy = dy
                } else if (sad < secondBest) {
                    secondBest = sad
                }
            }
        }

        val samples = (blockRadius * 2 + 1) * (blockRadius * 2 + 1)
        val meanError = bestScore.toFloat() / samples.toFloat()
        if (meanError > 42f) return null

        // Rejeita matches muito ambíguos em texturas repetitivas.
        if (secondBest < Int.MAX_VALUE && secondBest > 0) {
            val uniqueness = secondBest.toFloat() / max(1, bestScore).toFloat()
            if (uniqueness < 1.035f) return null
        }

        return bestDx to bestDy
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun medianFloat(values: List<Int>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2].toFloat()
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    private fun rotateVector(dx: Float, dy: Float, rotationDegrees: Int): Pair<Float, Float> {
        return when (((rotationDegrees % 360) + 360) % 360) {
            90 -> -dy to dx
            180 -> -dx to -dy
            270 -> dy to -dx
            else -> dx to dy
        }
    }

    private fun elapsedMs(startedNs: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L
}
