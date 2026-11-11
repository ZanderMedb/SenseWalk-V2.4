package com.travessia.segura.road

import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.roundToInt

object RoadTensorCodec {
    fun quantize(channel: Int, scale: Float, zeroPoint: Int): Int =
        (channel / 255f / scale).roundToInt().plus(zeroPoint).coerceIn(0,255)

    fun argmax(scores: ByteBuffer, x: Float, y: Float, width: Int = 256, height: Int = 128, classes: Int = 19): Int {
        val px = x.coerceIn(0f, width - 1f); val py = y.coerceIn(0f, height - 1f)
        val x0 = floor(px).toInt(); val y0 = floor(py).toInt()
        val x1 = (x0+1).coerceAtMost(width-1); val y1 = (y0+1).coerceAtMost(height-1)
        val dx = px-x0; val dy = py-y0
        var best = Float.NEGATIVE_INFINITY; var label = 0
        for (c in 0 until classes) {
            fun value(xx: Int, yy: Int) = (scores.get((yy*width+xx)*classes+c).toInt() and 255).toFloat()
            val v = (value(x0,y0)*(1-dx)+value(x1,y0)*dx)*(1-dy) +
                (value(x0,y1)*(1-dx)+value(x1,y1)*dx)*dy
            if (v > best) { best = v; label = c }
        }
        return label
    }
}
