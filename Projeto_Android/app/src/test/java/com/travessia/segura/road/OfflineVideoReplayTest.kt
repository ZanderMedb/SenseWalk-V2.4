package com.travessia.segura.road

import android.graphics.BitmapFactory
import com.travessia.segura.detection.*
import com.travessia.segura.tracking.Rastreador
import com.travessia.segura.vision.SparseOpticalFlowEstimator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real app classes over host model outputs, not a device/camera/TFLite integration test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineVideoReplayTest {
    private val names = listOf("car", "motorcycle", "bicycle", "truck", "bus", "traffic-light-green",
        "traffic-light-red", "traffic-light-yellow", "crosswalk", "pedestrian-light-green", "pedestrian-light-red")

    @Test fun replayRealVideosAndCheckSharedTensorParity() {
        val location = System.getenv("SENSEWALK_VIDEO_RESULTS")
        assumeTrue("Optional real-video replay: set SENSEWALK_VIDEO_RESULTS", location != null)
        val root = File(location!!)
        val inputs = root.listFiles()!!.filter { File(it, "replay.json").isFile }.sortedBy { it.name }
        assertTrue("No replay artifacts", inputs.isNotEmpty())
        var processed = 0
        File(root, "tracking.csv").printWriter().use { tracksOut ->
            File(root, "cena_kotlin.csv").printWriter().use { sceneOut ->
                tracksOut.println("video,frame,timestamp_ms,id,ghost,class_name,x1,y1,x2,y2,classification,speed_px_s,growth_s")
                sceneOut.println("video,frame,timestamp_ms,camera_dx,camera_dy,camera_confidence,crosswalk,signal_heuristic,signal_applicability,road_ratio,sidewalk_ratio,boundary_ratio,unobserved_tracks,new_tracks,removed_tracks,danger,reasons,stage,rotation_2d_deg,scale,background_residual,flow_host_ms")
                for (folder in inputs) {
                    val input = JSONObject(File(folder, "replay.json").readText())
                    val w = input.getInt("width"); val h = input.getInt("height")
                    val frames = input.getJSONArray("frames")
                    val tracker = AdvancedVehicleTracker()
                    val motion = Rastreador { Rastreador.Settings() }
                    val flow = SparseOpticalFlowEstimator()
                    val evidence = SceneEvidenceTracker()
                    val crossing = CrossingVerification().apply { start(0) }
                    var previous = emptyList<FloatArray>()
                    for (i in 0 until frames.length()) {
                        val row = frames.getJSONObject(i)
                        val timestamp = row.getLong("timestamp_ms")
                        val bitmap = BitmapFactory.decodeFile(File(root, row.getString("image")).path)
                        assertNotNull(bitmap)
                        val camera = flow.estimate(bitmap, 0, previous)
                        bitmap.recycle()
                        val reliable = camera.confidence >= .35f
                        motion.novoFrameCamera(camera.dx, camera.dy, camera.confidence, .35f)
                        val raw = row.getJSONArray("detections")
                        val detections = (0 until raw.length()).map { j ->
                            val d = raw.getJSONArray(j); val id = d.getInt(0)
                            RawDetection(d.getDouble(2).toFloat(), d.getDouble(3).toFloat(),
                                d.getDouble(4).toFloat(), d.getDouble(5).toFloat(), id, names[id], d.getDouble(1).toFloat())
                        }
                        val labels = File(root, row.getString("mask")).readBytes()
                        val road = RoadSceneAnalyzer.analyze(labels, 256, 128, timestamp)
                        val expected = row.getJSONArray("road")
                        assertEquals(expected.getDouble(0), road.roadRatio.toDouble(), 1e-6)
                        assertEquals(expected.getDouble(1), road.sidewalkRatio.toDouble(), 1e-6)
                        assertEquals(expected.getDouble(2), road.boundaryRatio.toDouble(), 1e-6)
                        assertEquals(expected.getBoolean(3), road.possibleRoadBoundary)
                        if (i == 0) parity(folder, detections, labels, w, h)
                        val vehicles = detections.filter { it.classId in 0..4 }
                        previous = vehicles.map { floatArrayOf(it.x1,it.y1,it.x2,it.y2) }
                        val tracked = tracker.update(vehicles, w, h, if (reliable) camera.dx else 0f,
                            if (reliable) camera.dy else 0f, timestamp)
                        for (d in tracked.filterNot { it.isGhost }) motion.atualizar(d.trackId,d.cx,d.cy,timestamp/1000.0,d.bboxW.toInt())
                        motion.limpar(timestamp/1000.0)
                        val infos = tracked.filterNot { it.isGhost }.map { d ->
                            val c = motion.classificar(d.trackId,w,h)
                            VeiculoInfo(d.className,d.confidence,d.cx,d.cy,c.first,c.second,
                                floatArrayOf(d.x1,d.y1,d.x2,d.y2),d.bboxW,d.trackId,motion.trajectory(d.trackId,w,h))
                        }
                        for (d in tracked) {
                            val c = if (d.isGhost) "UNOBSERVED" to 0f else motion.classificar(d.trackId,w,h)
                            tracksOut.println(listOf(folder.name,row.getInt("frame"),timestamp,d.trackId,d.isGhost,
                                d.className,d.x1,d.y1,d.x2,d.y2,c.first,c.second,motion.taxaCrescimentoBBox(d.trackId)).joinToString(","))
                        }
                        val scene = evidence.update(detections.filter { it.classId > 4 },timestamp,w)
                        val risk = CrossingRiskAnalyzer.analyze(infos,w,h,scene.crosswalkBox,0,false,reliable,tracker.lastStats.unobservedTracks)
                        crossing.observe(risk,scene,road,timestamp,camera)
                        // A button may start a window in replay; it never supplies physical coverage.
                        if (crossing.stage in setOf(CrossingVerification.Stage.AWAIT_RIGHT, CrossingVerification.Stage.AWAIT_LEFT,
                                CrossingVerification.Stage.AWAIT_FRONT)) crossing.confirmCameraDirection(timestamp)
                        assertNotEquals(CrossingVerification.Stage.FAVORABLE_OBSERVATION,crossing.stage)
                        sceneOut.println(listOf(folder.name,row.getInt("frame"),timestamp,camera.dx,camera.dy,camera.confidence,
                            scene.crosswalk,scene.signal,"UNCERTAIN",road.roadRatio,road.sidewalkRatio,road.boundaryRatio,
                            tracker.lastStats.unobservedTracks,tracker.lastStats.newTracks,tracker.lastStats.removedTracks,
                            risk.danger,risk.reasons.joinToString("|"),crossing.stage,camera.rotationDegrees,camera.scale,
                            camera.backgroundResidual,camera.processingMs).joinToString(","))
                        processed++
                    }
                }
            }
        }
        File(root,"replay_kotlin.json").writeText(JSONObject(mapOf("frames" to processed,
            "videos" to inputs.size,"runtime" to "Robolectric native graphics; host tensors; no Android TFLite invocation",
            "parity" to "YOLO decode/NMS first frame each video; FFNet first-frame score interpolation; ROI all frames")).toString(2))
    }

    @Suppress("UNCHECKED_CAST")
    private fun parity(folder: File, expected: List<RawDetection>, labels: ByteArray, w: Int, h: Int) {
        val geometry = JSONObject(File(folder,"geometry.json").readText())
        val detector = YoloDetector(RuntimeEnvironment.getApplication(), classNames=names)
        // Execute the production Android/Skia letterbox on the shared lossless frame.
        val source = BitmapFactory.decodeFile(File(folder,"000000.png").path)
        val letterbox = YoloDetector::class.java.getDeclaredMethod("letterbox",android.graphics.Bitmap::class.java).apply { isAccessible=true }
        val inputImage = letterbox.invoke(detector,source) as android.graphics.Bitmap
        val pixels = IntArray(640*640)
        inputImage.getPixels(pixels,0,640,0,0,640,640)
        val rgb = ByteArray(pixels.size*3)
        for (i in pixels.indices) for (c in 0..2) rgb[i*3+c] = (pixels[i] ushr (16-c*8)).toByte()
        File(folder,"yolo_input_android.rgb").writeBytes(rgb)
        val pythonRgb = File(folder,"yolo_input_python.rgb").readBytes()
        assertEquals(rgb.size,pythonRgb.size)
        val mae = rgb.indices.sumOf { kotlin.math.abs((rgb[it].toInt() and 255)-(pythonRgb[it].toInt() and 255)).toLong() }.toDouble()/rgb.size
        // Skia/OpenCV rounding is not bit-exact; a one-pixel padding shift fails this bound.
        assertTrue("YOLO preprocessing MAE=$mae (8-bit channels)",mae < 1.0)
        if (inputImage !== source) inputImage.recycle()
        source.recycle()
        fun field(name: String, value: Any) = YoloDetector::class.java.getDeclaredField(name).apply { isAccessible=true }.set(detector,value)
        field("coordsInPixelSpace",false)
        val yg = geometry.getJSONArray("yolo")
        field("lastRatio",yg.getDouble(0).toFloat()); field("lastPadW",yg.getDouble(1).toFloat()); field("lastPadH",yg.getDouble(2).toFloat())
        val bytes = ByteBuffer.wrap(File(folder,"yolo_output.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val output = Array(15) { FloatArray(8400) { bytes.float } }
        val parse = YoloDetector::class.java.getDeclaredMethod("parsearOutput",Array<FloatArray>::class.java,
            Float::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply { isAccessible=true }
        val raw = parse.invoke(detector,output,.20f,w,h) as List<RawDetection>
        val nms = YoloDetector::class.java.getDeclaredMethod("nms",List::class.java,Float::class.javaPrimitiveType).apply { isAccessible=true }
        val actual = nms.invoke(detector,raw,.45f) as List<RawDetection>
        assertEquals(expected.size,actual.size)
        for ((a,b) in actual.zip(expected)) {
            assertEquals(a.classId,b.classId); assertEquals(a.confidence,b.confidence,1e-6f)
            assertEquals(a.x1,b.x1,.002f); assertEquals(a.y1,b.y1,.002f)
            assertEquals(a.x2,b.x2,.002f); assertEquals(a.y2,b.y2,.002f)
        }
        val fg = geometry.getJSONArray("ffnet")
        val scores = ByteBuffer.wrap(File(folder,"ffnet_output.u8").readBytes())
        for (index in labels.indices) {
            val x = (fg.getInt(0)+(index%256+.5f)/256*fg.getInt(2))/4-.5f
            val y = (fg.getInt(1)+(index/256+.5f)/128*fg.getInt(3))/4-.5f
            assertEquals("FFNet pixel $index", labels[index].toInt(),RoadTensorCodec.argmax(scores,x,y))
        }
    }
}
