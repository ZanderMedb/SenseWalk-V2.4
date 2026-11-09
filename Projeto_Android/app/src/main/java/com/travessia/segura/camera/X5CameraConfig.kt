package com.travessia.segura.camera

import android.content.Context
import android.content.SharedPreferences

/** Settings isolated from AppConfig so the model/class configuration remains untouched. */
class X5CameraConfig(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("x5_camera_config", Context.MODE_PRIVATE)

    var ssid: String
        get() = prefs.getString("ssid", DEFAULT_SSID) ?: DEFAULT_SSID
        set(value) = prefs.edit().putString("ssid", value.trim()).apply()

    var controlHost: String
        get() = prefs.getString("control_host", DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString("control_host", value.trim()).apply()

    var controlPort: Int
        get() = prefs.getInt("control_port", DEFAULT_CONTROL_PORT).coerceIn(1, 65535)
        set(value) = prefs.edit().putInt("control_port", value.coerceIn(1, 65535)).apply()

    var fallbackRtspUrl: String
        get() = prefs.getString("rtsp_url", DEFAULT_RTSP_URL) ?: DEFAULT_RTSP_URL
        set(value) = prefs.edit().putString("rtsp_url", value.trim()).apply()

    var rotate180: Boolean
        get() = prefs.getBoolean("rotate_180", true)
        set(value) = prefs.edit().putBoolean("rotate_180", value).apply()

    var staleFrameMs: Long
        get() = prefs.getLong("stale_frame_ms", DEFAULT_STALE_FRAME_MS).coerceIn(1_000L, 10_000L)
        set(value) = prefs.edit().putLong("stale_frame_ms", value.coerceIn(1_000L, 10_000L)).apply()

    companion object {
        const val DEFAULT_SSID = "ZIOTA_775012581081"
        const val DEFAULT_HOST = "192.168.1.1"
        const val DEFAULT_CONTROL_PORT = 8266
        const val DEFAULT_RTSP_URL = "rtsp://192.168.1.1:7070/webcam/jpeg_dvp"
        const val DEFAULT_STALE_FRAME_MS = 3_000L
    }
}
