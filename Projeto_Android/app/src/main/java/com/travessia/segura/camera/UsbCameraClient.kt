package com.travessia.segura.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.UVCCamera
import com.serenegiant.utils.UVCUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow

/** USB Host/UVC real. A permissão pertence ao Android; não reutiliza autorização de outro app. */
class UsbCameraClient(private val context: Context, private val onFrame: (VideoFrame) -> Unit,
    private val onStatus: (String, Boolean) -> Unit) : AutoCloseable {
    private data class RgbFrame(val rgb: ByteArray, val width: Int, val height: Int, val timestamp: Long)
    private var helper: CameraHelper? = null
    private var scope: CoroutineScope? = null
    private var frames: Channel<RgbFrame>? = null
    @Volatile private var running = false
    @Volatile private var width = 640
    @Volatile private var height = 480
    private var selected: String? = null
    private var lastFrameNs = 0L
    private var loggedFirstFrame = false
    private var loggedSizeMismatch = false

    fun start() {
        if (running) return
        running = true
        UVCUtils.init(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        frames = Channel(1, BufferOverflow.DROP_OLDEST)
        val channel = frames!!
        scope?.launch {
            for (frame in channel) {
                if (!running || SystemClock.elapsedRealtimeNanos()-frame.timestamp > 500_000_000L) continue
                try {
                    val pixels = UsbRgbCodec.toArgb(frame.rgb,frame.width,frame.height)
                    val bitmap = Bitmap.createBitmap(pixels,frame.width,frame.height,Bitmap.Config.ARGB_8888)
                    if (running) onFrame(VideoFrame(bitmap,frame.timestamp,0)) else bitmap.recycle()
                } catch (error: Exception) { notify("USB: erro no frame de vídeo",true); Log.e("UsbCamera", "Frame",error) }
            }
        }
        try {
            val camera = CameraHelper()
            helper = camera
            camera.setStateCallback(object : ICameraHelper.StateCallback {
                override fun onAttach(device: UsbDevice) { select(device) }
                override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) { if (running) camera.openCamera() }
                override fun onCameraOpen(device: UsbDevice) {
                    if (!running) return
                    val sizes = camera.supportedSizeList.orEmpty()
                    Log.i("UsbCamera", "Supported modes: " + sizes.joinToString { "${it.width}x${it.height}/type=${it.type}" })
                    val index = UsbVideoQuality.preferredIndex(sizes.map {
                        UsbVideoQuality.Mode(it.width,it.height,it.type == UVCCamera.UVC_VS_FRAME_MJPEG)
                    })
                    val chosen = index?.let { sizes[it] }
                    if (chosen != null) camera.setPreviewSize(chosen)
                    val size = chosen ?: camera.previewSize
                    if (size == null || size.width !in 1..4096 || size.height !in 1..2160) {
                        notify("USB: resolução não suportada",true); return
                    }
                    width = size.width; height = size.height
                    camera.setFrameCallback({ buffer ->
                        val timestamp = SystemClock.elapsedRealtimeNanos()
                        val interval = if (width * height > 640*480) 166_000_000L else 100_000_000L
                        if (running && timestamp-lastFrameNs >= interval) {
                            val w = width; val h = height
                            val source = buffer.duplicate().apply { rewind() }
                            if (source.remaining() == w*h*3) {
                                val bytes = ByteArray(w*h*3); source.get(bytes)
                                lastFrameNs = timestamp
                                if (!loggedFirstFrame) {
                                    loggedFirstFrame = true
                                    Log.i("UsbCamera", "First RGB frame ${w}x${h}, ${bytes.size} bytes")
                                }
                                channel.trySend(RgbFrame(bytes,w,h,timestamp))
                            } else if (!loggedSizeMismatch) {
                                loggedSizeMismatch = true
                                notify("USB: formato de frame incompatível com a resolução negociada",true)
                                Log.e("UsbCamera", "RGB bytes=${source.remaining()} expected=${w*h*3}")
                            }
                        }
                    },UVCCamera.PIXEL_FORMAT_RGB)
                    camera.startPreview()
                    notify("USB: ${device.productName ?: "UVC"} — ${width}×${height}",false)
                    Log.i("UsbCamera", "Opened ${device.vendorId}:${device.productId} ${width}x${height}")
                }
                override fun onCameraClose(device: UsbDevice) { notify("USB: câmera fechada",true) }
                override fun onDeviceClose(device: UsbDevice) { selected = null }
                override fun onDetach(device: UsbDevice) {
                    if (selected == device.deviceName) { selected = null; notify("USB: webcam desconectada",true) }
                }
                override fun onCancel(device: UsbDevice) { selected = null; notify("USB: permissão não concedida",true) }
                override fun onError(device: UsbDevice, error: CameraException) {
                    selected = null; notify("USB: não foi possível abrir. Feche outros apps de câmera USB e reconecte.",true)
                    Log.e("UsbCamera", "Open failed",error)
                }
            })
            val manager = context.getSystemService(UsbManager::class.java)
            val device = manager.deviceList.values.firstOrNull(::isVideo)
            if (device != null) select(device) else notify("USB: conecte uma webcam UVC por adaptador OTG",false)
        } catch (error: Exception) { notify("USB: erro ao inicializar captura",true); Log.e("UsbCamera","Initialize",error) }
        catch (error: LinkageError) { notify("USB: biblioteca nativa indisponível",true); Log.e("UsbCamera","Native",error) }
    }
    private fun isVideo(device: UsbDevice): Boolean = (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 14 }
    private fun select(device: UsbDevice) {
        if (!running || selected != null || !isVideo(device)) return
        selected = device.deviceName
        notify("USB: autorize o SenseWalk a acessar a webcam",false)
        helper?.selectDevice(device)
    }
    private fun notify(message: String, error: Boolean) { if (running) onStatus(message,error) }
    override fun close() {
        running = false
        frames?.cancel(); frames = null
        scope?.cancel(); scope = null
        helper?.setStateCallback(null)
        helper?.release(); helper = null
        selected = null
    }
}
