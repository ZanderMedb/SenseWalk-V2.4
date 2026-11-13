package com.travessia.segura.orientation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Alimenta [OrientationCoverageTracker] com o rumo da câmera traseira.
 * Preferência: GAME_ROTATION_VECTOR (sem magnetômetro, estável perto de carros/postes);
 * alternativa: ROTATION_VECTOR. Sem nenhum dos dois, a orientação fica indisponível (inconclusivo).
 * Só para a câmera do celular: nunca usar para uma webcam USB independente.
 */
class OrientationSensorSource(context: Context, private val tracker: OrientationCoverageTracker) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val matrix = FloatArray(9)
    var running = false; private set
    val sensorName: String get() = sensor?.name ?: "none"

    fun start(): Boolean {
        if (running) return true
        val s = sensor
        if (manager == null || s == null) { tracker.available = false; Log.w(TAG, "orientation_unavailable"); return false }
        running = manager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        tracker.available = running
        Log.i(TAG, "orientation_start sensor=${s.name} type=${s.type} ok=$running")
        return running
    }

    fun stop() {
        if (running) manager?.unregisterListener(this)
        running = false
        tracker.available = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        // event.timestamp e o timestamp do CameraX usam elapsedRealtimeNanos.
        tracker.onSample(event.timestamp / 1_000_000L, OrientationMath.cameraHeadingDegrees(matrix))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) Log.w(TAG, "orientation_accuracy_unreliable")
    }

    companion object { private const val TAG = "SenseWalkOrientation" }
}
