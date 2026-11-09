package com.travessia.segura.navigation

import com.travessia.segura.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * Cliente do endpoint documentado GraphHopper Directions API para perfil foot.
 * Uma chave é obrigatória e deve estar em local.properties/variável GRAPHHOPPER_API_KEY.
 */
class GraphHopperRouteRepository(
    private val apiKey: String = BuildConfig.GRAPHHOPPER_API_KEY,
    private val endpoint: String = "https://graphhopper.com/api/1/route"
) {
    fun isConfigured(): Boolean = apiKey.isNotBlank()

    suspend fun route(origin: GeoPoint, destination: DestinationPlace, locale: String = "pt_BR"): Result<PedestrianRoute> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(isConfigured()) {
                    "Roteamento não configurado. Defina GRAPHHOPPER_API_KEY no local.properties."
                }
                val url = "$endpoint?point=${origin.latitude},${origin.longitude}" +
                    "&point=${destination.point.latitude},${destination.point.longitude}" +
                    "&profile=foot&locale=${URLEncoder.encode(locale, "UTF-8")}" +
                    "&instructions=true&points_encoded=false&key=${URLEncoder.encode(apiKey, "UTF-8")}"
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", "SenseWalk-academic-demo/2.4")
                }
                try {
                    if (connection.responseCode !in 200..299) {
                        val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        error("GraphHopper HTTP ${connection.responseCode}: $error")
                    }
                    parse(connection.inputStream.bufferedReader().use { it.readText() }, destination)
                } finally {
                    connection.disconnect()
                }
            }
        }

    fun parse(response: String, destination: DestinationPlace): PedestrianRoute {
        val root = JSONObject(response)
        val path = root.optJSONArray("paths")?.optJSONObject(0)
            ?: error("Nenhuma rota de pedestre foi retornada.")
        val geometry = parseGeometry(path.optJSONObject("points")?.optJSONArray("coordinates") ?: JSONArray())
        require(geometry.isNotEmpty()) { "A rota retornou sem geometria." }
        val instructionsJson = path.optJSONArray("instructions") ?: JSONArray()
        val instructions = buildList {
            for (index in 0 until instructionsJson.length()) {
                val instruction = instructionsJson.optJSONObject(index) ?: continue
                val interval = instruction.optJSONArray("interval")
                val geometryIndex = interval?.optInt(0, 0)?.coerceIn(0, geometry.lastIndex) ?: 0
                add(
                    RouteInstruction(
                        text = instruction.optString("text", "Continue em frente."),
                        point = geometry[geometryIndex],
                        distanceMeters = instruction.optDouble("distance", 0.0),
                        sign = instruction.optInt("sign", 0)
                    )
                )
            }
        }
        return PedestrianRoute(
            destination = destination,
            distanceMeters = path.optDouble("distance", 0.0),
            timeMillis = path.optLong("time", 0L),
            geometry = geometry,
            instructions = instructions
        )
    }

    private fun parseGeometry(coordinates: JSONArray): List<GeoPoint> = buildList {
        for (index in 0 until coordinates.length()) {
            val pair = coordinates.optJSONArray(index) ?: continue
            if (pair.length() >= 2) add(GeoPoint(pair.optDouble(1), pair.optDouble(0)))
        }
    }
}
