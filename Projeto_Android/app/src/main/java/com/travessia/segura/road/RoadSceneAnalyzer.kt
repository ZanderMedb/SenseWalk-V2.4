package com.travessia.segura.road

data class RoadSegmentationResult(
    val roadRatio: Float, val sidewalkRatio: Float,
    val roadDetected: Boolean, val sidewalkDetected: Boolean,
    val possibleRoadBoundary: Boolean, val confidence: Float? = null,
    val timestamp: Long, val boundaryRatio: Float
)

/** Rótulos Cityscapes trainId, não os labelIds originais. Sem estimativa de distância. */
object RoadSceneAnalyzer {
    val classes = listOf("road", "sidewalk", "building", "wall", "fence", "pole", "traffic light", "traffic sign", "vegetation", "terrain", "sky", "person", "rider", "car", "truck", "bus", "train", "motorcycle", "bicycle")

    fun analyze(labels: ByteArray, width: Int, height: Int, timestamp: Long): RoadSegmentationResult {
        require(labels.size == width * height && width >= 4 && height >= 4)
        var road = 0; var sidewalk = 0; var boundary = 0; var samples = 0
        // Região central/inferior; contato entre classes é apenas um indício visual.
        for (y in height / 2 until height - 1) for (x in width / 6 until width * 5 / 6 - 1) {
            val index = y * width + x
            val label = labels[index].toInt()
            samples++
            if (label == 0) road++
            if (label == 1) sidewalk++
            val right = labels[index + 1].toInt()
            val below = labels[index + width].toInt()
            if ((label == 0 && (right == 1 || below == 1)) || (label == 1 && (right == 0 || below == 0))) boundary++
        }
        val r = road.toFloat() / samples; val s = sidewalk.toFloat() / samples
        val b = boundary.toFloat() / samples
        return RoadSegmentationResult(r, s, r >= .12f, s >= .05f, r >= .12f && s >= .05f && b >= .002f,
            timestamp = timestamp, boundaryRatio = b)
    }
}
