package com.jeffery.assistant.automation

import android.content.Context
import android.location.LocationManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Current weather via Open-Meteo (no API key needed) using the device's last known
 * location. This makes a real (blocking) network call, so callers must run it off
 * the main thread — AutomationEngine's caller in AssistantViewModel already does.
 */
object WeatherTool {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val weatherCodeDescriptions = mapOf(
        0 to "clear sky", 1 to "mostly clear", 2 to "partly cloudy", 3 to "overcast",
        45 to "foggy", 48 to "foggy", 51 to "light drizzle", 61 to "light rain",
        63 to "rain", 65 to "heavy rain", 71 to "light snow", 73 to "snow",
        75 to "heavy snow", 80 to "rain showers", 95 to "thunderstorms"
    )

    fun currentWeather(context: Context): String {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        val location = providers.firstNotNullOfOrNull {
            try {
                @Suppress("MissingPermission")
                locationManager.getLastKnownLocation(it)
            } catch (e: SecurityException) {
                null
            }
        } ?: return "I don't have a location to check weather for — grant Location access first."

        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}" +
                "&longitude=${location.longitude}&current=temperature_2m,weather_code&temperature_unit=fahrenheit"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "Couldn't reach the weather service right now."
                val json = JSONObject(response.body?.string().orEmpty())
                val current = json.optJSONObject("current") ?: return "Weather data came back empty."
                val temp = current.optDouble("temperature_2m", Double.NaN)
                val code = current.optInt("weather_code", -1)
                val description = weatherCodeDescriptions[code] ?: "conditions I don't have a description for"
                if (temp.isNaN()) "Couldn't parse the weather data." else "It's about ${temp.toInt()}°F and $description right now."
            }
        } catch (e: Exception) {
            "Couldn't check the weather: ${e.message}"
        }
    }
}
