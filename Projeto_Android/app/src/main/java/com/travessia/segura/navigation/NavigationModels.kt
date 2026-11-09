package com.travessia.segura.navigation

import kotlin.math.*

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

enum class CrossingType {
    PEDESTRIAN_CROSSING,
    TRAFFIC_SIGNAL,
    SIGNALIZED_CROSSING
}

data class CrossingPoint(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val type: CrossingType,
    val distanceMeters: Double
) {
    val point: GeoPoint get() = GeoPoint(latitude, longitude)
}

data class LocationSnapshot(
    val point: GeoPoint,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float = 0f,
    val bearingDegrees: Float? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class DestinationPlace(
    val displayName: String,
    val point: GeoPoint
)

data class RouteInstruction(
    val text: String,
    val point: GeoPoint,
    val distanceMeters: Double,
    val sign: Int
)

data class PedestrianRoute(
    val destination: DestinationPlace,
    val distanceMeters: Double,
    val timeMillis: Long,
    val geometry: List<GeoPoint>,
    val instructions: List<RouteInstruction>
)

/** Funções puras para manter cálculos verificáveis também nos testes JVM. */
object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val latDelta = Math.toRadians(b.latitude - a.latitude)
        val lonDelta = Math.toRadians(b.longitude - a.longitude)
        val latA = Math.toRadians(a.latitude)
        val latB = Math.toRadians(b.latitude)
        val h = sin(latDelta / 2).pow(2) + cos(latA) * cos(latB) * sin(lonDelta / 2).pow(2)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Rumo de a para b, em graus [0, 360). */
    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val latA = Math.toRadians(a.latitude)
        val latB = Math.toRadians(b.latitude)
        val deltaLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(deltaLon) * cos(latB)
        val x = cos(latA) * sin(latB) - sin(latA) * cos(latB) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angularDifferenceDegrees(first: Double, second: Double): Double {
        val raw = abs(first - second) % 360.0
        return if (raw > 180.0) 360.0 - raw else raw
    }

    /** Aproximação conservadora: menor distância aos vértices da geometria da rota. */
    fun distanceToRouteMeters(point: GeoPoint, route: List<GeoPoint>): Double {
        if (route.isEmpty()) return Double.POSITIVE_INFINITY
        return route.minOf { distanceMeters(point, it) }
    }
}
