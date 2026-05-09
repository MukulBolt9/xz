package com.kakao.taxi.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.kakao.taxi.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class NowBriefRepository(private val context: Context) {
    companion object {
        private const val TAG = "NowBriefRepository"
        private const val GEMINI_API_KEY = "AIzaSyC356BnpkkFlWyIclsX5aB1OMvY-uNW0Hk"
        private const val GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$GEMINI_API_KEY"
        // GNews API (free tier) - fallback to Gemini-generated news topics
        private const val GNEWS_API_KEY = "eb54c3ba796db9f53d43baff20e71dfc"
    }

    private val _briefState = MutableStateFlow(BriefState(isLoading = false))
    val briefState: StateFlow<BriefState> = _briefState.asStateFlow()

    // Notification messages that cycle every ~30min
    private val greetingMessages = listOf(
        "Good morning! ☀️",
        "Hope you're having a great day! 🌟",
        "Stay hydrated today! 💧",
        "You've got this! 💪",
        "Good afternoon! ⛅",
        "Take a deep breath 🌿",
        "Good evening! 🌙",
        "Time to wind down ✨",
        "Have a peaceful night 🌠"
    )

    fun getContextualGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 6 -> "Still up? Rest well 🌙"
            hour < 12 -> "Good morning! ☀️"
            hour < 17 -> "Good afternoon! ⛅"
            hour < 21 -> "Good evening! 🌆"
            else -> "Time to relax 🌙"
        }
    }

    suspend fun refreshAll() {
        _briefState.value = _briefState.value.copy(isLoading = true, error = null)
        try {
            val weather = fetchWeather()
            val aiData = fetchGeminiSummaryAndNews(weather)
            _briefState.value = BriefState(
                summary = aiData.first,
                weather = weather,
                news = aiData.second,
                isLoading = false,
                lastUpdated = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "refreshAll error", e)
            _briefState.value = _briefState.value.copy(
                isLoading = false,
                error = e.message ?: "Failed to load data"
            )
        }
    }

    private suspend fun fetchWeather(): WeatherData = withContext(Dispatchers.IO) {
        try {
            // Use open-meteo (free, no API key) with default coords (can be updated with location)
            val (lat, lon, city) = getLocation()
            val url = "https://api.open-meteo.com/v1/forecast?" +
                    "latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
                    "weather_code,wind_speed_10m,precipitation_probability,uv_index,visibility" +
                    "&daily=sunrise,sunset&timezone=auto&forecast_days=1"

            val response = httpGet(url)
            val json = JSONObject(response)
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")

            val wmoCode = current.getInt("weather_code")
            val (condition, icon) = wmoToCondition(wmoCode)

            WeatherData(
                condition = condition,
                temperature = current.getDouble("temperature_2m"),
                feelsLike = current.getDouble("apparent_temperature"),
                humidity = current.getInt("relative_humidity_2m"),
                windSpeed = current.getDouble("wind_speed_10m"),
                cityName = city,
                icon = icon,
                uvIndex = current.optInt("uv_index", 0),
                visibility = current.optDouble("visibility", 10000.0) / 1000,
                rainChance = current.optInt("precipitation_probability", 0),
                sunrise = formatTime(daily.getJSONArray("sunrise").getString(0)),
                sunset = formatTime(daily.getJSONArray("sunset").getString(0))
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchWeather error", e)
            WeatherData(condition = "Clear", temperature = 28.0, cityName = "Your City", icon = "☀️")
        }
    }

    private fun getLocation(): Triple<Double, Double, String> {
        return try {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val providers = lm.getProviders(true)
                var bestLoc: android.location.Location? = null
                for (provider in providers) {
                    val l = lm.getLastKnownLocation(provider) ?: continue
                    if (bestLoc == null || l.accuracy < bestLoc.accuracy) bestLoc = l
                }
                if (bestLoc != null) {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    val addresses = geocoder.getFromLocation(bestLoc.latitude, bestLoc.longitude, 1)
                    val city = addresses?.firstOrNull()?.locality ?: "Your City"
                    Triple(bestLoc.latitude, bestLoc.longitude, city)
                } else Triple(22.65, 87.18, "Salboni") // Fallback to Salboni
            } else Triple(22.65, 87.18, "Salboni") // Default to Salboni area
        } catch (e: Exception) {
            Triple(22.65, 87.18, "Salboni")
        }
    }

    private suspend fun fetchGeminiSummaryAndNews(
        weather: WeatherData
    ): Pair<NowBriefSummary, List<NewsArticle>> = withContext(Dispatchers.IO) {
        try {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val timeOfDay = when {
                hour < 12 -> "morning"
                hour < 17 -> "afternoon"
                hour < 21 -> "evening"
                else -> "night"
            }
            val prompt = """
You are NowBrief, a friendly AI assistant embedded in a Samsung Now Bar notification.
Current context:
- Time: $timeOfDay (hour: $hour)
- Weather: ${weather.condition}, ${weather.temperature.toInt()}°C, feels like ${weather.feelsLike.toInt()}°C, rain chance ${weather.rainChance}%, UV index ${weather.uvIndex}
- City: ${weather.cityName}

Respond ONLY with a valid JSON object (no markdown, no backticks). Format:
{
  "greeting": "short warm greeting for $timeOfDay",
  "weatherSummary": "one natural sentence about the weather and what to do",
  "daySummary": "2 sentence helpful tip for the $timeOfDay",
  "tip": "one short practical tip",
  "musicMood": "one word mood for music (e.g. energetic, calm, focused, cozy)",
  "musicTitle": "a real song title fitting the mood and weather",
  "musicArtist": "the artist name",
  "musicReason": "10 word max reason why",
  "news": [
    {"title": "headline 1", "description": "2 sentence summary", "source": "BBC", "category": "World", "url": "https://bbc.com"},
    {"title": "headline 2", "description": "2 sentence summary", "source": "TechCrunch", "category": "Technology", "url": "https://techcrunch.com"},
    {"title": "headline 3", "description": "2 sentence summary", "source": "Reuters", "category": "Business", "url": "https://reuters.com"},
    {"title": "headline 4", "description": "2 sentence summary", "source": "ESPN", "category": "Sports", "url": "https://espn.com"},
    {"title": "headline 5", "description": "2 sentence summary", "source": "The Verge", "category": "Technology", "url": "https://theverge.com"},
    {"title": "headline 6", "description": "2 sentence summary", "source": "CNN", "category": "World", "url": "https://cnn.com"}
  ]
}
Make the news realistic and plausible for today. Mix categories: World, Technology, Sports, Business, Science, Entertainment.
""".trimIndent()

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.8)
                    put("maxOutputTokens", 1500)
                })
            }

            val response = httpPost(GEMINI_URL, body.toString())
            val json = JSONObject(response)
            val text = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val data = JSONObject(text)

            val summary = NowBriefSummary(
                greeting = data.optString("greeting", getContextualGreeting()),
                weatherSummary = data.optString("weatherSummary", ""),
                daySummary = data.optString("daySummary", ""),
                musicMood = data.optString("musicMood", "calm"),
                tip = data.optString("tip", ""),
                music = MusicRecommendation(
                    title = data.optString("musicTitle", "Weightless"),
                    artist = data.optString("musicArtist", "Marconi Union"),
                    mood = data.optString("musicMood", "calm"),
                    reason = data.optString("musicReason", "Perfect for now")
                )
            )

            val newsArray = data.optJSONArray("news") ?: JSONArray()
            val newsList = mutableListOf<NewsArticle>()
            for (i in 0 until newsArray.length()) {
                val n = newsArray.getJSONObject(i)
                newsList.add(
                    NewsArticle(
                        title = n.optString("title", ""),
                        description = n.optString("description", ""),
                        source = n.optString("source", "News"),
                        url = n.optString("url", ""),
                        publishedAt = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()),
                        category = n.optString("category", "General")
                    )
                )
            }

            Pair(summary, newsList)
        } catch (e: Exception) {
            Log.e(TAG, "fetchGeminiSummaryAndNews error", e)
            val fallback = NowBriefSummary(
                greeting = getContextualGreeting(),
                weatherSummary = "It's ${weather.temperature.toInt()}°C outside. Stay comfortable!",
                daySummary = "Have a wonderful day ahead.",
                tip = "Stay hydrated and take breaks.",
                musicMood = "calm"
            )
            Pair(fallback, emptyList())
        }
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun httpPost(urlStr: String, body: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun wmoToCondition(code: Int): Pair<String, String> = when (code) {
        0 -> "Clear Sky" to "☀️"
        1, 2 -> "Partly Cloudy" to "⛅"
        3 -> "Overcast" to "☁️"
        45, 48 -> "Foggy" to "🌫️"
        51, 53, 55 -> "Drizzle" to "🌦️"
        61, 63, 65 -> "Rainy" to "🌧️"
        71, 73, 75 -> "Snowy" to "❄️"
        80, 81, 82 -> "Rain Showers" to "🌦️"
        95 -> "Thunderstorm" to "⛈️"
        96, 99 -> "Heavy Thunderstorm" to "🌩️"
        else -> "Clear" to "🌤️"
    }

    private fun formatTime(isoTime: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
            val date = sdf.parse(isoTime) ?: return isoTime
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            isoTime
        }
    }
}
