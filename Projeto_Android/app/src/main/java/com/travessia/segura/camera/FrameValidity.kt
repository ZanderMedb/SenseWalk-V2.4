package com.travessia.segura.camera

/** Relógio monotônico único. Invalidação não espera a inferência nativa em andamento. */
class FrameValidity {
    @Volatile var generation: Long = 0; private set
    @Volatile private var validFromMs = 0L
    @Synchronized fun invalidate(nowMs: Long) { validFromMs = nowMs; generation++ }
    fun accepts(token: Long, capturedAtMs: Long, nowMs: Long): Boolean =
        token == generation && capturedAtMs >= validFromMs && nowMs - capturedAtMs in 0..1500
}
