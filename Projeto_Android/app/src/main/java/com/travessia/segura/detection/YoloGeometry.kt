package com.travessia.segura.detection

/** Contrato geométrico puro, compartilhado pelo resize e pelos testes. */
data class YoloGeometry(val width: Int, val height: Int, val target: Int) {
    init { require(width > 0 && height > 0 && target > 0) }
    val ratio = minOf(target.toFloat()/width,target.toFloat()/height)
    val resizedWidth = (width*ratio).toInt().coerceAtLeast(1)
    val resizedHeight = (height*ratio).toInt().coerceAtLeast(1)
    val padX = (target-resizedWidth)/2f
    val padY = (target-resizedHeight)/2f
    val canReuseOriginal = width == target && height == target
}
