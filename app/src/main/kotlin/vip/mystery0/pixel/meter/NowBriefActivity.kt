package com.kakao.taxi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.kakao.taxi.data.model.*
import com.kakao.taxi.data.repository.NowBriefRepository
import com.kakao.taxi.ui.theme.PixelPulseTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.*

class NowBriefActivity : ComponentActivity() {

    private val briefRepository: NowBriefRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PixelPulseTheme {
                NowBriefScreen(briefRepository)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowBriefScreen(repo: NowBriefRepository) {
    val state by repo.briefState.collectAsState()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })
    val context = LocalContext.current

    val tabs = listOf(
        Triple("Summary", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
        Triple("Weather", Icons.Filled.WbSunny, Icons.Outlined.WbSunny),
        Triple("News", Icons.Filled.Newspaper, Icons.Outlined.Newspaper)
    )

    LaunchedEffect(Unit) {
        if (state.lastUpdated == 0L) {
            repo.refreshAll()
        }
    }

    Scaffold(
        topBar = {
            NowBriefTopBar(
                isLoading = state.isLoading,
                lastUpdated = state.lastUpdated,
                onRefresh = { scope.launch { repo.refreshAll() } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab Row
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surface,
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        Box(
                            Modifier
                                .tabIndicatorOffset(tabPositions[pagerState.currentPage])
                                .height(3.dp)
                                .background(
                                    brush = Brush.horizontalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary
                                        )
                                    ),
                                    shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                                )
                        )
                    }
                }
            ) {
                tabs.forEachIndexed { index, (label, filledIcon, outlinedIcon) ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = {
                            Icon(
                                imageVector = if (pagerState.currentPage == index) filledIcon else outlinedIcon,
                                contentDescription = label,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> SummaryTab(state = state, onRefresh = { scope.launch { repo.refreshAll() } })
                    1 -> WeatherTab(weather = state.weather, isLoading = state.isLoading)
                    2 -> NewsTab(
                        news = state.news,
                        isLoading = state.isLoading,
                        onArticleClick = { url ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: Exception) {}
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun NowBriefTopBar(isLoading: Boolean, lastUpdated: Long, onRefresh: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (isLoading) 360f else 0f,
        animationSpec = if (isLoading)
            infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart)
        else tween(0),
        label = "rotation"
    )
    val lastUpdatedText = remember(lastUpdated) {
        if (lastUpdated == 0L) "" else {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            "Updated ${sdf.format(Date(lastUpdated))}"
        }
    }

    TopAppBar(
        title = {
            Column {
                Text(
                    "NowBrief",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (lastUpdatedText.isNotEmpty()) {
                    Text(
                        lastUpdatedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    modifier = Modifier.rotate(rotation),
                    tint = if (isLoading) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

// ─── TAB 1: SUMMARY ────────────────────────────────────────────────

@Composable
fun SummaryTab(state: BriefState, onRefresh: () -> Unit) {
    if (state.isLoading && state.lastUpdated == 0L) {
        LoadingScreen("Crafting your brief…")
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Greeting hero card
        item {
            GreetingCard(
                greeting = state.summary.greeting.ifBlank { "Hello! ✨" },
                daySummary = state.summary.daySummary,
                weatherSummary = state.summary.weatherSummary
            )
        }

        // Quick weather pill
        if (state.weather.cityName.isNotBlank()) {
            item {
                WeatherPillCard(state.weather)
            }
        }

        // Music recommendation
        if (state.summary.music != null) {
            item {
                MusicCard(music = state.summary.music, mood = state.summary.musicMood)
            }
        }

        // Daily tip
        if (state.summary.tip.isNotBlank()) {
            item {
                TipCard(tip = state.summary.tip)
            }
        }

        // Error state
        if (state.error != null) {
            item {
                ErrorCard(error = state.error, onRetry = onRefresh)
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun GreetingCard(greeting: String, daySummary: String, weatherSummary: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "gradient")
    val animOffset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Reverse),
        label = "gradOffset"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        start = Offset(animOffset, 0f),
                        end = Offset(animOffset + 600f, 600f)
                    )
                )
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    greeting,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (weatherSummary.isNotBlank()) {
                    Text(
                        weatherSummary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                    )
                }
                if (daySummary.isNotBlank()) {
                    Text(
                        daySummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

@Composable
fun WeatherPillCard(weather: WeatherData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(weather.icon, fontSize = 36.sp)
                Column {
                    Text(
                        "${weather.temperature.toInt()}°C · ${weather.condition}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${weather.cityName} · Rain ${weather.rainChance}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Feels ${weather.feelsLike.toInt()}°", style = MaterialTheme.typography.labelMedium)
                Text("UV ${weather.uvIndex}", style = MaterialTheme.typography.labelSmall,
                    color = when {
                        weather.uvIndex >= 8 -> Color(0xFFE53935)
                        weather.uvIndex >= 5 -> Color(0xFFFB8C00)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
            }
        }
    }
}

@Composable
fun MusicCard(music: MusicRecommendation, mood: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail placeholder with music note
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Music for your mood",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            mood.lowercase().capitalize(Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Text(
                    music.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    music.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
                if (music.reason.isNotBlank()) {
                    Text(
                        music.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.55f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun TipCard(tip: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp).padding(top = 2.dp)
            )
            Text(
                tip,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
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
            Text("Couldn't load data", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
            Text(error, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

// ─── TAB 2: WEATHER ────────────────────────────────────────────────

@Composable
fun WeatherTab(weather: WeatherData, isLoading: Boolean) {
    if (isLoading && weather.cityName == "Unknown") {
        LoadingScreen("Fetching weather…")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero temperature card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        )
                        .padding(28.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(weather.cityName,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(weather.icon, fontSize = 64.sp)
                            Column {
                                Text(
                                    "${weather.temperature.toInt()}°C",
                                    style = MaterialTheme.typography.displayLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(weather.condition,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Feels like ${weather.feelsLike.toInt()}°C",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f))
                    }
                }
            }
        }

        // Details grid
        item {
            Text("Details", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
        }
        item {
            WeatherDetailsGrid(weather)
        }

        // Sunrise / Sunset
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SunTimeItem(icon = "🌅", label = "Sunrise", time = weather.sunrise)
                    VerticalDivider(modifier = Modifier.height(48.dp))
                    SunTimeItem(icon = "🌇", label = "Sunset", time = weather.sunset)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun WeatherDetailsGrid(weather: WeatherData) {
    val items = listOf(
        Triple("💧", "Humidity", "${weather.humidity}%"),
        Triple("💨", "Wind", "${weather.windSpeed.toInt()} km/h"),
        Triple("🌂", "Rain Chance", "${weather.rainChance}%"),
        Triple("☀️", "UV Index", uvLabel(weather.uvIndex)),
        Triple("👁️", "Visibility", "${weather.visibility.toInt()} km"),
        Triple("🌡️", "Feels Like", "${weather.feelsLike.toInt()}°C")
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (icon, label, value) ->
                    WeatherDetailItem(
                        icon = icon,
                        label = label,
                        value = value,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun WeatherDetailItem(icon: String, label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(icon, fontSize = 24.sp)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SunTimeItem(icon: String, label: String, time: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(icon, fontSize = 28.sp)
        Text(time, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun uvLabel(uv: Int) = when {
    uv <= 2 -> "$uv · Low"
    uv <= 5 -> "$uv · Moderate"
    uv <= 7 -> "$uv · High"
    uv <= 10 -> "$uv · Very High"
    else -> "$uv · Extreme"
}

// ─── TAB 3: NEWS ────────────────────────────────────────────────────

@Composable
fun NewsTab(news: List<NewsArticle>, isLoading: Boolean, onArticleClick: (String) -> Unit) {
    if (isLoading && news.isEmpty()) {
        LoadingScreen("Loading latest news…")
        return
    }
    if (news.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📰", fontSize = 48.sp)
                Spacer(Modifier.height(8.dp))
                Text("No news loaded yet", style = MaterialTheme.typography.bodyLarge)
                Text("Pull to refresh", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    // Group by category
    val grouped = news.groupBy { it.category }
    val categoryEmojis = mapOf(
        "World" to "🌍", "Technology" to "💻", "Business" to "📈",
        "Sports" to "⚽", "Science" to "🔬", "Entertainment" to "🎬",
        "General" to "📰", "Health" to "🏥"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        grouped.forEach { (category, articles) ->
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    Text(categoryEmojis[category] ?: "📰", fontSize = 18.sp)
                    Text(
                        category,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            items(articles) { article ->
                NewsCard(article = article, onClick = { onArticleClick(article.url) })
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun NewsCard(article: NewsArticle, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                article.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )
            if (article.description.isNotBlank()) {
                Text(
                    article.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    Text(
                        article.source,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    article.publishedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── SHARED ──────────────────────────────────────────────────────────

@Composable
fun LoadingScreen(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
