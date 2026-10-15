package com.travessia.segura.detection

data class RawDetection(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val classId: Int,
    val className: String,
    val confidence: Float
) {
    val cx get() = (x1 + x2) / 2f
    val cy get() = (y1 + y2) / 2f
    val area get() = (x2 - x1) * (y2 - y1)
    val bboxW get() = x2 - x1
    val bboxH get() = y2 - y1
}

data class TrackedDetection(
    val trackId: Int,
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val classId: Int,
    val className: String,
    val confidence: Float,
    /** true quando a caixa foi prevista por poucos frames para evitar o "pisca-pisca". */
    val isGhost: Boolean = false,
    /** quantidade de frames reais que confirmaram esse track. */
    val hits: Int = 1
) {
    val cx get() = (x1 + x2) / 2f
    val cy get() = (y1 + y2) / 2f
    val bboxW get() = x2 - x1
    val bboxH get() = y2 - y1
    val area get() = bboxW * bboxH
}

data class VeiculoInfo(
    val nome: String,
    val conf: Float,
    val cx: Float,
    val cy: Float,
    val classif: String,
    val vel: Float,
    val bbox: FloatArray,
    val bboxW: Float,
    val tid: Int
)

data class CinematicaResult(
    val vel: Float,
    val vx: Float,
    val vy: Float,
    val emMovimento: Boolean,
    val aproximando: Boolean
)
