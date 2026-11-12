package com.travessia.segura.road

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/** Modelo oficial w8a8 v0.66.0, NHWC. RGB [0,1]; normalização ImageNet embutida. */
class RoadSegmentationManager(context: Context) : AutoCloseable {
    private val model = context.assets.openFd("ffnet_122ns_lowres.tflite").use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
    }
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    private val resized = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(resized)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(1024 * 512)
    private val input = ByteBuffer.allocateDirect(1024 * 512 * 3).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(128 * 256 * 19).order(ByteOrder.nativeOrder())
    private val scale: Float
    private val zeroPoint: Int
    private val quantizedChannels: ByteArray
    var lastInferenceMs = 0L; private set
    init {
        val i = interpreter.getInputTensor(0); val o = interpreter.getOutputTensor(0)
        require(i.shape().contentEquals(intArrayOf(1,512,1024,3)) && i.dataType() == DataType.UINT8)
        require(o.shape().contentEquals(intArrayOf(1,128,256,19)) && o.dataType() == DataType.UINT8)
        scale = i.quantizationParams().scale; zeroPoint = i.quantizationParams().zeroPoint
        require(scale > 0 && o.quantizationParams().scale > 0)
        quantizedChannels = RoadTensorCodec.quantizationTable(scale,zeroPoint)
    }
    @Synchronized fun segment(bitmap: Bitmap): ByteArray {
        // Resize-pad do app oficial. A máscara é desacolchoada abaixo antes de analisar ROI/desenhar.
        val factor = minOf(1024f / bitmap.width, 512f / bitmap.height)
        val w = (bitmap.width * factor).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * factor).roundToInt().coerceAtLeast(1)
        val left = (1024 - w) / 2; val top = (512 - h) / 2
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(bitmap, null, Rect(left, top, left + w, top + h), paint)
        resized.getPixels(pixels, 0, 1024, 0, 0, 1024, 512)
        input.rewind()
        for (pixel in pixels) for (channel in 0..2) {
            val shift = 16 - channel * 8
            input.put(quantizedChannels[pixel ushr shift and 255])
        }
        input.rewind(); output.rewind()
        val inferenceStarted = android.os.SystemClock.elapsedRealtime()
        interpreter.run(input, output)
        lastInferenceMs = android.os.SystemClock.elapsedRealtime() - inferenceStarted
        val labels = ByteArray(128 * 256)
        // Interpolar logits (não IDs), remover padding e só então argmax. Sem softmax/confiança.
        for (index in labels.indices) {
            val x = (left + (index % 256 + .5f) / 256 * w) / 4 - .5f
            val y = (top + (index / 256 + .5f) / 128 * h) / 4 - .5f
            labels[index] = RoadTensorCodec.argmax(output, x, y).toByte()
        }
        return labels
    }
    @Synchronized override fun close() { interpreter.close(); resized.recycle() }
}
