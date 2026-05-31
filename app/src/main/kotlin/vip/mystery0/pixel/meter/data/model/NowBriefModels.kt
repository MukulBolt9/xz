package com.kakao.taxi.data.model

data class WeatherData(
    val condition: String = "Clear",
    val temperature: Double = 0.0,
    val feelsLike: Double = 0.0,
    val humidity: Int = 0,
    val windSpeed: Double = 0.0,
    val cityName: String = "Unknown",
    val icon: String = "☀️",
    val uvIndex: Int = 0,
    val visibility: Double = 10.0,
    val rainChance: Int = 0,
    val sunrise: String = "6:00 AM",
    val sunset: String = "6:00 PM",
    val airQualityIndex: Int = 0,       // AQI from Open-Meteo European AQI
    val airQualityLabel: String = "",   // "Good" / "Fair" / "Poor" / "Very Poor"
    val airQualityEmoji: String = "🟢"
)

data class NewsArticle(
    val title: String,
    val description: String,
    val source: String,
    val url: String,
    val publishedAt: String,
    val category: String = "General",
    val imageUrl: String? = null
)

data class MusicRecommendation(
    val title: String,
    val artist: String,
    val mood: String,
    val thumbnailUrl: String? = null,
    val reason: String
)

data class DailyFocus(
    val goal: String = "",              // AI-generated single focus for the day
    val steps: String = "",            // Actionable micro-steps
    val completed: Boolean = false
)

data class NowBriefSummary(
    val greeting: String = "",
    val weatherSummary: String = "",
    val weatherAdvice: String = "",
    val daySummary: String = "",
    val newsSummary: String = "",
    val musicMood: String = "",
    val tip: String = "",
    val quote: String = "",
    val tips: List<String> = emptyList(),
    val music: MusicRecommendation? = null,
    val focus: DailyFocus? = null      // Daily focus goal
)

data class BriefState(
    val summary: NowBriefSummary = NowBriefSummary(),
    val weather: WeatherData = WeatherData(),
    val news: List<NewsArticle> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastUpdated: Long = 0L
)
