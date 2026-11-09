package com.travessia.segura.camera

import android.graphics.Bitmap

/** A frame delivered by an external source with the metadata needed by the vision pipeline. */
data class VideoFrame(
    val bitmap: Bitmap,
    val timestampNanos: Long,
    val rotationDegrees: Int = 0
)
