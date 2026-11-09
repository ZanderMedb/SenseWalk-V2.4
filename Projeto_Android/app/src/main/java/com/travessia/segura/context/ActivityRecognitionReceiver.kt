package com.travessia.segura.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object MonitoringActivityEvents {
    private val mutableWalking = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val walking = mutableWalking.asSharedFlow()

    fun emit(isWalking: Boolean) {
        mutableWalking.tryEmit(isWalking)
    }
}

class ActivityRecognitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val activity = result.mostProbableActivity
        val walking = activity.type == DetectedActivity.WALKING || activity.type == DetectedActivity.ON_FOOT
        // UNKNOWN/TILTING não substitui o último estado para evitar oscilações.
        if (walking || activity.type == DetectedActivity.STILL) MonitoringActivityEvents.emit(walking)
    }
}
