package com.kakao.taxi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import com.kakao.taxi.data.model.*
import com.kakao.taxi.data.repository.NowBriefRepository
import com.kakao.taxi.ui.theme.PixelPulseTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.*

// ─── Brand Colors ────────────────────────────────────────────────────
private val WarmOrange      = Color(0xFFE8804A)
private val WarmOrangeLight = Color(0xFFF0A070)
private val CreamBg         = Color(0xFFFAF6F1)
private val CreamCard       = Color(0xFFF5EFE8)
private val CreamCardAlt    = Color(0xFFEEE8F5)
private val TextPrimary     = Color(0xFF2D2016)
private val TextSecondary   = Color(0xFF7A6650)
private val TextMuted       = Color(0xFFAA9980)
private val DividerColor    = Color(0xFFE8E0D5)

// Dark mode ocean-blue equivalents (used via MaterialTheme.colorScheme)
// Background  = Color(0xFF060D18)  dark navy
// Surface     = Color(0xFF0B1828)  slate blue surface
// SurfaceVariant = Color(0xFF112236) card bg
// Primary     = Color(0xFF7EB8F7)  sky blue accent
// onSurface   = Color(0xFFD0E8FF)  pale blue-white text
// onSurfaceVariant = Color(0xFF90B8D8) steel blue label

class NowBriefActivity : ComponentActivity() {
    private val briefRepository: NowBriefRepository by inject()
    private val networkRepository: com.kakao.taxi.data.repository.NetworkRepository by inject()
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val isDark by networkRepository.isDarkThemeEnabled.collectAsState()
            val isOled by networkRepository.isOledThemeEnabled.collectAsState()
            PixelPulseTheme(darkTheme = isDark, isOledTheme = isOled) {
                NowBriefScreen(briefRepository)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowBriefScreen(repo: NowBriefRepository) {
    val state   by repo.briefState.collectAsState()
    val scope   = rememberCoroutineScope()
    val pager   = rememberPagerState(pageCount = { 3 })
    val context = LocalContext.current

    val tabs = listOf("Summary", "Weather", "News")

    // ── Location permission ──────────────────────────────────────────
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            // Permission just granted — clear cached Howrah fallback and re-fetch
            repo.clearLocationCache()
            scope.launch { repo.refreshAll() }
        }
    }

    LaunchedEffect(Unit) {
        val hasFine   = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            // Already have permission — load normally
            val staleMs = 15 * 60 * 1000L
            if (state.lastUpdated == 0L || (System.currentTimeMillis() - state.lastUpdated) > staleMs)
                repo.refreshAll()
        }
    }

    // Rotate quote + music every 5 minutes while the screen is open
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5 * 60 * 1000L)
            repo.refreshQuoteAndMusic()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // ── Top bar ─────────────────────────────────────────────
            TopHeader(
                isLoading   = state.isLoading,
                cityName    = state.weather.cityName,
                lastUpdated = state.lastUpdated,
                onRefresh   = { scope.launch { repo.refreshAll() } }
            )

            // ── Tab pills ───────────────────────────────────────────
            TabPills(
                tabs      = tabs,
                selected  = pager.currentPage,
                onSelect  = { scope.launch { pager.animateScrollToPage(it) } }
            )

            Spacer(Modifier.height(4.dp))

            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> SummaryTab(state = state, onRefresh = { scope.launch { repo.refreshAll() } })
                    1 -> WeatherTab(weather = state.weather, isLoading = state.isLoading)
                    2 -> NewsTab(
                        news        = state.news,
                        isLoading   = state.isLoading,
                        onRefresh   = { scope.launch { repo.refreshAll() } },
                        onArticleClick = { url ->
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                        }
                    )
                }
            }
        }
    }
}

// ─── TOP HEADER ──────────────────────────────────────────────────────

@Composable
fun TopHeader(isLoading: Boolean, cityName: String, lastUpdated: Long, onRefresh: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (isLoading) 360f else 0f,
        animationSpec = if (isLoading)
            infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart)
        else tween(0),
        label = "spin"
    )
    val timeStr = remember { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) }
    val dateStr = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()).uppercase() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = timeStr,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = (-0.5).sp
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(dateStr, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.5.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
                Text(
                    cityName.ifBlank { "Your Location" },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontWeight = FontWeight.Normal
                )
            }
        }
        IconButton(onClick = onRefresh, enabled = !isLoading) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Refresh",
                tint = if (isLoading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp).rotate(rotation)
            )
        }
    }
}

