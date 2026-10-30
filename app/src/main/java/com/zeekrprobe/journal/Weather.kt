package com.zeekrprobe.journal

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WeatherData(val tempC: Double?, val windMs: Double?)

/** Current weather from open-meteo (free, no API key). Call off the main thread. */
object Weather {
    fun fetch(lat: Double, lon: Double): WeatherData? = try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,wind_speed_10m&wind_speed_unit=ms"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000
        }
        val body = conn.inputStream.bufferedReader().readText()
        val cur = JSONObject(body).optJSONObject("current")
        if (cur == null) null
        else WeatherData(
            tempC = if (cur.has("temperature_2m")) cur.getDouble("temperature_2m") else null,
            windMs = if (cur.has("wind_speed_10m")) cur.getDouble("wind_speed_10m") else null,
        )
    } catch (_: Throwable) {
        null
    }
}
