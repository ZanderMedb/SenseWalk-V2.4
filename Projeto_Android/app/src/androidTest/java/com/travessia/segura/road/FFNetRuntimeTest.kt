package com.travessia.segura.road

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class FFNetRuntimeTest {
    @Test fun benchmarkOfficialSampleAndBothModels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sample = java.io.File(context.filesDir, "ffnet_validation.jpg")
        org.junit.Assume.assumeTrue("Baixe a imagem oficial de demonstração e copie para files/ffnet_validation.jpg", sample.exists())
        val bitmap = android.graphics.BitmapFactory.decodeFile(sample.path)
        val config = com.travessia.segura.config.AppConfig(context)
        val yolo = com.travessia.segura.detection.YoloDetector(context, classNames = config.classNames)
        yolo.inicializar(); assertTrue(yolo.isReady)
        val report = java.io.File(context.filesDir,"ffnet_validation.csv")
        report.bufferedWriter().use { out ->
            out.write("mode,iteration,total_ms,road_ratio,sidewalk_ratio,pss_kb,thread_cpu_ms,thermal\n")
            RoadSegmentationManager(context).use { ffnet ->
                for (mode in listOf("yolo", "ffnet", "combined")) repeat(6) { iteration ->
                    val start = android.os.SystemClock.elapsedRealtime()
                    val cpu = android.os.Debug.threadCpuTimeNanos()
                    var scene: RoadSegmentationResult? = null
                    if (mode != "yolo") scene = RoadSceneAnalyzer.analyze(ffnet.segment(bitmap),256,128,start)
                    if (mode != "ffnet") yolo.detectar(bitmap,.25f)
                    val cpuMs = (android.os.Debug.threadCpuTimeNanos()-cpu)/1_000_000
                    val thermal = context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus
                    out.write("$mode,$iteration,${android.os.SystemClock.elapsedRealtime()-start},${scene?.roadRatio},${scene?.sidewalkRatio},${android.os.Debug.getPss()},$cpuMs,$thermal\n")
                }
                var manual = false; var manualRuns = 0; var semanticRuns = 0
                val previous = config.automaticRoadDetection
                config.automaticRoadDetection = true
                try {
                    InferenceCoordinator(context,config,{manual},{},{semanticRuns++}).use { coordinator ->
                        coordinator.start()
                        coordinator.process(bitmap,0,android.os.SystemClock.elapsedRealtime()) { manualRuns++; yolo.detectar(bitmap,.25f) }
                        assertEquals(0,manualRuns) // amostra tem pista mas quase nenhuma calçada na ROI
                        manual = true
                        coordinator.suspendMonitoring() // análise manual independe do FFNet/automático
                        coordinator.process(bitmap,0,android.os.SystemClock.elapsedRealtime()) { manualRuns++; yolo.detectar(bitmap,.25f) }
                        assertEquals(1,manualRuns)
                        assertTrue(semanticRuns > 0)
                        coordinator.suspendMonitoring(); manual = false
                        coordinator.process(bitmap,0,android.os.SystemClock.elapsedRealtime()) { fail("Processou após parar") }
                    }
                } finally { config.automaticRoadDetection = previous }
            }
            val cameras = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
            println("OFFLINE_RECOGNITION_AVAILABLE=${android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(context)}")
            for (id in cameras.cameraIdList) {
                val characteristics = cameras.getCameraCharacteristics(id)
                println("CAMERA id=$id lensFacing=${characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)} timestampSource=${characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)}")
            }
        }
        yolo.fechar(); bitmap.recycle()
        assertTrue(report.length() > 100)
    }
    @Test fun inspectAndRunRealModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = context.assets.open("ffnet_122ns_lowres.tflite").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        Interpreter(model, Interpreter.Options().setNumThreads(2)).use { interpreter ->
            val input = interpreter.getInputTensor(0)
            val output = interpreter.getOutputTensor(0)
            println("FFNET input=${input.shape().toList()} ${input.dataType()} ${input.quantizationParams().scale},${input.quantizationParams().zeroPoint}")
            println("FFNET output=${output.shape().toList()} ${output.dataType()} ${output.quantizationParams().scale},${output.quantizationParams().zeroPoint}")
            assertArrayEquals(intArrayOf(1,512,1024,3), input.shape())
            assertArrayEquals(intArrayOf(1,128,256,19), output.shape())
            assertEquals(DataType.UINT8, input.dataType())
            assertEquals(DataType.UINT8, output.dataType())
            val source = ByteBuffer.allocateDirect(input.numBytes()).order(ByteOrder.nativeOrder())
            val dest = ByteBuffer.allocateDirect(output.numBytes()).order(ByteOrder.nativeOrder())
            val started = android.os.SystemClock.elapsedRealtime()
            interpreter.run(source, dest)
            println("FFNET real CPU inference ms=${android.os.SystemClock.elapsedRealtime()-started}")
            assertTrue(dest.capacity() > 0)
        }
    }
}
