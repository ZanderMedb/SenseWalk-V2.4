package com.travessia.segura.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** Pesquisa geográfica Nominatim/OSM, com User-Agent identificável e baixa cadência por ação do usuário. */
class PlaceSearchRepository(
    private val endpoint: String = "https://nominatim.openstreetmap.org/search"
) {
    suspend fun search(query: String): Result<List<DestinationPlace>> = withContext(Dispatchers.IO) {
        runCatching {
            require(query.trim().length >= 3) { "Informe ao menos três caracteres." }
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val connection = (URL("$endpoint?format=jsonv2&limit=5&q=$encoded").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", "SenseWalk-academic-demo/2.4 (accessible mobility research)")
                setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
            }
            try {
                if (connection.responseCode !in 200..299) error("Nominatim HTTP ${connection.responseCode}")
                val results = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                buildList {
                    for (index in 0 until results.length()) {
                        val item = results.optJSONObject(index) ?: continue
                        val lat = item.optString("lat").toDoubleOrNull() ?: continue
                        val lon = item.optString("lon").toDoubleOrNull() ?: continue
                        add(DestinationPlace(item.optString("display_name"), GeoPoint(lat, lon)))
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
