package com.travessia.segura.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.travessia.segura.config.AppConfig
import com.travessia.segura.navigation.GeoPoint
import com.travessia.segura.navigation.LocationSnapshot

/** Localização em primeiro plano via Fused Location Provider. */
class LocationService(
    context: Context,
    private val config: AppConfig
) {
    private val appContext = context.applicationContext
    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(appContext)
    private var callback: LocationCallback? = null

    fun hasLocationPermission(): Boolean = LocationPermissionPolicy.hasForegroundLocationPermission(
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    )

    @SuppressLint("MissingPermission")
    fun start(onLocation: (LocationSnapshot) -> Unit, onError: (String) -> Unit) {
        if (!hasLocationPermission()) {
            onError("Permissão de localização não concedida")
            return
        }
        stop()
        val intervalMs = config.locationUpdateIntervalSeconds * 1_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis((intervalMs / 2).coerceAtLeast(2_000L))
            .setWaitForAccurateLocation(false)
            .build()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                onLocation(
                    LocationSnapshot(
                        point = GeoPoint(location.latitude, location.longitude),
                        accuracyMeters = location.accuracy,
                        speedMetersPerSecond = if (location.hasSpeed()) location.speed else 0f,
                        bearingDegrees = if (location.hasBearing()) location.bearing else null,
                        timestampMs = location.time
                    )
                )
            }
        }
        client.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
            .addOnFailureListener { onError(it.message ?: "Falha ao iniciar localização") }
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }
}

/** Política pequena e testável: nenhum modo desta versão solicita localização em segundo plano. */
object LocationPermissionPolicy {
    fun hasForegroundLocationPermission(fineGranted: Boolean, coarseGranted: Boolean): Boolean =
        fineGranted || coarseGranted
}