// ─── TAB PILLS ───────────────────────────────────────────────────────

@Composable
fun TabPills(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEachIndexed { i, label ->
            val isSelected = i == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .border(
                        width = if (isSelected) 0.dp else 1.dp,
                        color = if (isSelected) Color.Transparent else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(50)
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── TAB 1: SUMMARY ──────────────────────────────────────────────────

@Composable
fun SummaryTab(state: BriefState, onRefresh: () -> Unit) {
    if (state.isLoading && state.lastUpdated == 0L) {
        WarmLoadingScreen("Crafting your brief…")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Big greeting
        item {
            Text(
                text = state.summary.greeting.ifBlank { "Hello" },
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                lineHeight = 42.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }

        // Day Summary (AI: warm 2-sentence overview of the day)
        if (state.summary.daySummary.isNotBlank()) {
            item { AISummaryCard(
                label = "YOUR DAY AT A GLANCE",
                icon = "🌟",
                text = state.summary.daySummary,
                accentColor = MaterialTheme.colorScheme.primary
            )}
        }

        // Weather outlook + advice combined
        if (state.summary.weatherSummary.isNotBlank() || state.summary.weatherAdvice.isNotBlank()) {
            item { WeatherSummaryCard(
                weatherSummary = state.summary.weatherSummary,
                weatherAdvice  = state.summary.weatherAdvice,
                icon           = state.weather.icon
            )}
        }

        // AI News Digest
        if (state.summary.newsSummary.isNotBlank()) {
            item { AISummaryCard(
                label = "NEWS DIGEST",
                icon = "📰",
                text = state.summary.newsSummary,
                accentColor = Color(0xFF2196F3)
            )}
        }

        // Quote card
        if (state.summary.quote.isNotBlank()) {
            item { QuoteCard(state.summary.quote) }
        }

        // Music card
        if (state.summary.music != null) {
            item { MusicCard(music = state.summary.music) }
        }

        // Daily Tips
        if (state.summary.tips.isNotEmpty()) {
            item { DailyTipsSection(tips = state.summary.tips) }
        }

        // Error
        if (state.error != null) {
            item { ErrorCard(error = state.error, onRetry = onRefresh) }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun AISummaryCard(label: String, icon: String, text: String, accentColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(icon, fontSize = 16.sp)
                Text(
                    label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    letterSpacing = 1.sp
                )
            }
            Text(
                text = text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun WeatherSummaryCard(weatherSummary: String, weatherAdvice: String, icon: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 24.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Text(
                    "TODAY'S WEATHER",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    letterSpacing = 1.sp
                )
                if (weatherSummary.isNotBlank()) {
                    Text(weatherSummary, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 20.sp)
                }
                if (weatherAdvice.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "💡 $weatherAdvice",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuoteCard(quote: String) {
    // Split quote text from attribution if present (separated by — or -)
    val parts = quote.split(" — ", " - ", "—", "–").map { it.trim() }
    val quoteText   = if (parts.size >= 2) "\"${parts[0]}\"" else "\"$quote\""
    val attribution = if (parts.size >= 2) "— ${parts[1]}" else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = quoteText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
                fontStyle = FontStyle.Italic
            )
            if (attribution.isNotBlank()) {
                Text(
                    text = attribution,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun MusicCard(music: MusicRecommendation) {
    // Parse mood tags from "Soul · Uplifting" or similar
    val tags = music.mood.split(" · ", " / ", ", ").map { it.trim() }.filter { it.isNotBlank() }
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "MUSIC FOR YOUR MOOD",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val query = Uri.encode("${music.title} ${music.artist}")
                    val uri = Uri.parse("https://open.spotify.com/search/$query")
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            Brush.radialGradient(listOf(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primary)),
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(music.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(music.artist, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (music.reason.isNotBlank()) {
                        Text(music.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.take(2).forEach { tag ->
                            Box(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text(tag, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Open in Spotify",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
fun DailyTipsSection(tips: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "DAILY TIPS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )
        tips.forEach { tip ->
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    tip,
                    fontSize = 13.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ErrorCard(error: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Couldn't load data", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRetry) { Text("Retry", color = MaterialTheme.colorScheme.primary) }
        }
    }
}

// ─── TAB 2: WEATHER ──────────────────────────────────────────────────

@Composable
fun WeatherTab(weather: WeatherData, isLoading: Boolean) {
    if (isLoading && weather.cityName == "Unknown") {
        WarmLoadingScreen("Fetching weather…")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)))
                    .padding(28.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
                        Text(weather.cityName, fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(weather.icon, fontSize = 60.sp)
                        Column {
                            Text("${weather.temperature.toInt()}°C", fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text(weather.condition, fontSize = 16.sp, color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                    Text("Feels like ${weather.feelsLike.toInt()}°C", fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f))
                }
            }
        }

        // Detail grid
        item { WeatherGrid(weather) }

        // Sunrise / Sunset
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SunItem("🌅", "Sunrise", weather.sunrise)
                    Box(modifier = Modifier.width(1.dp).height(44.dp).background(MaterialTheme.colorScheme.outline))
                    SunItem("🌇", "Sunset", weather.sunset)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun WeatherGrid(weather: WeatherData) {
    val items = listOf(
        Triple("💧", "Humidity", "${weather.humidity}%"),
        Triple("💨", "Wind", "${weather.windSpeed.toInt()} km/h"),
        Triple("🌂", "Rain Chance", "${weather.rainChance}%"),
        Triple("☀️", "UV Index", uvLabel(weather.uvIndex)),
        Triple("👁️", "Visibility", "${weather.visibility.toInt()} km"),
        Triple("🌡️", "Feels Like", "${weather.feelsLike.toInt()}°C")
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (icon, label, value) ->
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(icon, fontSize = 22.sp)
                            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SunItem(icon: String, label: String, time: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(icon, fontSize = 26.sp)
        Text(time, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

fun uvLabel(uv: Int) = when {
    uv <= 2 -> "$uv · Low"
    uv <= 5 -> "$uv · Moderate"
    uv <= 7 -> "$uv · High"
    uv <= 10 -> "$uv · Very High"
    else     -> "$uv · Extreme"
}

// ─── TAB 3: NEWS ─────────────────────────────────────────────────────

@Composable
fun NewsTab(news: List<NewsArticle>, isLoading: Boolean, onRefresh: () -> Unit = {}, onArticleClick: (String) -> Unit) {
    if (isLoading && news.isEmpty()) {
        WarmLoadingScreen("Loading latest news…")
        return
    }
    // "System" category = offline fallback articles — show error state instead
    val isErrorState = news.isNotEmpty() && news.all { it.category == "System" }
    if (news.isEmpty() || isErrorState) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 32.dp)) {
                Text("📡", fontSize = 48.sp)
                Text(
                    if (isErrorState) "News unavailable" else "No news loaded yet",
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (isErrorState) "Check your internet connection and try again."
                    else "Tap below to load today's top stories.",
                    fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        }
        return
    }

    val grouped = news.groupBy { it.category }
    val categoryEmojis = mapOf(
        "World" to "🌍", "Technology" to "💻", "Business" to "📈",
        "Sports" to "⚽", "Science" to "🔬", "Entertainment" to "🎬",
        "General" to "📰", "Health" to "🏥", "Top News" to "🗞️",
        "Politics" to "🏛️", "Environment" to "🌿"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        grouped.forEach { (category, articles) ->
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                ) {
                    Text(categoryEmojis[category] ?: "📰", fontSize = 16.sp)
                    Text(
                        category,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.3.sp
                    )
                }
            }
            items(articles) { article ->
                NewsCard(article = article, onClick = { onArticleClick(article.url) })
                if (article != articles.last()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun NewsCard(article: NewsArticle, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            article.title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 21.sp
        )
        if (article.description.isNotBlank()) {
            Text(
                article.description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 19.sp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                article.source,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMuted
            )
            if (article.publishedAt.isNotBlank()) {
                Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                Text(article.publishedAt, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

// ─── SHARED ──────────────────────────────────────────────────────────

@Composable
fun WarmLoadingScreen(message: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
            Text(message, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
