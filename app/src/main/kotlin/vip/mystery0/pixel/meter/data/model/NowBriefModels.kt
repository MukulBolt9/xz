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
    val sunset: String = "6:00 PM"
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

data class NowBriefSummary(
    val greeting: String = "",
    val weatherSummary: String = "",
    val daySummary: String = "",
    val musicMood: String = "",
    val tip: String = "",
    val music: MusicRecommendation? = null
)

data class BriefState(
    val summary: NowBriefSummary = NowBriefSummary(),
    val weather: WeatherData = WeatherData(),
    val news: List<NewsArticle> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastUpdated: Long = 0L
)
