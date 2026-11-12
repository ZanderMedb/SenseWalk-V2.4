package com.travessia.segura.tracking

import android.graphics.Bitmap
import com.travessia.segura.vision.SparseOpticalFlowEstimator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** Frames de textura sintética com translação conhecida; boxes não vêm do YOLO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OpticalFlowAuditTest {
    private val width = 160
    private val height = 120
    private val texture = Random(42).let { random -> IntArray(width*height) {
        val c = random.nextInt(256); (0xff shl 24) or (c shl 16) or (c shl 8) or c
    } }
    private fun frame(shift: Int): Bitmap {
        val pixels = IntArray(width*height) { i -> texture[(i/width)*width + (i%width-shift+width)%width] }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
    }
    @Test fun parkedExpansionWithMeasuredBackgroundTranslationIsUncertainNotApproaching() {
        val flow = SparseOpticalFlowEstimator()
        val tracker = Rastreador { Rastreador.Settings() }
        for (i in 0..12) {
            val image = frame(i)
            val motion = flow.estimate(image)
            image.recycle()
            if (i > 0) { assertEquals(1f,motion.dx,.01f); assertTrue(motion.confidence >= .35f) }
            tracker.novoFrameCamera(motion.dx,motion.dy,motion.confidence,.35f)
            tracker.atualizar(1,40f+i,60f,1.0+i*.1,20+i*2)
            assertNotEquals("APROXIMANDO",tracker.classificar(1,width,height).first)
        }
        assertEquals("MOVIMENTO_INCERTO",tracker.classificar(1,width,height).first)
    }
    @Test fun independentVehicleStartingLateralMotionSurvivesBackgroundCompensation() {
        val flow = SparseOpticalFlowEstimator()
        val tracker = Rastreador { Rastreador.Settings() }
        for (i in 0..12) {
            val image = frame(i)
            val motion = flow.estimate(image)
            image.recycle()
            tracker.novoFrameCamera(motion.dx,motion.dy,motion.confidence,.35f)
            tracker.atualizar(1,20f+i+(i-6).coerceAtLeast(0)*4,60f,1.0+i*.1,20)
        }
        assertEquals("APROXIMANDO",tracker.classificar(1,width,height).first)
    }
    @Test fun texturelessFramesCannotCertifyCameraCompensation() {
        val flow = SparseOpticalFlowEstimator()
        val image = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        flow.estimate(image)
        assertEquals(0f,flow.estimate(image).confidence,0f)
        image.recycle()
    }
}
