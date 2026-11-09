package com.travessia.segura.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CrossingQueryResult(
    val crossings: List<CrossingPoint>,
    val fromCache: Boolean,
    val error: Throwable? = null
)

/**
 * Repositório OSM/Overpass. A instância pública é usada com baixa frequência e cache;
 * não é tratada como infraestrutura de produção. Não há coleta de imagens neste fluxo.
 */
class CrossingMapRepository(
    private val endpoint: String = "https://overpass-api.de/api/interpreter",
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val CACHE_VALIDITY_MS = 60_000L
        private const val MIN_REFRESH_DISTANCE_METERS = 45.0
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
    }

    private var cachedAtMs = 0L
    private var cachedAtPoint: GeoPoint? = null
    private var cachedCrossings: List<CrossingPoint> = emptyList()

    suspend fun crossingsNearby(origin: GeoPoint, radiusMeters: Int): CrossingQueryResult =
        withContext(Dispatchers.IO) {
            synchronized(this@CrossingMapRepository) {
                val age = clock() - cachedAtMs
                val originOfCache = cachedAtPoint
                if (originOfCache != null && age in 0 until CACHE_VALIDITY_MS &&
                    GeoMath.distanceMeters(origin, originOfCache) < MIN_REFRESH_DISTANCE_METERS
                ) {
                    return@withContext CrossingQueryResult(
                        withDistances(cachedCrossings, origin),
                        fromCache = true
                    )
                }
            }

            try {
                val body = fetch(buildQuery(origin, radiusMeters))
                val parsed = CrossingMapParser.parse(body, origin)
                synchronized(this@CrossingMapRepository) {
                    cachedAtMs = clock()
                    cachedAtPoint = origin
                    cachedCrossings = parsed
                }
                CrossingQueryResult(parsed, fromCache = false)
            } catch (error: Exception) {
                val fallback = synchronized(this@CrossingMapRepository) {
                    if (cachedCrossings.isNotEmpty()) withDistances(cachedCrossings, origin) else emptyList()
                }
                CrossingQueryResult(fallback, fromCache = fallback.isNotEmpty(), error = error)
            }
        }

    private fun buildQuery(origin: GeoPoint, radiusMeters: Int): String = """
        [out:json][timeout:25];
        (
          node["highway"="crossing"](around:$radiusMeters,${origin.latitude},${origin.longitude});
          way["highway"="crossing"](around:$radiusMeters,${origin.latitude},${origin.longitude});
          node["highway"="traffic_signals"](around:$radiusMeters,${origin.latitude},${origin.longitude});
          way["highway"="traffic_signals"](around:$radiusMeters,${origin.latitude},${origin.longitude});
          node["crossing"="traffic_signals"](around:$radiusMeters,${origin.latitude},${origin.longitude});
          way["crossing"="traffic_signals"](around:$radiusMeters,${origin.latitude},${origin.longitude});
          node["crossing"~"^(zebra|uncontrolled|marked)$"](around:$radiusMeters,${origin.latitude},${origin.longitude});
          way["crossing"~"^(zebra|uncontrolled|marked)$"](around:$radiusMeters,${origin.latitude},${origin.longitude});
        );
        out center tags;
    """.trimIndent()

    private fun fetch(query: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "SenseWalk-academic-demo/2.4 (accessible mobility research)")
        }
        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("data=" + java.net.URLEncoder.encode(query, "UTF-8"))
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Overpass HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun withDistances(points: List<CrossingPoint>, origin: GeoPoint): List<CrossingPoint> =
        points.map { it.copy(distanceMeters = GeoMath.distanceMeters(origin, it.point)) }
            .sortedBy { it.distanceMeters }
}

object CrossingMapParser {
    fun parse(response: String, origin: GeoPoint): List<CrossingPoint> {
        val elements = JSONObject(response).optJSONArray("elements") ?: JSONArray()
        val unique = linkedMapOf<String, CrossingPoint>()
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val id = "${element.optString("type", "element")}/${element.optLong("id")}"
            val coordinate = coordinateOf(element) ?: continue
            val tags = element.optJSONObject("tags") ?: JSONObject()
            val type = crossingType(tags) ?: continue
            val point = CrossingPoint(
                id = id,
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
                type = type,
                distanceMeters = GeoMath.distanceMeters(origin, coordinate)
            )
            // Preferir crossing sinalizada quando OSM expõe o mesmo elemento mais de uma vez.
            val old = unique[id]
            if (old == null || type == CrossingType.SIGNALIZED_CROSSING) unique[id] = point
        }
        return unique.values.sortedBy { it.distanceMeters }
    }

    private fun coordinateOf(element: JSONObject): GeoPoint? {
        if (element.has("lat") && element.has("lon")) {
            return GeoPoint(element.optDouble("lat"), element.optDouble("lon"))
        }
        val center = element.optJSONObject("center") ?: return null
        if (!center.has("lat") || !center.has("lon")) return null
        return GeoPoint(center.optDouble("lat"), center.optDouble("lon"))
    }

    private fun crossingType(tags: JSONObject): CrossingType? {
        val highway = tags.optString("highway")
        val crossing = tags.optString("crossing")
        return when {
            crossing == "traffic_signals" -> CrossingType.SIGNALIZED_CROSSING
            highway == "traffic_signals" -> CrossingType.TRAFFIC_SIGNAL
            highway == "crossing" || crossing in setOf("zebra", "uncontrolled", "marked") ->
                CrossingType.PEDESTRIAN_CROSSING
            else -> null
        }
    }
}
