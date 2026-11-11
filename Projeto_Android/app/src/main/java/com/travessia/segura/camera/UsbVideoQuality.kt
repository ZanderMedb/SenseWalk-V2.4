package com.travessia.segura.camera

/** Escolhe somente modos anunciados; não amplia artificialmente uma imagem pequena. */
object UsbVideoQuality {
    data class Mode(val width: Int, val height: Int, val mjpeg: Boolean)
    fun preferredIndex(modes: List<Mode>): Int? {
        val supported = modes.indices.filter { modes[it].width in 1..1280 && modes[it].height in 1..720 }
        return supported.maxWithOrNull(compareBy<Int> { modes[it].width * modes[it].height }
            .thenBy { if (modes[it].mjpeg) 1 else 0 })
    }
}
