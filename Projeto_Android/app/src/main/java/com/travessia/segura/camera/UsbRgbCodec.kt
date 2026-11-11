package com.travessia.segura.camera

object UsbRgbCodec {
    fun toArgb(rgb: ByteArray, width: Int, height: Int): IntArray {
        require(width in 1..4096 && height in 1..2160 && rgb.size == width * height * 3)
        return IntArray(width * height) { i ->
            0xff000000.toInt() or ((rgb[i*3].toInt() and 255) shl 16) or
                ((rgb[i*3+1].toInt() and 255) shl 8) or (rgb[i*3+2].toInt() and 255)
        }
    }
}
