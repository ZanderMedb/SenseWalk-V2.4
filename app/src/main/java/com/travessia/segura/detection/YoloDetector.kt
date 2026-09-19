package com.travessia.segura.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp

class YoloDetector(
    private val context: Context,
    private val modelPath: String = "model.tflite",
    private val classNames: List<String>,
    inputSize: Int = 640
) {
    companion object {
        private const val TAG = "YoloDetector"
        private const val NUM_FEATURES_BBOX = 4
    }

    @Volatile
    private var interpreter: Interpreter? = null

    // ★ MUTÁVEL — será ajustado se o modelo tiver diferente
    private var numClasses = classNames.size
    private var numFeatures = NUM_FEATURES_BBOX + numClasses

    private var inputSize: Int = 640

    private lateinit var inputBuffer: ByteBuffer
    private lateinit var outputBuffer: Array<Array<FloatArray>>

    private var outputTransposed = true
    private var outputNumPreds = 8400
    private var outputDim1 = 0
    private var outputDim2 = 0

    private var lastRatio = 1f
    private var lastPadW = 0f
    private var lastPadH = 0f

    private var formatDetected = false
    private var coordsInPixelSpace = true
    private var scoresNeedSigmoid = false

    private var inferenceCount = 0L

    private val inferenceLock = Object()

    var lastInferenceTimeMs = 0L
        private set

    @Volatile
    var isReady = false
        private set

    fun inicializar() {
        try {
            val model = carregarModelo()
            val options = Interpreter.Options().setNumThreads(4)

            // GPU delegate removido — causa crash em muitos dispositivos

            val interp = Interpreter(model, options)
            interpreter = interp

            val inputTensor = interp.getInputTensor(0)
            val inputShape = inputTensor.shape()
            Log.i(TAG, "Input shape: ${inputShape.toList()}, type: ${inputTensor.dataType()}")

            if (inputShape.size == 4) {
                val modelH = inputShape[1]
                val modelW = inputShape[2]
                if (modelH != modelW) {
                    Log.w(TAG, "⚠️ Modelo não é quadrado: ${modelH}x${modelW}, usando $modelH")
                }
                inputSize = modelH
                Log.i(TAG, "✅ inputSize lido do modelo: $inputSize")
            } else {
                Log.w(TAG, "⚠️ Input shape inesperado, usando inputSize=$inputSize")
            }

            val inputBytes = 1 * inputSize * inputSize * 3 * 4
            inputBuffer = ByteBuffer.allocateDirect(inputBytes).apply {
                order(ByteOrder.nativeOrder())
            }
            Log.i(TAG, "Input buffer: $inputBytes bytes (${inputSize}x${inputSize}x3xFLOAT32)")

            val outputTensor = interp.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            Log.i(TAG, "Output shape: ${outputShape.toList()}, type: ${outputTensor.dataType()}")

            if (outputShape.size != 3) {
                Log.e(TAG, "❌ Output deveria ter 3 dimensões, tem ${outputShape.size}")
                return
            }

            outputDim1 = outputShape[1]
            outputDim2 = outputShape[2]

            // Detectar se output é transposed [1, features, preds] ou standard [1, preds, features]
            // Primeiro tentamos com base no numFeatures esperado
            val expectedFeatures = numFeatures

            when {
                outputDim1 == expectedFeatures -> {
                    outputTransposed = true
                    outputNumPreds = outputDim2
                }
                outputDim2 == expectedFeatures -> {
                    outputTransposed = false
                    outputNumPreds = outputDim1
                }
                outputDim1 < outputDim2 -> {
                    outputTransposed = true
                    outputNumPreds = outputDim2
                    Log.w(TAG, "⚠️ dim1=$outputDim1 ≠ $expectedFeatures, assumindo TRANSPOSED")
                }
                else -> {
                    outputTransposed = false
                    outputNumPreds = outputDim1
                    Log.w(TAG, "⚠️ dim2=$outputDim2 ≠ $expectedFeatures, assumindo STANDARD")
                }
            }

            Log.i(TAG, "Output format: ${if (outputTransposed) "TRANSPOSED" else "STANDARD"}, " +
                    "$outputNumPreds predições")

            // ★★★ AUTO-DETECTAR número real de classes do modelo ★★★
            val modelFeatures = if (outputTransposed) outputDim1 else outputDim2
            val modelNumClasses = modelFeatures - NUM_FEATURES_BBOX

            if (modelNumClasses > 0 && modelNumClasses != numClasses) {
                Log.w(TAG, "⚠️ AJUSTE AUTOMÁTICO: modelo tem $modelNumClasses classes, " +
                        "classNames tem ${classNames.size}. Usando $modelNumClasses do modelo.")
                numClasses = modelNumClasses
                numFeatures = NUM_FEATURES_BBOX + numClasses
            }

            Log.i(TAG, "Classes efetivas: $numClasses (classNames disponíveis: ${classNames.size})")

            outputBuffer = Array(1) { Array(outputDim1) { FloatArray(outputDim2) } }

            isReady = true
            Log.i(TAG, "✅ Detector pronto! $numClasses classes, " +
                    "$outputNumPreds preds, input=${inputSize}x$inputSize")

            executarTesteDiagnostico()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao inicializar: ${e.message}", e)
            isReady = false
        }
    }

    private fun executarTesteDiagnostico() {
        val interp = interpreter ?: run {
            Log.w(TAG, "Diagnóstico cancelado: interpreter null")
            return
        }

        try {
            Log.i(TAG, "🔬 Executando teste diagnóstico...")

            val testBitmap = Bitmap.createBitmap(
                inputSize, inputSize, Bitmap.Config.ARGB_8888
            )
            testBitmap.eraseColor(Color.rgb(114, 114, 114))
            preencherInput(testBitmap)
            testBitmap.recycle()

            synchronized(inferenceLock) {
                interp.run(inputBuffer, outputBuffer)
            }

            detectarFormato(outputBuffer[0])
            logDiagnostico(outputBuffer[0], "TESTE (img cinza)")

        } catch (e: Exception) {
            Log.e(TAG, "Erro no teste diagnóstico: ${e.message}", e)
        }
    }

    @Synchronized
    fun detectar(bitmap: Bitmap, confMinima: Float, rotationDegrees: Int = 0): List<RawDetection> {
        val interp = interpreter
        if (!isReady || interp == null) {
            Log.w(TAG, "detectar() chamado mas detector não está pronto")
            return emptyList()
        }

        inferenceCount++
        val startTime = System.currentTimeMillis()

        val rotated = if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val origW = rotated.width
        val origH = rotated.height

        val letterboxed = letterbox(rotated)

        preencherInput(letterboxed)

        if (letterboxed !== rotated) letterboxed.recycle()
        if (rotated !== bitmap) rotated.recycle()

        try {
            synchronized(inferenceLock) {
                interp.run(inputBuffer, outputBuffer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro na inferência: ${e.message}", e)
            return emptyList()
        }

        val output = outputBuffer[0]

        if (inferenceCount == 1L) {
            detectarFormato(output)
            logDiagnostico(output, "PRIMEIRO FRAME REAL")
        }

        if (inferenceCount % 60 == 0L) {
            logDiagnostico(output, "Frame #$inferenceCount")
        }

        val deteccoes = parsearOutput(output, confMinima, origW, origH)
        val resultado = nms(deteccoes, 0.45f)

        val elapsed = System.currentTimeMillis() - startTime
        lastInferenceTimeMs = elapsed

        if (inferenceCount <= 10 || inferenceCount % 30 == 0L || resultado.isNotEmpty()) {
            Log.d(TAG, "Frame #$inferenceCount: ${elapsed}ms, " +
                    "préNMS=${deteccoes.size}, pósNMS=${resultado.size}, " +
                    "confMin=$confMinima, " +
                    "rotação=${rotationDegrees}°, " +
                    "bitmap=${origW}x${origH}, " +
                    "letterbox(ratio=${"%.3f".format(lastRatio)}, " +
                    "pad=${"%.1f".format(lastPadW)},${"%.1f".format(lastPadH)})")

            for (d in resultado) {
                Log.d(TAG, "  ✓ ${d.className} ${"%.1f".format(d.confidence * 100)}% " +
                        "[${d.x1.toInt()},${d.y1.toInt()},${d.x2.toInt()},${d.y2.toInt()}]")
            }
        }

        return resultado
    }

    // ══════════════════════════════════════════
    //  AUTO-DETECÇÃO DE FORMATO
    // ══════════════════════════════════════════

    private fun detectarFormato(output: Array<FloatArray>) {
        var maxBbox = 0f
        var minBbox = Float.MAX_VALUE
        var maxScore = -Float.MAX_VALUE
        var minScore = Float.MAX_VALUE
        val sampleCount = minOf(500, outputNumPreds)

        for (i in 0 until sampleCount) {
            for (f in 0 until NUM_FEATURES_BBOX) {
                val v = abs(getVal(output, f, i))
                if (v > maxBbox) maxBbox = v
                if (v < minBbox && v > 0.0001f) minBbox = v
            }
            for (c in 0 until numClasses) {
                val v = getVal(output, NUM_FEATURES_BBOX + c, i)
                if (v > maxScore) maxScore = v
                if (v < minScore) minScore = v
            }
        }

        coordsInPixelSpace = maxBbox > 2.0f
        scoresNeedSigmoid = minScore < -0.5f || maxScore > 1.5f
        formatDetected = true

        Log.i(TAG, "🔍 FORMAT DETECT: " +
                "bbox range=[${"%.3f".format(minBbox)}, ${"%.3f".format(maxBbox)}] " +
                "→ ${if (coordsInPixelSpace) "PIXEL_SPACE" else "NORMALIZED"}, " +
                "score range=[${"%.3f".format(minScore)}, ${"%.3f".format(maxScore)}] " +
                "→ sigmoid=${if (scoresNeedSigmoid) "YES" else "NO"}")
    }

    private fun logDiagnostico(output: Array<FloatArray>, label: String) {
        Log.d(TAG, "═══ DIAGNÓSTICO: $label ═══")
        Log.d(TAG, "  dims=[${outputDim1}, ${outputDim2}] " +
                "transposed=$outputTransposed preds=$outputNumPreds " +
                "numClasses=$numClasses inputSize=$inputSize")
        Log.d(TAG, "  coords=${if (coordsInPixelSpace) "PIXEL" else "NORM"} " +
                "sigmoid=${if (scoresNeedSigmoid) "YES" else "NO"}")

        data class Sample(
            val idx: Int, val cx: Float, val cy: Float,
            val w: Float, val h: Float,
            val bestClass: Int, val rawScore: Float, val finalScore: Float
        )

        val samples = mutableListOf<Sample>()
        val scanCount = minOf(outputNumPreds, 8400)

        for (i in 0 until scanCount) {
            var bestC = 0
            var bestRaw = -999f
            for (c in 0 until numClasses) {
                val s = getVal(output, NUM_FEATURES_BBOX + c, i)
                if (s > bestRaw) { bestRaw = s; bestC = c }
            }
            val finalScore = if (scoresNeedSigmoid) sigmoid(bestRaw) else bestRaw
            samples.add(Sample(
                i,
                getVal(output, 0, i),
                getVal(output, 1, i),
                getVal(output, 2, i),
                getVal(output, 3, i),
                bestC, bestRaw, finalScore
            ))
        }

        samples.sortByDescending { it.finalScore }

        Log.d(TAG, "  Top 8 predições (de $scanCount):")
        for (s in samples.take(8)) {
            val className = if (s.bestClass < classNames.size)
                classNames[s.bestClass] else "cls_${s.bestClass}"
            Log.d(TAG, "    [${s.idx}] cx=${"%.2f".format(s.cx)} " +
                    "cy=${"%.2f".format(s.cy)} " +
                    "w=${"%.2f".format(s.w)} h=${"%.2f".format(s.h)} " +
                    "→ $className raw=${"%.4f".format(s.rawScore)} " +
                    "final=${"%.4f".format(s.finalScore)}")
        }

        val thresholds = floatArrayOf(0.01f, 0.05f, 0.10f, 0.15f, 0.25f, 0.40f, 0.50f)
        val counts = IntArray(thresholds.size)
        for (s in samples) {
            for (t in thresholds.indices) {
                if (s.finalScore >= thresholds[t]) counts[t]++
            }
        }
        val dist = thresholds.indices.joinToString(", ") {
            ">${"%.0f".format(thresholds[it] * 100)}%=${counts[it]}"
        }
        Log.d(TAG, "  Score distribution: $dist")
        Log.d(TAG, "═══ FIM DIAGNÓSTICO ═══")
    }

    // ══════════════════════════════════════════
    //  LETTERBOX
    // ══════════════════════════════════════════

    private fun letterbox(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        val ratio = minOf(inputSize.toFloat() / w, inputSize.toFloat() / h)
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()
        val padW = (inputSize - newW) / 2f
        val padH = (inputSize - newH) / 2f

        lastRatio = ratio
        lastPadW = padW
        lastPadH = padH

        if (newW == inputSize && newH == inputSize) return bitmap

        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

        val padded = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(resized, padW, padH, null)

        if (resized !== bitmap) resized.recycle()
        return padded
    }

    // ══════════════════════════════════════════
    //  INPUT
    // ══════════════════════════════════════════

    private fun preencherInput(bitmap: Bitmap) {
        inputBuffer.rewind()
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }
    }

    // ══════════════════════════════════════════
    //  OUTPUT PARSING
    // ══════════════════════════════════════════

    private fun parsearOutput(
        output: Array<FloatArray>,
        confMinima: Float,
        origW: Int,
        origH: Int
    ): List<RawDetection> {

        val deteccoes = mutableListOf<RawDetection>()

        for (i in 0 until outputNumPreds) {

            var maxConf = -Float.MAX_VALUE
            var maxIdx = 0
            for (c in 0 until numClasses) {
                val score = getVal(output, NUM_FEATURES_BBOX + c, i)
                if (score > maxConf) {
                    maxConf = score
                    maxIdx = c
                }
            }

            val finalConf = if (scoresNeedSigmoid) sigmoid(maxConf) else maxConf

            if (finalConf < confMinima) continue

            var cx = getVal(output, 0, i)
            var cy = getVal(output, 1, i)
            var bw = getVal(output, 2, i)
            var bh = getVal(output, 3, i)

            if (!coordsInPixelSpace) {
                cx *= inputSize
                cy *= inputSize
                bw *= inputSize
                bh *= inputSize
            }

            val lbX1 = cx - bw / 2f
            val lbY1 = cy - bh / 2f
            val lbX2 = cx + bw / 2f
            val lbY2 = cy + bh / 2f

            val x1 = ((lbX1 - lastPadW) / lastRatio).coerceIn(0f, origW.toFloat())
            val y1 = ((lbY1 - lastPadH) / lastRatio).coerceIn(0f, origH.toFloat())
            val x2 = ((lbX2 - lastPadW) / lastRatio).coerceIn(0f, origW.toFloat())
            val y2 = ((lbY2 - lastPadH) / lastRatio).coerceIn(0f, origH.toFloat())

            if (x2 - x1 < 1f || y2 - y1 < 1f) continue

            // ★ Usa classNames se disponível, senão gera nome genérico
            val className = if (maxIdx < classNames.size) classNames[maxIdx]
            else "class_$maxIdx"

            deteccoes.add(
                RawDetection(
                    x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                    classId = maxIdx,
                    className = className,
                    confidence = finalConf
                )
            )
        }

        return deteccoes
    }

    private fun getVal(output: Array<FloatArray>, feature: Int, prediction: Int): Float {
        return if (outputTransposed) {
            output[feature][prediction]
        } else {
            output[prediction][feature]
        }
    }

    private fun sigmoid(x: Float): Float {
        return (1.0 / (1.0 + exp((-x).toDouble()))).toFloat()
    }

    // ══════════════════════════════════════════
    //  NMS
    // ══════════════════════════════════════════

    private fun nms(detections: List<RawDetection>, iouThreshold: Float): List<RawDetection> {
        val result = mutableListOf<RawDetection>()
        val grouped = detections.groupBy { it.classId }

        for ((_, dets) in grouped) {
            val sorted = dets.sortedByDescending { it.confidence }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                result.add(best)
                sorted.removeAll { computeIoU(best, it) > iouThreshold }
            }
        }
        return result
    }

    private fun computeIoU(a: RawDetection, b: RawDetection): Float {
        val ix1 = maxOf(a.x1, b.x1)
        val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2)
        val iy2 = minOf(a.y2, b.y2)
        val inter = (ix2 - ix1).coerceAtLeast(0f) * (iy2 - iy1).coerceAtLeast(0f)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }

    // ══════════════════════════════════════════
    //  UTILS
    // ══════════════════════════════════════════

    private fun carregarModelo(): MappedByteBuffer {
        val fd = context.assets.openFd(modelPath)
        val stream = FileInputStream(fd.fileDescriptor)
        val channel = stream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    @Synchronized
    fun fechar() {
        isReady = false
        synchronized(inferenceLock) {
            interpreter?.close()
            interpreter = null
        }
        Log.i(TAG, "Detector fechado")
    }
}