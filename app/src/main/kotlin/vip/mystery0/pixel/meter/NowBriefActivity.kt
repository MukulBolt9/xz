package com.kakao.taxi

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.kakao.taxi.data.repository.DataStoreRepository
import com.kakao.taxi.data.repository.dataStore
import org.koin.android.ext.android.inject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

// ─── Brand Colors ─────────────────────────────────────────────────────
private val TextMuted = Color(0xFFAA9980)

// ─── Time of day helpers ───────────────────────────────────────────────
private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

private enum class TimeOfDay { DAWN, DAY, DUSK, NIGHT }

private fun getTimeOfDay(): TimeOfDay {
    val h = currentHour()
    return when {
        h in 5..7   -> TimeOfDay.DAWN
        h in 8..17  -> TimeOfDay.DAY
        h in 18..20 -> TimeOfDay.DUSK
        else        -> TimeOfDay.NIGHT
    }
}

// ─────────────────────────────────────────────────────────────────────
class NowBriefActivity : ComponentActivity() {
    private val briefRepository: NowBriefRepository by inject()
    private val networkRepository: com.kakao.taxi.data.repository.NetworkRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val isDark by networkRepository.isDarkThemeEnabled.collectAsState()
            val isOled by networkRepository.isOledThemeEnabled.collectAsState()
            val isNeo  by networkRepository.isNeoThemeEnabled.collectAsState()
            PixelPulseTheme(darkTheme = isDark, isOledTheme = isOled, isNeoTheme = isNeo) {
                NowBriefScreen(briefRepository, networkRepository)
            }
        }
    }
}

// ─── MAIN SCREEN ──────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowBriefScreen(repo: NowBriefRepository, networkRepo: com.kakao.taxi.data.repository.NetworkRepository) {
    val state   by repo.briefState.collectAsState()
    val scope   = rememberCoroutineScope()
    val pager   = rememberPagerState(pageCount = { 4 })
    val context = LocalContext.current
    val tabs    = listOf("Summary", "Weather", "News", "Vitals")
    val isNeo        by networkRepo.isNeoThemeEnabled.collectAsState()
    var articleUrl   by remember { mutableStateOf<String?>(null) }
    var articleTitle by remember { mutableStateOf("") }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            repo.clearLocationCache()
            scope.launch { repo.refreshAll() }
        }
    }

    LaunchedEffect(Unit) {
        val hasFine   = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            val stale = 15 * 60 * 1000L
            if (state.lastUpdated == 0L || (System.currentTimeMillis() - state.lastUpdated) > stale)
                repo.refreshAll()
        }
    }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(5 * 60 * 1000L); repo.refreshQuoteAndMusic() }
    }

    Box(Modifier.fillMaxSize().background(
        if (isNeo) Brush.radialGradient(
            listOf(Color(0xFF0F172A), Color(0xFF030712)),
            center = Offset(0.3f, 0f),
            radius = 1200f
        ) else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background))
    )) {
        // Neo scan-line animation
        if (isNeo) {
            val scanInf = rememberInfiniteTransition(label = "scan")
            val scanY by scanInf.animateFloat(0f, 1f,
                infiniteRepeatable(tween(4000, easing = LinearEasing)), "scanY")
            Canvas(Modifier.fillMaxSize()) {
                val y = scanY * size.height
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color(0x4422D3EE), Color.Transparent)),
                    Offset(0f, y), Offset(size.width, y), 1.5f
                )
            }
        }
        // Ambient corner glows for Neo
        if (isNeo) {
            Box(Modifier.size(220.dp).offset((-60).dp, (-60).dp)
                .background(Brush.radialGradient(listOf(Color(0x1522D3EE), Color.Transparent))))
            Box(Modifier.size(220.dp).align(Alignment.BottomEnd).offset(60.dp, 60.dp)
                .background(Brush.radialGradient(listOf(Color(0x15E879F9), Color.Transparent))))
        }
        Column(Modifier.fillMaxSize()) {
            TopHeader(state.isLoading, state.weather.cityName, state.lastUpdated, isNeo,
                onRefresh = { scope.launch { repo.refreshAll() } },
                onNeoToggle = { networkRepo.setNeoThemeEnabled(!isNeo) }
            )
            TabPills(tabs, pager.currentPage, isNeo) { scope.launch { pager.animateScrollToPage(it) } }
            Spacer(Modifier.height(4.dp))
            HorizontalPager(state = pager, Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> SummaryTab(state) { scope.launch { repo.refreshAll() } }
                    1 -> WeatherTab(state.weather, state.isLoading)
                    2 -> NewsTab(state.news, state.isLoading, isNeo,
                        onRefresh = { scope.launch { repo.refreshAll() } },
                        onArticleClick = { url ->
                            val art = state.news.firstOrNull { it.url == url }
                            articleTitle = art?.title ?: "Article"
                            articleUrl = url
                        }
                    )
                    3 -> VitalsTab()
                }
            }
        }

        // ── Article viewer overlay ────────────────────────────────────
        articleUrl?.let { url ->
            ArticleViewer(url = url, title = articleTitle, isNeo = isNeo, onClose = { articleUrl = null })
        }
    }
}

// ─── ARTICLE VIEWER ───────────────────────────────────────────────────
@Composable
fun ArticleViewer(url: String, title: String, isNeo: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var loadProgress by remember { mutableStateOf(0f) }
    var isPageLoading by remember { mutableStateOf(true) }
    var pageTitle by remember { mutableStateOf(title) }

    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    DisposableEffect(backDispatcher) {
        val cb = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { onClose() } }
        backDispatcher?.addCallback(cb); onDispose { cb.remove() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.72f)).clickable { onClose() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.82f)
                .align(Alignment.Center)
                .offset(y = (-28).dp)
                .clickable(enabled = false) {}
                .clip(RoundedCornerShape(28.dp))
                .background(
                    if (isNeo) Brush.verticalGradient(listOf(Color(0xFF080E1C), Color(0xFF050A14)))
                    else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
                )
                .border(
                    1.dp,
                    if (isNeo) Brush.linearGradient(listOf(Color(0xFF22D3EE).copy(0.6f), Color(0xFFA855F7).copy(0.4f)))
                    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.outline.copy(0.35f), MaterialTheme.colorScheme.outline.copy(0.15f))),
                    RoundedCornerShape(28.dp)
                )
        ) {
            Column(Modifier.fillMaxSize()) {
                // Drag handle
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), Alignment.Center) {
                    Box(Modifier.width(38.dp).height(4.dp).background(
                        if (isNeo) Color(0xFF22D3EE).copy(0.5f) else MaterialTheme.colorScheme.outline.copy(0.4f),
                        RoundedCornerShape(2.dp)))
                }
                // Header
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Close btn
                    Box(Modifier.size(34.dp).clip(CircleShape)
                        .background(if (isNeo) Color(0xFF22D3EE).copy(0.12f) else MaterialTheme.colorScheme.surfaceVariant)
                        .border(0.8.dp, if (isNeo) Color(0xFF22D3EE).copy(0.5f) else MaterialTheme.colorScheme.outline.copy(0.3f), CircleShape)
                        .clickable { onClose() }, Alignment.Center) {
                        Text("✕", fontSize = 13.sp, color = if (isNeo) Color(0xFF22D3EE) else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    }
                    Text(pageTitle.ifBlank { title }, Modifier.weight(1f), fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (isNeo) Color(0xFFE2E8F0) else MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
                    // Open in browser btn
                    Box(Modifier.size(34.dp).clip(CircleShape)
                        .background(if (isNeo) Brush.linearGradient(listOf(Color(0xFF22D3EE).copy(0.2f), Color(0xFFA855F7).copy(0.15f)))
                                    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(0.1f), MaterialTheme.colorScheme.primary.copy(0.1f))))
                        .border(0.8.dp, if (isNeo) Color(0xFF22D3EE).copy(0.6f) else MaterialTheme.colorScheme.outline.copy(0.4f), CircleShape)
                        .clickable { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} },
                        Alignment.Center) {
                        Text("↗", fontSize = 14.sp, color = if (isNeo) Color(0xFF22D3EE) else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                // Progress bar
                if (isPageLoading) {
                    Box(Modifier.fillMaxWidth().height(2.dp)
                        .background(if (isNeo) Color(0xFF0F172A) else MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(loadProgress.coerceIn(0f,1f))
                            .background(if (isNeo) Brush.horizontalGradient(listOf(Color(0xFF22D3EE), Color(0xFFA855F7)))
                                        else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary))))
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(
                        if (isNeo) Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF22D3EE).copy(0.35f), Color.Transparent))
                        else Brush.horizontalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.outline.copy(0.25f), Color.Transparent))))
                }
                // WebView
                AndroidView(factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageStarted(v: android.webkit.WebView, u: String, f: android.graphics.Bitmap?) { isPageLoading = true }
                            override fun onPageFinished(v: android.webkit.WebView, u: String) {
                                isPageLoading = false; pageTitle = v.title?.takeIf { it.isNotBlank() } ?: title
                            }
                            override fun shouldOverrideUrlLoading(v: android.webkit.WebView, r: android.webkit.WebResourceRequest) = false
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(v: android.webkit.WebView, p: Int) {
                                loadProgress = p / 100f; if (p >= 100) isPageLoading = false
                            }
                            override fun onReceivedTitle(v: android.webkit.WebView, t: String?) {
                                pageTitle = t?.takeIf { it.isNotBlank() } ?: title
                            }
                        }
                        loadUrl(url)
                    }
                }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// ─── TOP HEADER ───────────────────────────────────────────────────────
@Composable
fun TopHeader(isLoading: Boolean, cityName: String, lastUpdated: Long, isNeo: Boolean, onRefresh: () -> Unit, onNeoToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (isLoading) 360f else 0f,
        animationSpec = if (isLoading) infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart) else tween(0),
        label = "spin"
    )
    val timeStr = remember { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) }
    val dateStr = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()).uppercase() }

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color.Transparent)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                timeStr,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isNeo) Color(0xFF22D3EE) else MaterialTheme.colorScheme.primary,
                letterSpacing = (-0.5).sp
            )
            Text(dateStr, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = if (isNeo) Color(0xFF94A3B8) else MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (isNeo) {
                    val pulseAnim = rememberInfiniteTransition(label = "loc")
                    val pulse by pulseAnim.animateFloat(0.5f, 1f,
                        infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), "lp")
                    Box(Modifier.size(6.dp).background(Color(0xFFE879F9).copy(alpha = pulse), CircleShape))
                } else {
                    Icon(Icons.Filled.LocationOn, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp))
                }
                Text(cityName.ifBlank { "Your Location" }, fontSize = 12.sp,
                    color = if (isNeo) Color(0xFF94A3B8) else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Neo theme toggle button
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (isNeo)
                            Brush.linearGradient(listOf(Color(0xFF22D3EE), Color(0xFFA855F7)))
                        else
                            Brush.linearGradient(listOf(Color(0xFF94A3B8).copy(0.2f), Color(0xFF94A3B8).copy(0.1f)))
                    )
                    .clickable { onNeoToggle() },
                contentAlignment = Alignment.Center
            ) {
                Text("◈", fontSize = 14.sp,
                    color = if (isNeo) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Refresh
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(Icons.Filled.Refresh, "Refresh",
                    tint = if (isNeo) Color(0xFF22D3EE).copy(if (isLoading) 1f else 0.7f)
                           else if (isLoading) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp).rotate(rotation))
            }
        }
    }
}

// ─── TAB PILLS ────────────────────────────────────────────────────────
@Composable
fun TabPills(tabs: List<String>, selected: Int, isNeo: Boolean, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { i, label ->
            val sel = i == selected
            if (isNeo && sel) {
                // Gradient border active pill for Neo
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF22D3EE).copy(0.25f), Color(0xFFA855F7).copy(0.25f)))
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(listOf(Color(0xFF22D3EE), Color(0xFFA855F7))),
                            shape = RoundedCornerShape(50)
                        )
                        .clickable { onSelect(i) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF22D3EE), maxLines = 1)
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (sel && !isNeo) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .border(
                            if (sel && !isNeo) 0.dp else 1.dp,
                            if (isNeo) Color(0xFF22D3EE).copy(0.15f)
                            else if (sel) Color.Transparent
                            else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(50)
                        )
                        .clickable { onSelect(i) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel && !isNeo) MaterialTheme.colorScheme.onPrimary
                                else if (isNeo) Color(0xFF94A3B8)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1)
                }
            }
        }
    }
}

// ─── TAB 1: SUMMARY ───────────────────────────────────────────────────
@Composable
fun SummaryTab(state: BriefState, onRefresh: () -> Unit) {
    if (state.isLoading && state.lastUpdated == 0L) { WarmLoadingScreen("Crafting your brief..."); return }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {

        item {
            Text(state.summary.greeting.ifBlank { "Hello" }, fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary,
                lineHeight = 42.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        if (state.summary.daySummary.isNotBlank())
            item { AISummaryCard("YOUR DAY AT A GLANCE", "\uD83C\uDF05", state.summary.daySummary, MaterialTheme.colorScheme.primary) }
        if (state.weather.cityName != "Unknown" && state.weather.temperature != 0.0)
            item { SummaryWeatherInsightCard(state.weather) }
        if (state.summary.newsSummary.isNotBlank())
            item { AISummaryCard("NEWS DIGEST", "\uD83D\uDCF0", state.summary.newsSummary, Color(0xFF2196F3)) }
        item { SummaryVitalsDigestCard() }
        if (state.summary.quote.isNotBlank())
            item { QuoteCard(state.summary.quote) }

        val focus = state.summary.focus
        if (focus != null && focus.goal.isNotBlank()) item {
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
                CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer),
                CardDefaults.cardElevation(0.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TODAY'S FOCUS", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
                    Text(focus.goal, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (focus.steps.isNotBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        focus.steps.split(".").map { it.trim() }.filter { it.isNotBlank() }
                            .forEachIndexed { i, step ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${i+1}.", fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                    Text(step, fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f))
                                }
                            }
                    }
                }
            }
        }

        if (state.weather.airQualityLabel.isNotBlank()) item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.weather.airQualityEmoji, fontSize = 16.sp)
                Text("Air: ${state.weather.airQualityLabel}  |  Rise ${state.weather.sunrise}  Set ${state.weather.sunset}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        if (state.summary.music != null) item { MusicCard(state.summary.music) }
        if (state.summary.tips.isNotEmpty()) item { DailyTipsSection(state.summary.tips) }
        if (state.error != null) item { ErrorCard(state.error, onRefresh) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun AISummaryCard(label: String, icon: String, text: String, accentColor: Color) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
        CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 16.sp)
                Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accentColor, letterSpacing = 1.sp)
            }
            Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp)
        }
    }
}

@Composable
fun WeatherSummaryCard(weatherSummary: String, weatherAdvice: String, icon: String) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
        CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), CardDefaults.cardElevation(0.dp)) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center) { Text(icon, fontSize = 24.sp) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Text("TODAY'S WEATHER", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), letterSpacing = 1.sp)
                if (weatherSummary.isNotBlank())
                    Text(weatherSummary, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 20.sp)
                if (weatherAdvice.isNotBlank())
                    Box(Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("\uD83D\uDCA1 $weatherAdvice", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
                    }
            }
        }
    }
}

// ── Summary tab: Weather Insight card ────────────────────────────────────────
@Composable
fun SummaryWeatherInsightCard(weather: com.kakao.taxi.data.model.WeatherData) {
    var insight by remember { mutableStateOf("") }
    LaunchedEffect(weather.cityName, weather.condition) {
        if (weather.cityName == "Unknown") return@LaunchedEffect
        insight = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            buildWeatherInsight(weather)
        }
    }
    if (insight.isBlank()) return
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
        CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
        CardDefaults.cardElevation(0.dp)) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Text(weather.icon, fontSize = 22.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("\uD83E\uDDE0", fontSize = 13.sp)
                    Text("WEATHER INSIGHT", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
                }
                Text(insight, fontSize = 13.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// ── Summary tab: Vitals digest card ──────────────────────────────────────────
@Composable
fun SummaryVitalsDigestCard() {
    val context = LocalContext.current
    val hasActivity = remember {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACTIVITY_RECOGNITION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val stepCount = if (hasActivity) rememberStepCount() else -1
    val (totalScreenMin, _) = remember {
        if (hasUsageAccess(context)) getScreenTimeToday(context) else Pair(0L, emptyList<AppUsage>())
    }
    val caloriesBurned = if (stepCount > 0) (stepCount * 0.04f).toInt() else 0
    val distanceKm     = if (stepCount > 0) "%.1f".format(stepCount * 0.00078f) else "--"
    val scrH = totalScreenMin / 60; val scrM = totalScreenMin % 60
    val screenStr = if (totalScreenMin > 0)
        (if (scrH > 0) "${scrH}h ${scrM}m" else "${scrM}m") else "--"

    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
        CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
        CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("\uD83D\uDC9A", fontSize = 16.sp)
                Text("VITALS AT A GLANCE", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, color = Color(0xFF4CAF50))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(
                    Triple("\uD83D\uDC5F", if (stepCount > 0) "$stepCount" else "--", "steps"),
                    Triple("\uD83D\uDD25", if (stepCount > 0) "$caloriesBurned" else "--", "kcal"),
                    Triple("\uD83D\uDCCD", distanceKm, "km"),
                    Triple("\uD83D\uDCF1", screenStr, "screen")
                ).forEach { (emoji, value, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(emoji, fontSize = 18.sp)
                        Text(value, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                        Text(label, fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f),
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}


@Composable
fun QuoteCard(quote: String) {
    val parts = quote.split(" \u2014 ", " - ", "\u2014", "\u2013").map { it.trim() }
    val quoteText   = if (parts.size >= 2) "\"${parts[0]}\"" else "\"$quote\""
    val attribution = if (parts.size >= 2) "\u2014 ${parts[1]}" else ""
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
        CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer), CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(quoteText, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 24.sp,
                textAlign = TextAlign.Center, fontStyle = FontStyle.Italic)
            if (attribution.isNotBlank())
                Text(attribution, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun MusicCard(music: MusicRecommendation) {
    val tags = music.mood.split(" \u00b7 ", " / ", ", ").map { it.trim() }.filter { it.isNotBlank() }
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("MUSIC FOR YOUR MOOD", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
        Card(Modifier.fillMaxWidth().clickable {
                val q = Uri.encode("${music.title} ${music.artist}")
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$q"))) } catch (_: Exception) {}
            }, RoundedCornerShape(20.dp),
            CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), CardDefaults.cardElevation(0.dp)) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(
                    Brush.radialGradient(listOf(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primary)),
                    RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MusicNote, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(music.title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(music.artist, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (music.reason.isNotBlank())
                        Text(music.reason, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.take(2).forEach { tag ->
                            Box(Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 3.dp)) {
                                Text(tag, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Icon(Icons.Filled.PlayArrow, "Open in Spotify",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
fun DailyTipsSection(tips: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("DAILY TIPS", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
        tips.forEach { tip ->
            Box(Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 9.dp)) {
                Text(tip, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ErrorCard(error: String, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp),
        CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Couldn't load data", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error)
            Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onRetry) { Text("Retry", color = MaterialTheme.colorScheme.primary) }
        }
    }
}

// ─── ANIMATED SKY CANVAS ──────────────────────────────────────────────
@Composable
fun AnimatedSkyCanvas(modifier: Modifier = Modifier) {
    val tod = remember { getTimeOfDay() }
    val inf = rememberInfiniteTransition(label = "sky")

    val bodyProgress by inf.animateFloat(0f, 1f,
        infiniteRepeatable(tween(60_000, easing = LinearEasing)), "body")
    val cloudOffset  by inf.animateFloat(0f, 1f,
        infiniteRepeatable(tween(25_000, easing = LinearEasing)), "cloud")
    val twinkle      by inf.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), "twinkle")

    val skyTop: Color; val skyBot: Color; val bodyColor: Color; val glowColor: Color
    when (tod) {
        TimeOfDay.DAWN  -> { skyTop = Color(0xFF120820); skyBot = Color(0xFFF5913E); bodyColor = Color(0xFFFFD77A); glowColor = Color(0x80FFB347) }
        TimeOfDay.DAY   -> { skyTop = Color(0xFF2980B9); skyBot = Color(0xFF6FC3DF); bodyColor = Color(0xFFFFF176); glowColor = Color(0x60FFE082) }
        TimeOfDay.DUSK  -> { skyTop = Color(0xFF050A14); skyBot = Color(0xFFEF6C00); bodyColor = Color(0xFFFF8A65); glowColor = Color(0x80FF7043) }
        TimeOfDay.NIGHT -> { skyTop = Color(0xFF000008); skyBot = Color(0xFF050E2A); bodyColor = Color(0xFFEEF2FF); glowColor = Color(0x70B0C8FF) }
    }

    val stars = remember {
        (0..55).map { Triple((Math.random() * 1f).toFloat(), (Math.random() * 0.75f).toFloat(), Math.random().toFloat()) }
    }
    val clouds = remember {
        listOf(Triple(0.15f, 0.30f, 0.9f), Triple(0.60f, 0.20f, 1.2f), Triple(0.80f, 0.42f, 0.7f))
    }

    Canvas(modifier) {
        // Sky gradient
        drawRect(Brush.verticalGradient(listOf(skyTop, skyBot), 0f, size.height))

        // Stars
        if (tod == TimeOfDay.NIGHT || tod == TimeOfDay.DUSK) {
            stars.forEachIndexed { i, (sx, sy, phase) ->
                val alpha = ((sin(twinkle * PI * 2 + phase * PI * 2) + 1) / 2 * 0.85f + 0.15f).toFloat()
                val radius = when {
                    tod == TimeOfDay.NIGHT && i % 7 == 0 -> 3.5f
                    tod == TimeOfDay.NIGHT && i % 3 == 0 -> 2.2f
                    else -> 1.4f
                }
                val fx = sx * size.width; val fy = sy * size.height
                drawCircle(Color.White.copy(alpha = alpha), radius, Offset(fx, fy))
                if (tod == TimeOfDay.NIGHT && i % 7 == 0) {
                    val fa = alpha * 0.35f
                    drawLine(Color.White.copy(fa), Offset(fx - 7f, fy), Offset(fx + 7f, fy), 1f)
                    drawLine(Color.White.copy(fa), Offset(fx, fy - 7f), Offset(fx, fy + 7f), 1f)
                }
            }
        }

        // Sun / Moon arc
        val arcR   = size.width * 0.42f
        val cx     = size.width / 2f
        val baseY  = size.height * 0.90f
        val angle  = PI - bodyProgress * PI
        val bx     = (cx + arcR * cos(angle)).toFloat()
        val by     = (baseY - arcR * abs(sin(angle))).toFloat()
        val gr     = if (tod == TimeOfDay.NIGHT) 36f else 38f  // bigger moon

        drawCircle(Brush.radialGradient(listOf(glowColor, Color.Transparent),
            Offset(bx, by), gr * 3.2f), gr * 3.2f, Offset(bx, by))
        drawCircle(bodyColor, gr * 0.82f, Offset(bx, by))
        if (tod == TimeOfDay.NIGHT) {
            // crescent: dark circle offset to carve the crescent shape
            drawCircle(Color(0xFF000008), gr * 0.70f, Offset(bx + gr * 0.30f, by - gr * 0.15f))
        }

        // Clouds
        if (tod == TimeOfDay.DAY || tod == TimeOfDay.DAWN) {
            clouds.forEach { (fcx, fcy, scale) ->
                val rawX = (fcx + cloudOffset * 0.5f) % 1.2f - 0.1f
                val x = rawX * size.width; val y = fcy * size.height; val r = 22f * scale
                val cc = Color.White.copy(alpha = 0.75f)
                drawCircle(cc, r,        Offset(x,          y))
                drawCircle(cc, r * 0.8f, Offset(x - r*1.2f, y + r*0.3f))
                drawCircle(cc, r * 0.7f, Offset(x + r*1.1f, y + r*0.3f))
                drawCircle(cc, r * 0.9f, Offset(x + r*0.5f, y - r*0.1f))
            }
        }

        // Horizon glow
        val gh = size.height * 0.15f
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, glowColor.copy(alpha = 0.35f)),
            size.height - gh, size.height))
    }
}

// ═══════════════════════════════════════════════════════════════════════
// WEATHER ANIMATED ICON
// ═══════════════════════════════════════════════════════════════════════

private fun weatherIconType(icon: String, condition: String): String {
    val c = condition.lowercase()
    return when {
        "thunder" in c || "storm" in c || "\u26c8" in icon -> "storm"
        "snow" in c || "\u2744" in icon || "\uD83C\uDF28" in icon -> "snow"
        "fog" in c || "mist" in c || "haze" in c || "\uD83C\uDF2B" in icon -> "fog"
        "rain" in c || "drizzle" in c || "shower" in c || "\uD83C\uDF27" in icon || "\uD83D\uDCA7" in icon -> "rain"
        "cloud" in c || "overcast" in c || "\u2601" in icon || "\u26C5" in icon -> "cloudy"
        else -> "clear"
    }
}

@Composable
fun WeatherAnimIcon(icon: String, condition: String, modifier: Modifier = Modifier) {
    val type = remember(icon, condition) { weatherIconType(icon, condition) }
    val tod  = remember { getTimeOfDay() }
    // At night, "clear" becomes a moon scene, "cloudy" becomes night-cloud
    val effectiveType = when {
        type == "clear"  && (tod == TimeOfDay.NIGHT || tod == TimeOfDay.DUSK) -> "clear_night"
        type == "cloudy" && (tod == TimeOfDay.NIGHT || tod == TimeOfDay.DUSK) -> "cloudy_night"
        else -> type
    }
    val inf = rememberInfiniteTransition(label = "wicon")

    // shared timers
    val rot     by inf.animateFloat(0f, 360f,   infiniteRepeatable(tween(8000,  easing = LinearEasing)),            "rot")
    val pulse   by inf.animateFloat(0.92f, 1f,  infiniteRepeatable(tween(1200,  easing = FastOutSlowInEasing), RepeatMode.Reverse), "pulse")
    val drop    by inf.animateFloat(0f, 1f,     infiniteRepeatable(tween(1100,  easing = LinearEasing)),            "drop")
    val flash   by inf.animateFloat(0f, 1f,     infiniteRepeatable(tween(2400,  easing = LinearEasing)),            "flash")
    val drift   by inf.animateFloat(0f, 1f,     infiniteRepeatable(tween(3000,  easing = LinearEasing)),            "drift")
    val twinkle by inf.animateFloat(0f, 1f,     infiniteRepeatable(tween(900,   easing = FastOutSlowInEasing), RepeatMode.Reverse), "twinkle")

    // Stable random star positions for clear_night — must be outside Canvas
    val nightStars = remember { (0..18).map { Triple((Math.random()).toFloat(), (Math.random()).toFloat(), Math.random().toFloat()) } }

    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w / 2f;  val cy = h / 2f
        val r  = minOf(w, h) * 0.28f   // base body radius

        when (effectiveType) {

            // ── 🌙 CLEAR NIGHT: crescent moon + twinkling stars ───────────
            "clear_night" -> {
                nightStars.forEachIndexed { i, (sx, sy, ph) ->
                    val alpha = ((sin(twinkle * PI * 2 + ph * PI * 2) + 1) / 2 * 0.85f + 0.15f).toFloat()
                    drawCircle(Color.White.copy(alpha), if (i % 5 == 0) 2.8f else 1.4f, Offset(sx * w, sy * h))
                    if (i % 5 == 0) {
                        val fa = alpha * 0.3f
                        drawLine(Color.White.copy(fa), Offset(sx*w-5f, sy*h), Offset(sx*w+5f, sy*h), 1f)
                        drawLine(Color.White.copy(fa), Offset(sx*w, sy*h-5f), Offset(sx*w, sy*h+5f), 1f)
                    }
                }
                val moonR = r * 0.85f
                drawCircle(Brush.radialGradient(listOf(Color(0x50D0D8FF), Color.Transparent),
                    Offset(cx,cy), moonR*2.2f), moonR*2.2f, Offset(cx, cy))
                drawCircle(Color(0xFFEEF2FF), moonR, Offset(cx, cy))
                drawCircle(Color(0xFFCDD5F8), moonR*0.88f, Offset(cx, cy))
                drawCircle(Color(0xFF05101E), moonR*0.80f, Offset(cx + moonR*0.30f, cy - moonR*0.14f))
                drawCircle(Color(0xFFB8C4EE).copy(alpha=0.4f), moonR*0.14f, Offset(cx - moonR*0.22f, cy + moonR*0.18f))
                drawCircle(Color(0xFFB8C4EE).copy(alpha=0.2f), moonR*0.09f, Offset(cx - moonR*0.05f, cy - moonR*0.28f))
            }

            // ── 🌙⛅ CLOUDY NIGHT ──────────────────────────────────────────
            "cloudy_night" -> {
                val moonCx = cx - r*0.25f; val moonCy = cy - r*0.35f; val mr = r*0.55f
                drawCircle(Color(0xFFD0D8FF).copy(0.15f), mr*2f, Offset(moonCx, moonCy))
                drawCircle(Color(0xFFCDD5F8), mr, Offset(moonCx, moonCy))
                drawCircle(Color(0xFF05101E), mr*0.80f, Offset(moonCx + mr*0.28f, moonCy - mr*0.14f))
                val cldCx = cx+r*0.18f; val cldCy = cy+r*0.22f; val cr = r*0.52f
                val nc = Color(0xFF6B7A8D)
                drawCircle(nc, cr,         Offset(cldCx, cldCy))
                drawCircle(nc, cr*0.75f,   Offset(cldCx-cr*1.1f, cldCy+cr*0.3f))
                drawCircle(nc, cr*0.65f,   Offset(cldCx+cr*0.9f, cldCy+cr*0.3f))
                drawCircle(nc, cr*0.85f,   Offset(cldCx+cr*0.4f, cldCy-cr*0.2f))
            }

            // ── ☀️ CLEAR: pulsing sun + 8 rotating rays + two glow rings ─────
            "clear" -> {
                val glowR = r * 2.2f * pulse
                // outer glow ring
                drawCircle(Brush.radialGradient(listOf(Color(0x40FFC107), Color.Transparent),
                    Offset(cx,cy), glowR), glowR, Offset(cx, cy))
                // inner glow
                drawCircle(Brush.radialGradient(listOf(Color(0x80FFD54F), Color.Transparent),
                    Offset(cx,cy), r*1.4f), r*1.4f, Offset(cx, cy))
                // 8 rays
                rotate(rot, Offset(cx, cy)) {
                    repeat(8) { i ->
                        val a = (i * 45.0) * PI / 180.0
                        val r1 = r * 1.25f; val r2 = r * 1.75f
                        val sx = (cx + r1 * cos(a)).toFloat(); val sy = (cy + r1 * sin(a)).toFloat()
                        val ex = (cx + r2 * cos(a)).toFloat(); val ey = (cy + r2 * sin(a)).toFloat()
                        drawLine(Color(0xFFFFC107), Offset(sx,sy), Offset(ex,ey),
                            strokeWidth = if (i % 2 == 0) 4f else 2.5f, cap = StrokeCap.Round)
                    }
                }
                // disc
                drawCircle(Color(0xFFFFC107), r * pulse, Offset(cx, cy))
                drawCircle(Color(0xFFFFE082), r * 0.6f * pulse, Offset(cx, cy))
            }

            // ── ⛅ PARTLY CLOUDY: slow sun behind a cloud ─────────────────
            "cloudy" -> {
                val sunCx = cx - r * 0.3f; val sunCy = cy - r * 0.3f
                rotate(rot * 0.3f, Offset(sunCx, sunCy)) {
                    repeat(6) { i ->
                        val a = (i * 60.0) * PI / 180.0
                        val r1 = r * 0.9f; val r2 = r * 1.3f
                        drawLine(Color(0xAAFFC107),
                            Offset((sunCx + r1*cos(a)).toFloat(), (sunCy + r1*sin(a)).toFloat()),
                            Offset((sunCx + r2*cos(a)).toFloat(), (sunCy + r2*sin(a)).toFloat()),
                            strokeWidth = 3f, cap = StrokeCap.Round)
                    }
                }
                drawCircle(Color(0xFFFFC107), r * 0.7f, Offset(sunCx, sunCy))
                // cloud body
                val cldCx = cx + r * 0.15f; val cldCy = cy + r * 0.2f; val cr = r * 0.55f
                drawCircle(Color(0xFFE0E0E0), cr,        Offset(cldCx,           cldCy))
                drawCircle(Color(0xFFE0E0E0), cr * 0.75f, Offset(cldCx - cr*1.1f, cldCy + cr*0.3f))
                drawCircle(Color(0xFFE0E0E0), cr * 0.65f, Offset(cldCx + cr*0.9f, cldCy + cr*0.3f))
                drawCircle(Color(0xFFE0E0E0), cr * 0.85f, Offset(cldCx + cr*0.4f, cldCy - cr*0.2f))
            }

            // ── 🌧 RAIN: grey cloud + 5 staggered falling drops ──────────
            "rain" -> {
                val cr = r * 0.52f; val cldCx = cx; val cldCy = cy - r * 0.3f
                drawCircle(Color(0xFF90A4AE), cr,         Offset(cldCx,            cldCy))
                drawCircle(Color(0xFF90A4AE), cr * 0.75f, Offset(cldCx - cr*1.1f,  cldCy + cr*0.3f))
                drawCircle(Color(0xFF90A4AE), cr * 0.65f, Offset(cldCx + cr*0.9f,  cldCy + cr*0.3f))
                drawCircle(Color(0xFF90A4AE), cr * 0.80f, Offset(cldCx + cr*0.35f, cldCy - cr*0.2f))
                // 5 rain drops with offset phases
                val drops = listOf(
                    Pair(-cr*1.0f, 0.00f), Pair(-cr*0.4f, 0.20f), Pair(cr*0.2f, 0.45f),
                    Pair(cr*0.8f,  0.65f), Pair(-cr*0.7f, 0.80f)
                )
                drops.forEach { (dx, phase) ->
                    val t = ((drop + phase) % 1f)
                    val startY = cldCy + cr * 0.8f
                    val endY   = cldCy + cr * 0.8f + r * 1.1f
                    val dropY  = startY + (endY - startY) * t
                    val alpha  = if (t < 0.8f) 1f else (1f - t) / 0.2f
                    drawLine(Color(0xFF42A5F5).copy(alpha = alpha * 0.9f),
                        Offset(cx + dx, dropY - r * 0.15f), Offset(cx + dx, dropY),
                        strokeWidth = 3.5f, cap = StrokeCap.Round)
                }
            }

            // ── ⛈ STORM: menacing dark cloud + thick forked bolt + rain ──
            "storm" -> {
                val cr = r * 0.54f; val cldCx = cx; val cldCy = cy - r * 0.22f

                // Dark threatening cloud with depth layers
                drawCircle(Color(0xFF37474F), cr * 1.05f, Offset(cldCx, cldCy))
                drawCircle(Color(0xFF455A64), cr * 0.80f, Offset(cldCx - cr*1.05f, cldCy + cr*0.28f))
                drawCircle(Color(0xFF37474F), cr * 0.70f, Offset(cldCx + cr*0.90f, cldCy + cr*0.28f))
                drawCircle(Color(0xFF546E7A), cr * 0.88f, Offset(cldCx + cr*0.38f, cldCy - cr*0.22f))
                // inner dark highlight
                drawCircle(Color(0xFF263238), cr * 0.55f, Offset(cldCx - cr*0.1f, cldCy + cr*0.05f))

                // Screen flash glow when bolt fires
                val boltOn = flash < 0.12f || (flash > 0.18f && flash < 0.26f)

                // Main bolt path — define early so flash glow can use bBot position
                val bTop  = Offset(cx + r*0.08f,  cldCy + cr * 0.55f)
                val bMid1 = Offset(cx - r*0.28f,  cldCy + cr * 0.55f + r * 0.38f)
                val bMid2 = Offset(cx + r*0.18f,  cldCy + cr * 0.55f + r * 0.60f)
                val bBot  = Offset(cx - r*0.12f,  cldCy + cr * 0.55f + r * 1.05f)
                val forkEnd = Offset(cx + r*0.55f, cldCy + cr * 0.55f + r * 0.95f)
                val boltMidY = (bTop.y + bBot.y) / 2f

                if (boltOn) {
                    // Atmospheric radial glow centered on bolt midpoint — NO rectangle
                    val flashCenter = Offset(cx, boltMidY)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color(0x55FFFF88), Color(0x22FFEE44), Color.Transparent),
                            center = flashCenter,
                            radius = w * 0.75f
                        ),
                        radius = w * 0.75f,
                        center = flashCenter
                    )
                    // Bright core flash right at bolt
                    drawCircle(Color(0x30FFFFFF), w * 0.35f, flashCenter)
                }

                val boltAlpha = if (boltOn) 1f else 0.08f

                // Outer glow (wide, soft)
                if (boltOn) {
                    val glowPaint = Color(0x80FFEE00)
                    listOf(
                        bTop to bMid1, bMid1 to bMid2, bMid2 to bBot
                    ).forEach { (a, b) -> drawLine(glowPaint, a, b, strokeWidth = 22f, cap = StrokeCap.Round) }
                    drawLine(Color(0x50FFEE00), bMid2, forkEnd, strokeWidth = 14f, cap = StrokeCap.Round)
                }

                // Mid glow (medium)
                if (boltOn) {
                    val mgPaint = Color(0xAAFFFF55)
                    listOf(bTop to bMid1, bMid1 to bMid2, bMid2 to bBot).forEach { (a, b) ->
                        drawLine(mgPaint, a, b, strokeWidth = 12f, cap = StrokeCap.Round)
                    }
                }

                // Core bright bolt
                val coreColor = Color(0xFFFFEE00).copy(alpha = boltAlpha)
                listOf(bTop to bMid1, bMid1 to bMid2, bMid2 to bBot).forEach { (a, b) ->
                    drawLine(coreColor, a, b, strokeWidth = 6f, cap = StrokeCap.Round)
                }
                // White hot center
                val hotColor = Color.White.copy(alpha = boltAlpha * 0.9f)
                listOf(bTop to bMid1, bMid1 to bMid2, bMid2 to bBot).forEach { (a, b) ->
                    drawLine(hotColor, a, b, strokeWidth = 2.5f, cap = StrokeCap.Round)
                }
                // Fork branch (slightly thinner)
                drawLine(Color(0xFFFFEE00).copy(alpha = boltAlpha * 0.8f), bMid2, forkEnd, strokeWidth = 4f, cap = StrokeCap.Round)
                drawLine(Color.White.copy(alpha = boltAlpha * 0.7f), bMid2, forkEnd, strokeWidth = 1.8f, cap = StrokeCap.Round)

                // Rain drops under cloud
                val rainDrops = listOf(
                    Pair(-cr*1.0f, 0.00f), Pair(-cr*0.4f, 0.18f), Pair(cr*0.6f, 0.40f),
                    Pair(cr*1.0f,  0.60f), Pair(-cr*0.65f, 0.78f), Pair(cr*0.25f, 0.88f)
                )
                rainDrops.forEach { (dx, phase) ->
                    val t     = ((drop + phase) % 1f)
                    val startY = cldCy + cr * 0.75f
                    val dropY  = startY + (r * 1.2f) * t
                    val alpha  = if (t < 0.75f) 0.75f else (1f - t) / 0.25f * 0.75f
                    drawLine(Color(0xFF78C0E0).copy(alpha = alpha),
                        Offset(cx + dx, dropY - r * 0.16f), Offset(cx + dx, dropY),
                        strokeWidth = 3f, cap = StrokeCap.Round)
                }
            }

            // ── ❄ SNOW: blue-grey cloud + 5 drifting snowflakes ──────────
            "snow" -> {
                val cr = r * 0.50f; val cldCx = cx; val cldCy = cy - r * 0.30f
                drawCircle(Color(0xFFB0BEC5), cr,         Offset(cldCx,            cldCy))
                drawCircle(Color(0xFFB0BEC5), cr * 0.75f, Offset(cldCx - cr*1.1f,  cldCy + cr*0.3f))
                drawCircle(Color(0xFFB0BEC5), cr * 0.65f, Offset(cldCx + cr*0.9f,  cldCy + cr*0.3f))
                drawCircle(Color(0xFFB0BEC5), cr * 0.80f, Offset(cldCx + cr*0.35f, cldCy - cr*0.2f))
                // snowflakes
                val flakes = listOf(
                    Triple(-cr*0.9f, 0.00f, 0.0f), Triple(-cr*0.2f, 0.22f, 30f),
                    Triple(cr*0.5f,  0.45f, 60f),  Triple(cr*1.0f,  0.68f, 15f),
                    Triple(-cr*0.5f, 0.80f, 45f)
                )
                flakes.forEach { (dx, phase, extraRot) ->
                    val t     = ((drift + phase) % 1f)
                    val startY = cldCy + cr * 0.9f
                    val flakeY = startY + (r * 1.2f) * t
                    val flakeX = cx + dx + sin(t * PI * 2 + phase * PI).toFloat() * r * 0.15f
                    val alpha  = if (t < 0.75f) 1f else (1f - t) / 0.25f
                    rotate(rot * 0.5f + extraRot, Offset(flakeX, flakeY)) {
                        repeat(3) { arm ->
                            val a = (arm * 60.0) * PI / 180.0
                            val sr = r * 0.13f
                            drawLine(Color(0xFFB3E5FC).copy(alpha = alpha),
                                Offset((flakeX - sr*cos(a)).toFloat(), (flakeY - sr*sin(a)).toFloat()),
                                Offset((flakeX + sr*cos(a)).toFloat(), (flakeY + sr*sin(a)).toFloat()),
                                strokeWidth = 3f, cap = StrokeCap.Round)
                        }
                        drawCircle(Color(0xFFE1F5FE).copy(alpha = alpha), r*0.055f, Offset(flakeX, flakeY))
                    }
                }
            }

            // ── 🌫 FOG: 4 drifting horizontal bands ───────────────────────
            "fog" -> {
                val bands = listOf(
                    Triple(0.25f, 0.00f, 0.80f), Triple(0.42f, 0.25f, 0.90f),
                    Triple(0.58f, 0.50f, 0.75f), Triple(0.73f, 0.75f, 0.85f)
                )
                bands.forEach { (relY, phase, widthFrac) ->
                    val t      = ((drift + phase) % 1f)
                    val shiftX = (t - 0.5f) * w * 0.35f
                    val bandY  = h * relY
                    val bw     = w * widthFrac
                    val alpha  = 0.55f + 0.25f * sin(t * PI * 2 + phase * PI * 2).toFloat()
                    drawRoundRect(
                        color     = Color(0xFFB0BEC5).copy(alpha = alpha),
                        topLeft   = Offset(cx - bw/2f + shiftX, bandY - h*0.035f),
                        size      = Size(bw, h * 0.07f),
                        cornerRadius = CornerRadius(100f)
                    )
                }
            }
        }

        // subtle twinkle sparkles for clear sky
        if (type == "clear") {
            val sparkles = listOf(Pair(r*1.6f, -r*0.8f), Pair(-r*1.5f, r*0.4f), Pair(r*0.2f, -r*1.4f))
            sparkles.forEachIndexed { i, (dx, dy) ->
                val alpha = ((sin(twinkle * PI * 2 + i * 1.2) + 1) / 2 * 0.8f).toFloat().coerceIn(0f, 1f)
                drawCircle(Color.White.copy(alpha = alpha), 3f, Offset(cx + dx, cy + dy))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// SUN ARC CARD  (full replacement)
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun SunArcCard(sunrise: String, sunset: String) {
    val tod  = remember { getTimeOfDay() }
    val hour = remember { currentHour() }
    val now  = remember { Calendar.getInstance() }
    val sdf  = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    // Parse sunrise/sunset to fractional hours (e.g. 6:30 AM → 6.5)
    fun parseHour(s: String): Float {
        return try {
            val c = Calendar.getInstance(); c.time = sdf.parse(s)!!
            c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f
        } catch (_: Exception) { if ("am" in s.lowercase()) 6f else 18f }
    }
    val sunriseH = remember { parseHour(sunrise) }
    val sunsetH  = remember { parseHour(sunset) }
    val nowH     = remember { now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f }

    val isNight  = tod == TimeOfDay.NIGHT
    val rawProg  = if (isNight) 1f else ((nowH - sunriseH) / (sunsetH - sunriseH)).coerceIn(0f, 1f)
    val animProg by animateFloatAsState(rawProg, tween(1400, easing = FastOutSlowInEasing), label = "arcP")

    // Countdown label
    val label = remember {
        when {
            isNight -> "Nighttime \u2022 Sunrise at $sunrise"
            nowH >= sunsetH -> "Nighttime \u2022 Sunrise at $sunrise"
            else -> {
                val minLeft = ((sunsetH - nowH) * 60).toInt()
                val h = minLeft / 60; val m = minLeft % 60
                "${if (h > 0) "${h}h " else ""}${m}m until sunset"
            }
        }
    }

    val inf = rememberInfiniteTransition(label = "arcInf")
    val sparkle by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), "sparkle")
    val glowPulse by inf.animateFloat(0.85f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), "glow")
    val starTwink by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(800,  easing = FastOutSlowInEasing), RepeatMode.Reverse), "st")

    // Night sky stars (stable random positions)
    val stars = remember { (0..18).map { Triple((Math.random()).toFloat(), (Math.random()*0.7f).toFloat(), Math.random().toFloat()) } }

    val bgTop    = if (isNight) Color(0xFF000008) else Color(0xFFE3F2FD)
    val bgBot    = if (isNight) Color(0xFF060F2A) else Color(0xFFFFF8E1)
    val bodyCol  = if (isNight) Color(0xFFEEF2FF) else Color(0xFFFFC107)
    val glowCol  = if (isNight) Color(0x60B0C8FF) else Color(0x80FFD54F)
    val trackCol = if (isNight) Color(0x40FFFFFF) else Color(0x30FFA726)
    val fillCol  = if (isNight) Color(0x50B0C8FF) else Color(0xAAFFC107)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(24.dp),
        colors   = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(bgTop, bgBot)), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ── Arc canvas ───────────────────────────────────────────
                Canvas(modifier = Modifier.fillMaxWidth().height(130.dp)) {
                    val w = size.width; val h = size.height
                    val margin = 36f
                    val arcCx = w / 2f; val arcBaseline = h - 12f
                    val arcR = (w / 2f) - margin
                    val topLeft = Offset(arcCx - arcR, arcBaseline - arcR)
                    val arcSz  = Size(arcR * 2, arcR * 2)

                    // Night stars
                    if (isNight) {
                        stars.forEach { (sx, sy, ph) ->
                            val a = ((sin(starTwink * PI * 2 + ph * PI * 2) + 1) / 2 * 0.75f + 0.25f).toFloat()
                            drawCircle(Color.White.copy(alpha = a), if (ph > 0.7f) 2.5f else 1.5f,
                                Offset(sx * w, sy * h * 0.85f))
                        }
                    }

                    // Track arc (full semicircle)
                    drawArc(trackCol, 180f, 180f, false, topLeft, arcSz,
                        style = Stroke(3f, cap = StrokeCap.Round))

                    // Filled arc up to progress
                    if (animProg > 0f)
                        drawArc(fillCol, 180f, animProg * 180f, false, topLeft, arcSz,
                            style = Stroke(5f, cap = StrokeCap.Round))

                    // Sun/moon body position on arc
                    val arcAngle = PI + animProg * PI
                    val bx = (arcCx + arcR * cos(arcAngle)).toFloat()
                    val by = (arcBaseline + arcR * sin(arcAngle)).toFloat()

                    // Outer glow halo
                    val gr = 28f * glowPulse
                    drawCircle(Brush.radialGradient(listOf(glowCol, Color.Transparent),
                        Offset(bx,by), gr * 2.2f), gr * 2.2f, Offset(bx, by))

                    // Body
                    drawCircle(bodyCol, gr * 0.72f, Offset(bx, by))
                    if (!isNight) {
                        // inner bright spot
                        drawCircle(Color(0xFFFFECB3), gr * 0.38f, Offset(bx, by))
                    } else {
                        // crescent shadow
                        drawCircle(Color(0xFF000008), gr * 0.70f, Offset(bx + gr*0.28f, by - gr*0.14f))
                    }

                    // Shimmer sparkles around sun (day only)
                    if (!isNight) {
                        repeat(5) { i ->
                            val sp = (sparkle + i * 0.2f) % 1f
                            val sa = (sp * PI * 2 + i * 0.8).toFloat()
                            val sd = gr * (1.4f + sp * 0.8f)
                            val sx2 = bx + sd * cos(sa.toDouble()).toFloat()
                            val sy2 = by + sd * sin(sa.toDouble()).toFloat()
                            val salpha = (1f - sp).coerceIn(0f, 1f) * 0.7f
                            drawCircle(Color(0xFFFFEE58).copy(alpha = salpha), 3.5f, Offset(sx2, sy2))
                        }
                    }

                    // Horizon baseline
                    drawLine(trackCol, Offset(arcCx - arcR, arcBaseline), Offset(arcCx + arcR, arcBaseline),
                        strokeWidth = 2f)

                    // Sunrise dot (left end)
                    drawCircle(Color(0xFFFFA726), 6f, Offset(arcCx - arcR, arcBaseline))
                    // Sunset dot (right end)
                    drawCircle(Color(0xFFEF5350), 6f, Offset(arcCx + arcR, arcBaseline))
                }

                // ── Sunrise / Sunset labels ───────────────────────────
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("SUNRISE", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = if (isNight) Color.White.copy(0.5f) else Color(0xFFFFA726))
                        Text(sunrise, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = if (isNight) Color.White.copy(0.9f) else Color(0xFF5D4037))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, fontSize = 11.sp, textAlign = TextAlign.Center,
                            color = if (isNight) Color.White.copy(0.65f) else Color(0xFF795548),
                            lineHeight = 15.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("SUNSET", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = if (isNight) Color.White.copy(0.5f) else Color(0xFFEF5350))
                        Text(sunset, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = if (isNight) Color.White.copy(0.9f) else Color(0xFF5D4037))
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// TAB 2: WEATHER
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun WeatherTab(weather: WeatherData, isLoading: Boolean) {
    val hasData = weather.cityName != "Unknown" && weather.temperature != 0.0
    if (!hasData) {
        if (isLoading) { WarmLoadingScreen("Fetching weather..."); return }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) {
            val context = LocalContext.current
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(32.dp)) {
                Text("\uD83C\uDF24\uFE0F", fontSize = 56.sp)
                Text("Weather Unavailable", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("Could not get your location. Please grant location permission.",
                    fontSize = 13.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
                Button(onClick = {
                    try { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", context.packageName, null))) } catch (_: Exception) {}
                }) { Text("Open Settings") }
            }
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Hero card with animated sky background ─────────────────────
        item {
            Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(28.dp))) {
                AnimatedSkyCanvas(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)), startY = 70f)))
                Row(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Filled.LocationOn, null, tint = Color.White.copy(0.85f), modifier = Modifier.size(13.dp))
                            Text(weather.cityName, fontSize = 13.sp, color = Color.White.copy(0.85f), fontWeight = FontWeight.Medium)
                        }
                        Text("${weather.temperature.toInt()}\u00b0C", fontSize = 56.sp,
                            fontWeight = FontWeight.ExtraBold, color = Color.White, lineHeight = 60.sp)
                        Text(weather.condition, fontSize = 15.sp, color = Color.White.copy(0.85f))
                        Text("Feels like ${weather.feelsLike.toInt()}\u00b0C", fontSize = 12.sp, color = Color.White.copy(0.65f))
                    }
                    // Canvas-drawn animated weather icon in hero
                    WeatherAnimIcon(weather.icon, weather.condition,
                        modifier = Modifier.size(100.dp).padding(bottom = 8.dp))
                }
            }
        }

        // ── Weather Insight banner — shown at very top ─────────────────
        item {
            var wInsight by remember { mutableStateOf("") }
            LaunchedEffect(weather.cityName) {
                if (weather.cityName == "Unknown") return@LaunchedEffect
                wInsight = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { buildWeatherInsight(weather) }
            }
            if (wInsight.isNotBlank()) Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
                CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), CardDefaults.cardElevation(0.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("\uD83E\uDDE0", fontSize = 16.sp)
                        Text("WEATHER INSIGHT", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(wInsight, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // ── Detail grid: all 6 cards ───────────────────────────────────
        item { WeatherGrid(weather) }

        // ── Full SunArcCard (replaces old sunrise/sunset row) ──────────
        item { SunArcCard(weather.sunrise, weather.sunset) }

        // ── Air quality ────────────────────────────────────────────────
        if (weather.airQualityLabel.isNotBlank()) item {
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
                CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                CardDefaults.cardElevation(0.dp)) {
                Row(Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // AQI dot
                    val aqiColor = when (weather.airQualityLabel.lowercase()) {
                        "good"      -> Color(0xFF43A047)
                        "fair"      -> Color(0xFFFDD835)
                        "poor"      -> Color(0xFFFF7043)
                        "very poor" -> Color(0xFFD32F2F)
                        else        -> Color(0xFF90A4AE)
                    }
                    Box(Modifier.size(18.dp).background(aqiColor, RoundedCornerShape(50)))
                    Column(Modifier.weight(1f)) {
                        Text("Air Quality", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f))
                        Text(weather.airQualityLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text("AQI ${weather.airQualityIndex}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ─── WEATHER GRID (6 cards: humidity, wind, rain, uv, visibility, feels like) ──
@Composable
fun WeatherGrid(weather: WeatherData) {
    // Data: (label, value, icon-type for canvas)
    data class GridItem(val label: String, val value: String, val emoji: String)
    val items = listOf(
        GridItem("Humidity",    "${weather.humidity}%",              "\uD83D\uDCA7"),
        GridItem("Wind",        "${weather.windSpeed.toInt()} km/h", "\uD83D\uDCA8"),
        GridItem("Rain Chance", "${weather.rainChance}%",            "\uD83C\uDF27"),
        GridItem("UV Index",    uvLabel(weather.uvIndex),            "\u2600\uFE0F"),
        GridItem("Visibility",  "${weather.visibility.toInt()} km",  "\uD83D\uDC41\uFE0F"),
        GridItem("Feels Like",  "${weather.feelsLike.toInt()}\u00b0C", "\uD83C\uDF21\uFE0F")
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEachIndexed { rowIdx, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { colIdx, item ->
                    val delay = (rowIdx * 2 + colIdx) * 90
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { kotlinx.coroutines.delay(delay.toLong()); visible = true }
                    val animAlpha  by animateFloatAsState(if (visible) 1f else 0f, tween(320), label = "ga$rowIdx$colIdx")
                    val animOffset by animateFloatAsState(if (visible) 0f else 28f, tween(320), label = "go$rowIdx$colIdx")
                    Card(
                        modifier = Modifier.weight(1f).graphicsLayer { alpha = animAlpha; translationY = animOffset },
                        shape    = RoundedCornerShape(18.dp),
                        colors   = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.emoji, fontSize = 24.sp)
                            Text(item.value, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(item.label, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }
}

fun uvLabel(uv: Int) = when {
    uv <= 2  -> "$uv \u00b7 Low"
    uv <= 5  -> "$uv \u00b7 Moderate"
    uv <= 7  -> "$uv \u00b7 High"
    uv <= 10 -> "$uv \u00b7 Very High"
    else     -> "$uv \u00b7 Extreme"
}

// ─── TAB 3: NEWS ──────────────────────────────────────────────────────
@Composable
fun NewsTab(news: List<NewsArticle>, isLoading: Boolean, isNeo: Boolean = false, onRefresh: () -> Unit = {}, onArticleClick: (String) -> Unit) {
    if (isLoading && news.isEmpty()) { WarmLoadingScreen("Loading latest news..."); return }
    val isError = news.isNotEmpty() && news.all { it.category == "System" }
    if (news.isEmpty() || isError) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 32.dp)) {
                Text("\uD83D\uDCE1", fontSize = 48.sp)
                Text(if (isError) "News unavailable" else "No news loaded yet",
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(if (isError) "Check your internet connection and try again."
                     else "Tap below to load today's top stories.",
                    fontSize = 14.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
                Spacer(Modifier.height(4.dp))
                Button(onRefresh) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("Retry")
                }
            }
        }
        return
    }

    // AI state
    var searchQuery    by remember { mutableStateOf("") }
    var aiAnswer       by remember { mutableStateOf("") }
    var aiLoading      by remember { mutableStateOf(false) }
    val scope          = rememberCoroutineScope()

    val grouped = news.groupBy { it.category }

    // Smart trending topics derived from headlines
    val trendingTopics: List<String> = remember(news) {
        extractTrendingTopics(news)
    }
    var trendingQuery by remember { mutableStateOf("") }

    val emojis  = mapOf("World" to "\uD83C\uDF0D", "Technology" to "\uD83D\uDCBB",
        "Business" to "\uD83D\uDCC8", "Sports" to "\u26BD", "Science" to "\uD83D\uDD2C",
        "Entertainment" to "\uD83C\uDFAC", "General" to "\uD83D\uDCF0", "Health" to "\uD83C\uDFE5",
        "Top News" to "\uD83D\uDDDE\uFE0F", "Politics" to "\uD83C\uDFDB\uFE0F", "Environment" to "\uD83C\uDF3F")

    val neoBlue   = Color(0xFF22D3EE)
    val neoPurple = Color(0xFFA855F7)
    val neoText   = Color(0xFFE2E8F0)
    val neoMuted  = Color(0xFF94A3B8)
    val neoBg     = Color(0xFF080E1C)

    Box(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
    ) {
        LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {

            // News digest summary banner
            if (news.isNotEmpty()) item {
                val headlines = news.take(3).joinToString(" \u00b7 ") { it.title.take(50) }
                Card(Modifier.fillMaxWidth().padding(bottom = 6.dp), RoundedCornerShape(18.dp),
                    CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                    CardDefaults.cardElevation(0.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("\uD83D\uDCF0", fontSize = 14.sp)
                            Text("TODAY'S DIGEST", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp, color = Color(0xFF2196F3))
                        }
                        Text(headlines, fontSize = 12.sp, lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // AI answer card
            if (aiAnswer.isNotBlank() || aiLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        // Glow behind card
                        if (isNeo) Box(Modifier.fillMaxWidth().height(80.dp).align(Alignment.Center)
                            .background(Brush.radialGradient(listOf(neoBlue.copy(0.18f), Color.Transparent))))
                        Box(Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isNeo) Brush.linearGradient(listOf(Color(0xFF070D1A), Color(0xFF0A1020)))
                                        else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)))
                            .border(1.dp, if (isNeo) Brush.linearGradient(listOf(neoBlue.copy(0.7f), neoPurple.copy(0.5f), neoBlue.copy(0.3f)))
                                          else Brush.linearGradient(listOf(MaterialTheme.colorScheme.outline.copy(0.35f), MaterialTheme.colorScheme.outline.copy(0.2f))),
                                    RoundedCornerShape(20.dp))
                        ) {
                            // Cyan top edge glow
                            if (isNeo) Box(Modifier.fillMaxWidth().height(1.5.dp)
                                .background(Brush.horizontalGradient(listOf(Color.Transparent, neoBlue.copy(0.8f), neoPurple.copy(0.6f), Color.Transparent))))
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Pulsing dot
                                    if (aiLoading) {
                                        val inf = rememberInfiniteTransition(label = "pulse")
                                        val alpha by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), "a")
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(neoBlue.copy(alpha)))
                                    } else {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (isNeo) neoBlue else MaterialTheme.colorScheme.primary))
                                    }
                                    Text(if (aiLoading) "THINKING..." else "SMART ANSWER",
                                        fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp,
                                        color = if (isNeo) neoBlue else MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.weight(1f))
                                    // Dismiss
                                    Text("✕", fontSize = 11.sp, color = if (isNeo) neoMuted else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable { aiAnswer = ""; aiLoading = false })
                                }
                                if (aiAnswer.isNotBlank()) {
                                    // Thin separator
                                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(
                                        if (isNeo) Brush.horizontalGradient(listOf(neoBlue.copy(0.4f), neoPurple.copy(0.3f), Color.Transparent))
                                        else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.outline.copy(0.4f), Color.Transparent))))
                                    Text(aiAnswer, fontSize = 13.sp, lineHeight = 21.sp,
                                        color = if (isNeo) neoText else MaterialTheme.colorScheme.onSurface,
                                        fontFamily = if (isNeo) FontFamily.Default else FontFamily.Default)
                                }
                            }
                        }
                    }
                }
            }

            // Trending topics chips
            if (trendingTopics.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🔥 TRENDING TODAY", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.55f),
                            modifier = Modifier.padding(horizontal = 4.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            items(trendingTopics) { topic ->
                                val isSelected = trendingQuery == topic
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else if (isNeo) Color(0xFF22D3EE).copy(0.1f)
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .border(0.8.dp,
                                            if (isNeo) Color(0xFF22D3EE).copy(0.35f) else MaterialTheme.colorScheme.outline.copy(0.2f),
                                            RoundedCornerShape(50))
                                        .clickable {
                                            if (isSelected) {
                                                trendingQuery = ""
                                            } else {
                                                trendingQuery = topic
                                                searchQuery = topic
                                                aiLoading = true; aiAnswer = ""
                                                scope.launch { aiAnswer = smartNewsAnswer(topic, news); aiLoading = false }
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 7.dp)
                                ) {
                                    Text(topic, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                else if (isNeo) Color(0xFF22D3EE)
                                                else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            grouped.forEach { (cat, articles) ->
                item {
                    // Category header
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isNeo) neoBlue.copy(0.07f) else MaterialTheme.colorScheme.primary.copy(0.05f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (isNeo) neoBlue.copy(0.15f) else MaterialTheme.colorScheme.primary.copy(0.1f))
                            .border(0.7.dp, if (isNeo) neoBlue.copy(0.4f) else Color.Transparent, RoundedCornerShape(8.dp)),
                            Alignment.Center) {
                            Text(emojis[cat] ?: "\uD83D\uDCF0", fontSize = 15.sp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(cat.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.2.sp, color = if (isNeo) neoBlue else MaterialTheme.colorScheme.primary)
                            Text("${articles.size} stories", fontSize = 10.sp,
                                color = if (isNeo) neoMuted else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.weight(1f).height(1.dp).background(
                            if (isNeo) Brush.horizontalGradient(listOf(neoBlue.copy(0.3f), Color.Transparent))
                            else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.outline.copy(0.35f), Color.Transparent))))
                    }
                }
                item {
                    // News cards block
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(if (isNeo) Brush.linearGradient(listOf(Color(0xFF080E1C).copy(0.97f), Color(0xFF050A14).copy(0.97f)))
                                    else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)))
                        .border(1.dp, if (isNeo) Brush.linearGradient(listOf(neoBlue.copy(0.5f), neoPurple.copy(0.3f), neoBlue.copy(0.15f)))
                                      else Brush.linearGradient(listOf(MaterialTheme.colorScheme.outline.copy(0.3f), MaterialTheme.colorScheme.outline.copy(0.15f))),
                                RoundedCornerShape(18.dp))) {
                        if (isNeo) Box(Modifier.fillMaxWidth().height(1.5.dp)
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, neoBlue.copy(0.45f), Color.Transparent))))
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                            articles.forEachIndexed { idx, article ->
                                NewsCard(article, isNeo) { onArticleClick(article.url) }
                                if (idx < articles.lastIndex)
                                    HorizontalDivider(color = if (isNeo) neoBlue.copy(0.1f) else MaterialTheme.colorScheme.outline.copy(0.25f), thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // Floating AI search bar
        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 52.dp).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(if (isNeo) Brush.linearGradient(listOf(Color(0xFF0F172A).copy(0.97f), Color(0xFF080E1E).copy(0.97f)))
                            else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface.copy(0.97f), MaterialTheme.colorScheme.surface.copy(0.97f))))
                .border(1.dp, if (isNeo) Brush.linearGradient(listOf(neoBlue.copy(0.6f), neoPurple.copy(0.5f)))
                              else Brush.linearGradient(listOf(MaterialTheme.colorScheme.outline.copy(0.5f), MaterialTheme.colorScheme.outline.copy(0.5f))),
                        RoundedCornerShape(28.dp))) {
                Box(Modifier.fillMaxWidth().height(1.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(0.1f), Color.Transparent))))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (aiLoading) "⚡" else "\uD83D\uDD0D", fontSize = 16.sp)
                    androidx.compose.foundation.text.BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 13.sp,
                            color = if (isNeo) neoText else MaterialTheme.colorScheme.onSurface,
                            fontFamily = if (isNeo) FontFamily.Monospace else FontFamily.Default
                        ),
                        decorationBox = { inner ->
                            Box { if (searchQuery.isEmpty()) Text("Ask about today's news...", fontSize = 13.sp,
                                color = if (isNeo) neoMuted else MaterialTheme.colorScheme.onSurfaceVariant); inner() }
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                            if (searchQuery.isNotBlank() && !aiLoading) {
                                val q = searchQuery; aiLoading = true; aiAnswer = ""
                                scope.launch { aiAnswer = smartNewsAnswer(q, news); aiLoading = false }
                            }
                        })
                    )
                    Box(Modifier.size(36.dp).clip(CircleShape)
                        .background(if (isNeo) Brush.linearGradient(listOf(neoBlue, neoPurple))
                                    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary)))
                        .clickable(enabled = searchQuery.isNotBlank() && !aiLoading) {
                            if (searchQuery.isNotBlank()) {
                                val q = searchQuery; aiLoading = true; aiAnswer = ""
                                scope.launch { aiAnswer = smartNewsAnswer(q, news); aiLoading = false }
                            }
                        }, Alignment.Center) {
                        Text("↑", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

// ─── UPGRADED NEWS CARD ───────────────────────────────────────────────
@Composable
fun NewsCard(article: NewsArticle, isNeo: Boolean = false, onClick: () -> Unit) {
    val neoBlue  = Color(0xFF22D3EE)
    val neoText  = Color(0xFFE2E8F0)
    val neoMuted = Color(0xFF94A3B8)
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(article.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (isNeo) neoText else MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
            if (article.description.isNotBlank())
                Text(article.description, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp, color = if (isNeo) neoMuted else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(4.dp))
                    .background(if (isNeo) neoBlue.copy(0.1f) else MaterialTheme.colorScheme.primary.copy(0.1f))
                    .border(if (isNeo) 0.5.dp else 0.dp, if (isNeo) neoBlue.copy(0.4f) else Color.Transparent, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(article.source, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                        color = if (isNeo) neoBlue else MaterialTheme.colorScheme.primary)
                }
                if (article.publishedAt.isNotBlank()) {
                    Text("·", fontSize = 10.sp, color = if (isNeo) neoMuted.copy(0.5f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f))
                    Text(article.publishedAt, fontSize = 10.sp, color = if (isNeo) neoMuted else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f))
                }
            }
        }
        // Arrow btn
        Box(Modifier.size(30.dp).clip(CircleShape)
            .background(if (isNeo) Brush.linearGradient(listOf(neoBlue.copy(0.12f), Color(0xFFA855F7).copy(0.08f)))
                        else Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(0.07f), MaterialTheme.colorScheme.primary.copy(0.07f))))
            .border(0.8.dp, if (isNeo) neoBlue.copy(0.5f) else MaterialTheme.colorScheme.outline.copy(0.3f), CircleShape)
            .clickable { onClick() }, Alignment.Center) {
            Text("→", fontSize = 13.sp, color = if (isNeo) neoBlue else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

// ─── SMART NEWS AI: DuckDuckGo Instant + Wikipedia + Rule-based ─────────
// ─── SMART AI: DuckDuckGo + Wikipedia Search + Wikipedia Summary + News ──
//
// Pipeline (all free, no API key):
//  1. DuckDuckGo Instant Answer  → direct facts, calculator, conversions
//  2. Wikipedia OpenSearch       → find the canonical page title for the query
//  3. Wikipedia REST summary     → fetch intro paragraph of that page
//  4. DuckDuckGo Related Topics  → fallback entity info
//  5. Loaded news headlines      → match & cite today's articles
//  Offline fallback: rule-based scoring over loaded news
// ─────────────────────────────────────────────────────────────────────────
suspend fun smartNewsAnswer(query: String, news: List<NewsArticle>): String =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {

        fun get(urlStr: String, timeoutMs: Int = 5000): org.json.JSONObject? = try {
            val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "NowBriefApp/1.0 (Android; educational)")
            conn.setRequestProperty("Accept-Language", "en")
            if (conn.responseCode == 200)
                org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            else null
        } catch (_: Exception) { null }

        fun getArr(urlStr: String, timeoutMs: Int = 5000): org.json.JSONArray? = try {
            val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "NowBriefApp/1.0 (Android; educational)")
            conn.setRequestProperty("Accept-Language", "en")
            if (conn.responseCode == 200)
                org.json.JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            else null
        } catch (_: Exception) { null }

        fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

        val newsHit   = ruleBasedNewsAnswer(query, news)
        val hasNews   = !newsHit.startsWith("No matches") && !newsHit.startsWith("No articles") &&
                        !newsHit.startsWith("No articles")
        val newsSnip  = if (hasNews) "\n\n📰 From today's news: $newsHit" else ""

        // ── Step 1: DuckDuckGo Instant Answer ─────────────────────────────
        val ddgJson = get("https://api.duckduckgo.com/?q=${encode(query)}&format=json&no_html=1&skip_disambig=1")
        if (ddgJson != null) {
            val answer     = ddgJson.optString("Answer", "").trim()
            val abstract_  = ddgJson.optString("AbstractText", "").trim()
            val definition = ddgJson.optString("Definition", "").trim()
            val infobox    = ddgJson.optJSONObject("Infobox")

            // Direct answer (calc, conversion, time, etc.)
            if (answer.isNotBlank() && answer.length < 300) {
                return@withContext "💡 $answer$newsSnip"
            }

            // Infobox — structured entity data (person, place, film…)
            if (infobox != null) {
                val content = infobox.optJSONArray("content")
                if (content != null && content.length() > 0) {
                    val fields = (0 until minOf(content.length(), 5)).mapNotNull { i ->
                        val obj = content.optJSONObject(i) ?: return@mapNotNull null
                        val lbl = obj.optString("label", "").trim()
                        val val_ = obj.optString("value", "").trim()
                        if (lbl.isNotBlank() && val_.isNotBlank()) "$lbl: $val_" else null
                    }
                    if (fields.isNotEmpty()) {
                        val entity = ddgJson.optString("Heading", query)
                        val box    = fields.joinToString(" · ")
                        val abs    = if (abstract_.length > 60) "\n" + abstract_.take(220) + if (abstract_.length > 220) "…" else "" else ""
                        return@withContext "📋 $entity — $box$abs$newsSnip"
                    }
                }
            }

            // Abstract paragraph
            if (abstract_.length > 60) {
                val src   = ddgJson.optString("AbstractSource", "")
                val snip  = abstract_.take(300) + if (abstract_.length > 300) "…" else ""
                val srcLbl = if (src.isNotBlank()) " (via $src)" else ""
                return@withContext "$snip$srcLbl$newsSnip"
            }

            // Definition
            if (definition.length > 30) {
                val src   = ddgJson.optString("DefinitionSource", "")
                val srcLbl = if (src.isNotBlank()) " (via $src)" else ""
                return@withContext "${definition.take(260)}$srcLbl$newsSnip"
            }

            // Related topics fallback
            val related = ddgJson.optJSONArray("RelatedTopics")
            if (related != null && related.length() > 0) {
                val tips = (0 until minOf(related.length(), 3)).mapNotNull { i ->
                    related.optJSONObject(i)?.optString("Text", "")?.trim()
                        ?.takeIf { it.length > 20 }?.take(120)
                }.joinToString("\n• ", prefix = "• ")
                if (tips.isNotBlank()) return@withContext "Related to \"$query\":\n$tips$newsSnip"
            }
        }

        // ── Step 2: Wikipedia OpenSearch → find canonical title ────────────
        val searchArr = getArr("https://en.wikipedia.org/w/api.php?action=opensearch&search=${encode(query)}&limit=1&namespace=0&format=json")
        val wikiTitle = if (searchArr != null && searchArr.length() > 1) {
            val titles = searchArr.optJSONArray(1)
            if (titles != null && titles.length() > 0) titles.optString(0, "") else ""
        } else ""

        // ── Step 3: Wikipedia REST summary using resolved title ────────────
        val titleToFetch = wikiTitle.ifBlank { query }
        val wikiJson = get("https://en.wikipedia.org/api/rest_v1/page/summary/${encode(titleToFetch)}")
        if (wikiJson != null) {
            val type    = wikiJson.optString("type", "")
            if (type != "disambiguation") {
                val extract = wikiJson.optString("extract", "").trim()
                val title   = wikiJson.optString("title", "").trim()
                val desc    = wikiJson.optString("description", "").trim()

                if (extract.length > 80) {
                    // Trim to 2 sentences max for readability
                    val sentences = extract.split(Regex("(?<=[.!?])\\s+"))
                    val twoSent   = sentences.take(3).joinToString(" ").take(340)
                    val suffix    = if (extract.length > 340) "…" else ""
                    val descPart  = if (desc.isNotBlank() && !extract.startsWith(desc)) " ($desc)" else ""
                    return@withContext "📖 $title$descPart — $twoSent$suffix$newsSnip"
                }
            }
        }

        // ── Step 4: Wikipedia search API (broader fallback) ────────────────
        val searchJson = get("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encode(query)}&srlimit=1&srprop=snippet&format=json")
        if (searchJson != null) {
            val searchItems = searchJson.optJSONObject("query")?.optJSONArray("search")
            if (searchItems != null && searchItems.length() > 0) {
                val item    = searchItems.getJSONObject(0)
                val title   = item.optString("title", "").trim()
                val snippet = item.optString("snippet", "").trim()
                    .replace(Regex("<[^>]+>"), "")   // strip HTML tags
                if (snippet.length > 30) {
                    return@withContext "📖 $title — $snippet…$newsSnip"
                }
            }
        }

        // ── Step 5: Offline news only ──────────────────────────────────────
        newsHit
    }


// ─── WEATHER INSIGHT (no API key) ─────────────────────────────────────────
fun buildWeatherInsight(weather: com.kakao.taxi.data.model.WeatherData): String {
    val sb = StringBuilder()
    val temp   = weather.temperature.toInt()
    val feels  = weather.feelsLike.toInt()
    val uv     = weather.uvIndex
    val rain   = weather.rainChance
    val humid  = weather.humidity
    val wind   = weather.windSpeed.toInt()
    val cond   = weather.condition.lowercase()
    val hour   = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

    // Temperature feel
    sb.append(when {
        temp >= 38 -> "🥵 Dangerously hot — stay indoors and hydrate heavily."
        temp >= 32 -> "☀️ Very hot today. Limit outdoor activity, especially midday."
        temp >= 26 -> "😎 Warm and pleasant. Good for outdoor activities with sunscreen."
        temp >= 18 -> "🌤️ Comfortable temperature today."
        temp >= 10 -> "🧥 Cool out there — a light jacket is recommended."
        temp >= 0  -> "🥶 Cold day. Dress in warm layers."
        else       -> "❄️ Freezing conditions — minimize outdoor exposure."
    })

    // Feels-like gap
    if (kotlin.math.abs(feels - temp) >= 4) {
        sb.append(" Feels ${if (feels < temp) "$feels°C with wind chill" else "$feels°C due to humidity"}.")
    }

    // Rain advice
    if (rain >= 70) sb.append(" 🌧️ High rain chance ($rain%) — carry an umbrella.")
    else if (rain >= 40) sb.append(" 🌦️ Possible showers ($rain%) — keep an umbrella handy.")

    // UV advice
    if (hour in 9..17) {
        when {
            uv >= 8 -> sb.append(" ⚠️ Very high UV ($uv) — wear SPF 50+, hat, and sunglasses.")
            uv >= 6 -> sb.append(" 🕶️ High UV ($uv) — sunscreen recommended.")
            uv >= 3 -> sb.append(" 🌞 Moderate UV — sunscreen if outdoors for long.")
        }
    }

    // Wind advice
    if (wind >= 50) sb.append(" 💨 Strong winds ($wind km/h) — secure loose items.")
    else if (wind >= 30) sb.append(" 🍃 Breezy ($wind km/h) — good for a walk.")

    // Humidity comfort
    if (humid >= 80 && temp >= 25) sb.append(" 💧 High humidity makes it feel muggy — stay hydrated.")
    else if (humid < 30) sb.append(" 🏜️ Low humidity — use moisturiser and drink extra water.")

    // Condition-specific
    when {
        "fog" in cond || "mist" in cond -> sb.append(" 🌫️ Foggy conditions — drive slowly and use fog lights.")
        "snow" in cond || "blizzard" in cond -> sb.append(" ❄️ Snowy — roads may be slippery.")
        "thunder" in cond || "storm" in cond -> sb.append(" ⚡ Storm warning — avoid open areas.")
        "clear" in cond && hour in 18..22 -> sb.append(" 🌠 Clear night ahead — great for stargazing!")
    }

    // Fetch a relevant Wikipedia fact if clear
    val wikiTip = try {
        val query = when {
            uv >= 6 -> "UV index skin protection"
            rain >= 50 -> "rain weather safety tips"
            temp >= 35 -> "heat stroke prevention"
            temp <= 5 -> "hypothermia prevention cold weather"
            "thunder" in cond -> "lightning safety"
            else -> null
        }
        if (query != null) {
            val enc  = java.net.URLEncoder.encode(query, "UTF-8")
            val conn = java.net.URL("https://en.wikipedia.org/api/rest_v1/page/summary/$enc")
                .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "NowBriefApp/1.0")
            if (conn.responseCode == 200) {
                val j = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val ex = j.optString("extract", "").trim()
                if (ex.length > 60) {
                    val sent = ex.split(Regex("(?<=[.!?])\\s+")).firstOrNull()?.take(140) ?: ""
                    if (sent.isNotBlank()) "\n\n📖 $sent" else ""
                } else ""
            } else ""
        } else ""
    } catch (_: Exception) { "" }

    return sb.toString().trim() + wikiTip
}

// ─── HEALTH TIP (context-aware, no API key) ───────────────────────────────
fun buildHealthTip(steps: Int, sleepH: Float, water: Int, hour: Int): String {
    // Priority order: most urgent issue first
    val tips = mutableListOf<String>()

    // Sleep
    when {
        sleepH in 0.1f..4.9f -> tips.add("😴 You got ${sleepH.toInt()}h of sleep — less than 5h impairs focus. Try to nap or sleep earlier tonight.")
        sleepH in 5f..6f      -> tips.add("🌙 ${sleepH.toInt()}h sleep detected. Aim for 7–8h tonight for better recovery.")
        sleepH >= 9f          -> tips.add("🛌 Over 9h of sleep may indicate fatigue. Consistent 7–8h is ideal.")
    }

    // Hydration relative to time of day
    val expectedWater = when {
        hour < 10  -> 2
        hour < 14  -> 4
        hour < 18  -> 6
        else       -> 7
    }
    if (water < expectedWater - 1)
        tips.add("💧 You've had $water glasses but should have ~$expectedWater by now. Drink a glass soon!")

    // Steps
    when {
        steps in 1..2000 && hour >= 12 -> tips.add("🚶 Only $steps steps so far. A short 10-min walk now adds ~1,000 steps.")
        steps in 2001..5000 && hour >= 15 -> tips.add("👟 Halfway to 8k steps. A 20-min walk will get you there!")
        steps >= 8000 -> tips.add("🎉 Goal reached with ${steps} steps! Keep moving — every step counts.")
    }

    // Time-of-day tip
    val timeTip = when (hour) {
        in 6..8   -> "🌅 Morning tip: 10 min of sunlight exposure boosts serotonin and sets your sleep cycle."
        in 12..13 -> "🥗 Lunch time: a protein-rich meal sustains energy better than high-carb options."
        in 14..16 -> "😪 Afternoon slump? A 20-min nap or brisk walk beats coffee for sustained alertness."
        in 21..23 -> "📵 Wind-down hour: dim lights and avoid screens 30 min before bed for better sleep quality."
        else      -> ""
    }
    if (timeTip.isNotBlank()) tips.add(timeTip)

    // Return most urgent, or combine two if short
    return when {
        tips.isEmpty() -> "✅ You're on track! Keep up the hydration and movement."
        tips.size == 1 -> tips[0]
        else -> tips[0] + "\n" + tips[1]
    }
}

// ─── TRENDING TOPICS extractor ────────────────────────────────────────────
fun extractTrendingTopics(news: List<com.kakao.taxi.data.model.NewsArticle>): List<String> {
    if (news.isEmpty()) return emptyList()
    val stopWords = setOf(
        "the","a","an","and","or","but","in","on","at","to","for","of","with","as","is","are","was","were",
        "it","its","this","that","these","those","by","from","has","have","had","be","been","will","would",
        "could","should","may","might","than","then","into","out","after","before","over","under","about",
        "up","down","new","say","says","said","also","just","more","most","some","other","their","they",
        "he","she","his","her","him","we","us","our","you","your","i","my","me","now","not","no","so","do"
    )
    val freq = mutableMapOf<String, Int>()
    news.forEach { article ->
        val words = (article.title + " " + article.description)
            .lowercase()
            .split(Regex("[^a-z0-9']+"))
            .filter { it.length >= 4 && it !in stopWords && !it.all { c -> c.isDigit() } }
        // bigrams too (e.g. "elon musk", "climate change")
        words.forEach { w -> freq[w] = (freq[w] ?: 0) + 1 }
        words.zipWithNext { a, b ->
            val bigram = "$a $b"
            if (a !in stopWords && b !in stopWords) freq[bigram] = (freq[bigram] ?: 0) + 2
        }
    }
    // Capitalise and return top 8 by frequency
    return freq.entries
        .filter { it.value >= 2 }
        .sortedByDescending { it.value }
        .take(8)
        .map { it.key.split(" ").joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercaseChar() } } }
}

// ─── RULE-BASED NEWS AI (works offline, no quota) ─────────────────────
fun ruleBasedNewsAnswer(query: String, news: List<NewsArticle>): String {
    if (news.isEmpty()) return "No articles loaded yet. Pull to refresh."
    val q = query.lowercase().trim()
    if (q.contains("how many") || q.startsWith("count")) {
        val cats = news.groupBy { it.category }
        return "Loaded ${news.size} articles across ${cats.size} categories: " +
            cats.entries.joinToString(", ") { "${it.value.size} ${it.key}" } + "."
    }
    val topKeywords = setOf("latest","top","news","headlines","summary","happening","today","recent","breaking")
    val isTopQuery = q.split(" ").all { w -> topKeywords.any { k -> w.startsWith(k) || k.startsWith(w) } } || q.length < 5
    if (isTopQuery) {
        val top = news.take(5)
        val parts = top.mapIndexed { i, a -> "${i+1}. ${a.title} (${a.source})" }
        return "Top ${top.size} stories:\n" + parts.joinToString("\n")
    }
    val stopWords = setOf("a","an","the","is","are","was","were","in","on","at","to","for",
        "of","and","or","but","with","about","what","who","which","when","where","how","any","tell","me","show","give","find")
    val tokens = q.split(Regex("[^a-z0-9]")).filter { it.length > 2 && it !in stopWords }
    if (tokens.isEmpty()) {
        val top = news.take(2)
        val latest = top.joinToString(" | ") { "${it.title} — ${it.source}" }
        return "Latest: $latest"
    }
    data class Hit(val article: NewsArticle, val score: Int)
    val hits = news.mapNotNull { a ->
        val score = tokens.sumOf { tok ->
            when {
                a.title.lowercase().contains(tok) -> 3
                a.category.lowercase().contains(tok) -> 2
                a.description.lowercase().contains(tok) -> 1
                else -> 0
            }
        }
        if (score > 0) Hit(a, score) else null
    }.sortedByDescending { it.score }
    if (hits.isEmpty()) {
        val catHit = news.filter { it.category.lowercase().contains(tokens.first()) }
        return if (catHit.isNotEmpty())
            "Found ${catHit.size} ${catHit.first().category} stories. Top: ${catHit.first().title} (${catHit.first().source})."
        else "No matches for $query in today's ${news.size} articles. Try a broader keyword."
    }
    return when {
        hits.size == 1 -> {
            val a = hits[0].article
            val desc = if (a.description.isNotBlank()) " — " + a.description.take(110) else ""
            "On $query: ${a.title}$desc (${a.source})."
        }
        hits.size <= 3 -> {
            "Found ${hits.size} results for $query: " +
            hits.mapIndexed { i, h -> "${i+1}. ${h.article.title} (${h.article.source})" }.joinToString("; ") + "."
        }
        else -> {
            val top = hits.take(3)
            "Found ${hits.size} articles about $query. Top: " +
            top.mapIndexed { i, h -> "${i+1}. ${h.article.title} (${h.article.source})" }.joinToString("; ") +
            " and ${hits.size - 3} more."
        }
    }
}

// ─── OLD NEWS CARD (removed - replaced above) ─────────────────────────
// ─── TAB 4: VITALS

// ═══════════════════════════════════════════════════════════════════════
// TAB 4: VITALS  (Steps + Screen Time)
// ═══════════════════════════════════════════════════════════════════════

// ── Helpers ────────────────────────────────────────────────────────────

private fun hasUsageAccess(context: Context): Boolean {
    return try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode   = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), context.packageName
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }
}

private data class AppUsage(val packageName: String, val appName: String, val minutesUsed: Long, val icon: android.graphics.drawable.Drawable?)

// Packages that are NOT real "screen time" — launchers, system UI, input, etc.
private val SYSTEM_PACKAGE_BLOCKLIST = setOf(
    "com.android.systemui",
    "com.android.launcher",
    "com.android.launcher2",
    "com.android.launcher3",
    "com.sec.android.app.launcher",          // Samsung One UI Home
    "com.google.android.apps.nexuslauncher", // Pixel launcher
    "com.oneplus.launcher",
    "com.miui.home",
    "com.huawei.android.launcher",
    "com.oppo.launcher",
    "com.vivo.launcher",
    "com.zte.mifavor.launcher",
    "com.android.inputmethod.latin",
    "com.samsung.android.inputmethod",
    "com.google.android.inputmethod.latin",
    "com.android.phone",
    "com.android.server.telecom",
    "com.samsung.android.dialer",
    "com.android.settings",
    "com.samsung.android.settings",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.android.permissioncontroller",
    "android",
    "com.android.keyguard",
    "com.google.android.gms",               // Play Services (background)
    "com.android.vending",
)

private fun isSystemApp(pm: android.content.pm.PackageManager, pkg: String): Boolean {
    return try {
        val info = pm.getApplicationInfo(pkg, 0)
        // FLAG_SYSTEM = pre-installed system app
        (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: Exception) { false }
}

/**
 * Precise screen time using queryEvents — same method as Digital Wellbeing.
 * Sums ACTIVITY_RESUMED→ACTIVITY_PAUSED pairs per package since midnight.
 * Filters out launchers, system UI, and other non-user apps.
 */
private fun getScreenTimeToday(context: Context): Pair<Long, List<AppUsage>> {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return Pair(0L, emptyList())
    val pm = context.packageManager

    // Since midnight today
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val startMs = cal.timeInMillis
    val endMs   = System.currentTimeMillis()

    // Collect raw events
    val events  = usm.queryEvents(startMs, endMs)
    val ev      = android.app.usage.UsageEvents.Event()

    // Map: packageName → total foreground ms
    val foregroundMs = mutableMapOf<String, Long>()
    // Map: packageName → last resume timestamp
    val resumeAt     = mutableMapOf<String, Long>()

    while (events.hasNextEvent()) {
        events.getNextEvent(ev)
        val pkg = ev.packageName ?: continue
        when (ev.eventType) {
            android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                resumeAt[pkg] = ev.timeStamp
            }
            android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
            android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED -> {
                val start = resumeAt.remove(pkg) ?: continue
                val dur   = ev.timeStamp - start
                if (dur > 0) foregroundMs[pkg] = (foregroundMs[pkg] ?: 0L) + dur
            }
        }
    }

    // Close any still-open sessions (app currently in foreground)
    resumeAt.forEach { (pkg, start) ->
        val dur = endMs - start
        if (dur > 0) foregroundMs[pkg] = (foregroundMs[pkg] ?: 0L) + dur
    }

    // Filter: remove blocklisted, system, and self; require >30s
    val userApps = foregroundMs
        .filter { (pkg, ms) ->
            ms >= 30_000L &&
            pkg != context.packageName &&
            pkg !in SYSTEM_PACKAGE_BLOCKLIST &&
            !isSystemApp(pm, pkg)
        }
        .toList()
        .sortedByDescending { it.second }

    // Total = sum of all user app foreground time
    val totalMs  = userApps.sumOf { it.second }
    val totalMin = totalMs / 60_000L

    // Top 8 apps for the bar chart
    val topApps = userApps.take(8).mapNotNull { (pkg, ms) ->
        try {
            val info  = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(info).toString()
            val icon  = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
            AppUsage(pkg, label, ms / 60_000L, icon)
        } catch (_: Exception) { null }
    }

    return Pair(totalMin, topApps)
}

// ── Ring Chart ─────────────────────────────────────────────────────────

@Composable
fun AnimatedRingChart(
    progress: Float,
    label: String,
    valueText: String,
    subText: String,
    ringColor: Color,
    modifier: Modifier = Modifier
) {
    val inf     = rememberInfiniteTransition(label = "ring")
    val pulse   by inf.animateFloat(0.7f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), "pulse")

    val animP by animateFloatAsState(
        targetValue   = progress.coerceIn(0f, 1f),
        animationSpec = tween(1400, easing = FastOutSlowInEasing),
        label         = "ringP"
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.095f
            val pad    = stroke / 2f + 6f
            val tl     = Offset(pad, pad)
            val sz     = Size(size.width - pad * 2, size.height - pad * 2)
            val r      = (size.minDimension / 2f) - pad

            // Track ring (light grey)
            drawArc(ringColor.copy(alpha = 0.12f), -90f, 360f, false, tl, sz,
                style = Stroke(stroke, cap = StrokeCap.Round))

            // Filled arc (solid color, no sweep gradient bug)
            if (animP > 0.01f) {
                drawArc(ringColor, -90f, animP * 360f, false, tl, sz,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }

            // Glowing tip dot that pulses
            if (animP > 0.01f) {
                val tipAngle = (-PI / 2.0 + animP * PI * 2.0)
                val tx = (center.x + r * cos(tipAngle)).toFloat()
                val ty = (center.y + r * sin(tipAngle)).toFloat()
                // outer glow
                drawCircle(ringColor.copy(alpha = 0.30f * pulse), stroke * 1.0f, Offset(tx, ty))
                // inner bright dot
                drawCircle(Color.White, stroke * 0.40f, Offset(tx, ty))
                drawCircle(ringColor.copy(alpha = 0.9f), stroke * 0.28f, Offset(tx, ty))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(valueText, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Text(subText, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.65f),
                textAlign = TextAlign.Center, lineHeight = 13.sp)
        }
    }
}

// ── Mini bar for app usage ─────────────────────────────────────────────

@Composable
private fun AppUsageBar(app: AppUsage, maxMinutes: Long, index: Int) {
    val frac = (app.minutesUsed.toFloat() / maxMinutes.toFloat()).coerceIn(0f, 1f)
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay((index * 60).toLong()); visible = true }
    val animFrac by animateFloatAsState(if (visible) frac else 0f,
        tween(600, easing = FastOutSlowInEasing), label = "bar$index")
    val animAlpha by animateFloatAsState(if (visible) 1f else 0f, tween(300), label = "ba$index")

    val hue = (index * 37f) % 360f
    val barColor = Color.hsv(hue, 0.55f, 0.90f)

    Row(
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = animAlpha },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // App name
        Text(app.appName, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(90.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)

        // Bar track
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.outline.copy(0.15f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(animFrac)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(barColor.copy(0.6f), barColor))))
        }

        // Time label
        val h = app.minutesUsed / 60; val m = app.minutesUsed % 60
        Text(if (h > 0) "${h}h ${m}m" else "${m}m", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp),
            textAlign = TextAlign.End)
    }
}

// ── Step counter (SensorManager live listener) ─────────────────────────

@Composable
fun rememberStepCount(): Int {
    val context = LocalContext.current
    var steps by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val sm     = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { steps = e.values[0].toInt() }
            override fun onAccuracyChanged(s: Sensor, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm?.unregisterListener(listener) }
    }
    return steps
}

// ── Main VitalsTab ─────────────────────────────────────────────────────

@Composable
fun VitalsTab() {
    val context = LocalContext.current
    val hasActivity = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    }
    val hasUsage  = remember { hasUsageAccess(context) }

    // ── Health Connect — primary source; sensor is fallback ──────────
    val hcRepo: com.kakao.taxi.data.repository.HealthConnectRepository = remember {
        org.koin.java.KoinJavaComponent.get(com.kakao.taxi.data.repository.HealthConnectRepository::class.java)
    }
    var hcSnap by remember { mutableStateOf<com.kakao.taxi.data.repository.HealthConnectRepository.HealthSnapshot?>(null) }
    var hcAvailable by remember { mutableStateOf(false) }

    // ── DataStore & coroutines — must be declared BEFORE hcPermLauncher ──
    val dataStoreRepo = remember { DataStoreRepository(context.dataStore) }
    val coroutineScope = rememberCoroutineScope()

    // HC permission launcher per guide Step 4:
    // Uses PermissionController.createRequestPermissionResultContract() exactly
    val hcPermLauncher = rememberLauncherForActivityResult(
        contract = androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions ->
        if (grantedPermissions.containsAll(hcRepo.requiredPermissions)) {
            // All permissions granted — fetch snapshot
            coroutineScope.launch { hcSnap = hcRepo.snapshot() }
        }
        // Partial grants: snapshot() handles missing perms gracefully
        else if (grantedPermissions.isNotEmpty()) {
            coroutineScope.launch { hcSnap = hcRepo.snapshot() }
        }
    }

    // Sensor step fallback
    val sensorSteps = if (hasActivity) rememberStepCount() else -1

    // Effective stepCount: HC if available, else sensor (-1 = no data)
    val stepCount: Int = when {
        hcSnap != null && (hcSnap!!.steps) >= 0L -> hcSnap!!.steps.toInt()
        else -> sensorSteps
    }

    val (totalScreenMin, topApps) = remember {
        if (hasUsage) getScreenTimeToday(context) else Pair(0L, emptyList<AppUsage>())
    }

    val hour: Int  = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val stepGoal  = 8000f
    val screenGoal = 120f   // 2h daily goal
    val stepFrac   = if (stepCount > 0) (stepCount / stepGoal).coerceIn(0f, 1f) else 0f
    val screenFrac = (totalScreenMin / screenGoal).coerceIn(0f, 1f)

    // Per guide Step 3+4: check availability then permissions on every open
    LaunchedEffect(Unit) {
        hcAvailable = hcRepo.isAvailable
        if (!hcAvailable) return@LaunchedEffect
        // Check if all required permissions already granted
        if (hcRepo.hasPermissions()) {
            hcSnap = hcRepo.snapshot()
        }
        // If not granted, the permission row in the UI will trigger hcPermLauncher
    }

    // ── Sleep onboarding state ─────────────────────────────────────────
    var sleepOnboardDone    by remember { mutableStateOf(true) }   // assume done; LaunchedEffect corrects
    var showSleepOnboard    by remember { mutableStateOf(false) }
    var sleepTargetHours    by remember { mutableStateOf(7.5f) }
    var sleepBehaviors      by remember { mutableStateOf(listOf<String>()) }
    // onboarding wizard step (0=avg time, 1=behaviors)
    var sleepOnboardStep    by remember { mutableStateOf(0) }
    var sleepOnboardTarget  by remember { mutableStateOf(7.5f) }
    var sleepOnboardBehaviors by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        sleepOnboardDone = dataStoreRepo.isSleepOnboardDone()
        if (!sleepOnboardDone) showSleepOnboard = true
        val (t, b) = dataStoreRepo.loadSleepOnboarding()
        sleepTargetHours = t
        sleepBehaviors   = b
    }

    // ── Smart sleep detection ─────────────────────────────────────────
    // Strategy (multi-signal like Samsung Health):
    //  1. UsageStats screen events  → longest night screen-off window
    //  2. Charging signal           → phones often charge overnight
    //  3. Persisted session         → started by screen-off at bedtime hour
    //  4. Live "currently sleeping" countdown if session active
    var estimatedSleepH    by remember { mutableStateOf(0f) }
    var isSleepingNow      by remember { mutableStateOf(false) }
    var sleepSessionStart  by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        // Check if a sleep session is currently active (screen was off at night)
        val sessionActive = dataStoreRepo.isSleepSessionActive()
        val sessionStart  = dataStoreRepo.getSleepSessionStart()
        if (sessionActive && sessionStart > 0L) {
            val elapsedH = (System.currentTimeMillis() - sessionStart) / (1000f * 60 * 60)
            if (elapsedH < 16f) {           // sanity cap
                isSleepingNow     = true
                sleepSessionStart = sessionStart
                estimatedSleepH   = elapsedH
                return@LaunchedEffect
            } else {
                // Stale session — close it
                dataStoreRepo.endSleepSession(System.currentTimeMillis())
            }
        }

        // Try to load today's saved sleep from history
        val todaySaved = dataStoreRepo.loadTodaySleepHours()
        if (todaySaved > 0f) {
            estimatedSleepH = todaySaved
            return@LaunchedEffect
        }

        // Derive sleep from UsageStats if permission granted
        estimatedSleepH = if (!hasUsage) {
            when {
                hour in 6..9   -> sleepTargetHours
                hour in 10..13 -> (sleepTargetHours - 0.5f).coerceAtLeast(0f)
                else           -> 0f
            }
        } else {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val lookbackMs = 18 * 60 * 60 * 1000L   // look back 18h
                val events = usm.queryEvents(now - lookbackMs, now)
                val ev = android.app.usage.UsageEvents.Event()

                // Collect ALL screen-off / screen-on timestamps so we can find the longest gap
                val offTimes = mutableListOf<Long>()
                val onTimes  = mutableListOf<Long>()

                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    when (ev.eventType) {
                        android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE     -> onTimes.add(ev.timeStamp)
                        android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE -> offTimes.add(ev.timeStamp)
                    }
                }

                offTimes.sort(); onTimes.sort()

                // Find all candidate gaps: off→on pairs where off happened in evening/night window
                // Triple: (offAt, onAt, hours)
                val cal = Calendar.getInstance()
                val gaps = mutableListOf<Triple<Long, Long, Float>>()
                for (offT in offTimes) {
                    cal.timeInMillis = offT
                    val offHour = cal.get(Calendar.HOUR_OF_DAY)
                    // Plausible bedtime: 8 PM–4 AM
                    if (offHour in 20..23 || offHour in 0..4) {
                        // Find the next screen-on after this off
                        val nextOn = onTimes.firstOrNull { it > offT } ?: continue
                        val gapH   = (nextOn - offT) / (1000f * 60 * 60)
                        // Real sleep: > 1.5h and < 14h
                        if (gapH in 1.5f..14f) gaps.add(Triple(offT, nextOn, gapH))
                    }
                }

                // Also handle: screen went off but never came back on (still sleeping)
                if (gaps.isEmpty() && offTimes.isNotEmpty()) {
                    val lastOff = offTimes.last()
                    cal.timeInMillis = lastOff
                    val offHour = cal.get(Calendar.HOUR_OF_DAY)
                    if (offHour in 20..23 || offHour in 0..6) {
                        val elapsed = (now - lastOff) / (1000f * 60 * 60)
                        if (elapsed in 1f..14f) {
                            // Save as active session in DataStore
                            dataStoreRepo.startSleepSession(lastOff)
                            isSleepingNow     = true
                            sleepSessionStart = lastOff
                        }
                    }
                }

                val best = gaps.maxByOrNull { it.third }
                if (best != null) {
                    // Save completed session if not already saved
                    dataStoreRepo.endSleepSession(best.second)
                    best.third.coerceAtMost(12f)
                } else 0f
            } catch (_: Exception) { 0f }
        }
    }

    // Live ticker: if currently sleeping, update elapsed time every minute
    LaunchedEffect(isSleepingNow, sleepSessionStart) {
        if (!isSleepingNow || sleepSessionStart == 0L) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            estimatedSleepH = ((System.currentTimeMillis() - sleepSessionStart) / (1000f * 60 * 60))
                .coerceAtMost(16f)
        }
    }

    // ── Hydration: persisted to DataStore — survives app kill, resets at midnight ─
    var waterCups by remember { mutableStateOf<Int>(0) }
    LaunchedEffect(Unit) {
        waterCups = dataStoreRepo.loadWaterCups()
    }
    fun setWater(cups: Int) {
        waterCups = cups
        coroutineScope.launch { dataStoreRepo.saveWaterCups(cups) }
    }

    // ── Sleep onboarding dialog ────────────────────────────────────────
    if (showSleepOnboard) {
        val sleepOptions = listOf(6f, 6.5f, 7f, 7.5f, 8f, 8.5f, 9f)
        val behaviorOptions = listOf(
            "📵 Turn off mobile data",
            "✈️ Enable airplane mode",
            "🔕 Enable DND / silent",
            "🌙 Enable dark mode",
            "🔇 Mute all notifications",
            "🔋 Plug in charger",
            "📴 Turn off WiFi"
        )
        AlertDialog(
            onDismissRequest = {},
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("💤", fontSize = 36.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(if (sleepOnboardStep == 0) "Sleep Goal" else "Sleep Habits",
                        fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(if (sleepOnboardStep == 0)
                            "How many hours do you aim to sleep?"
                         else
                            "What do you usually do before sleeping?",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sleepOnboardStep == 0) {
                        // Hour picker chips
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            sleepOptions.forEach { h ->
                                val sel = sleepOnboardTarget == h
                                Surface(
                                    onClick = { sleepOnboardTarget = h },
                                    shape = CircleShape,
                                    color = if (sel) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = if (h == h.toLong().toFloat()) "${h.toInt()}h" else "${h}h",
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        textAlign = TextAlign.Center,
                                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (sel) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Selected: ${if (sleepOnboardTarget == sleepOnboardTarget.toLong().toFloat()) "${sleepOnboardTarget.toInt()} hours" else "${sleepOnboardTarget} hours"}",
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    } else {
                        // Behavior checkboxes
                        behaviorOptions.forEach { opt ->
                            val checked = opt in sleepOnboardBehaviors
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (checked) MaterialTheme.colorScheme.primaryContainer.copy(0.5f)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(0.4f))
                                    .clickable {
                                        sleepOnboardBehaviors = if (checked)
                                            sleepOnboardBehaviors - opt
                                        else
                                            sleepOnboardBehaviors + opt
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(opt, fontSize = 13.sp)
                                if (checked) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (sleepOnboardStep == 0) {
                        sleepOnboardStep = 1
                    } else {
                        // Save and close
                        val target    = sleepOnboardTarget
                        val behaviors = sleepOnboardBehaviors.toList()
                        coroutineScope.launch {
                            dataStoreRepo.saveSleepOnboarding(target, behaviors)
                        }
                        sleepTargetHours = target
                        sleepBehaviors   = behaviors
                        showSleepOnboard = false
                    }
                }, shape = RoundedCornerShape(12.dp)) {
                    Text(if (sleepOnboardStep == 0) "Next →" else "Done ✓")
                }
            },
            dismissButton = if (sleepOnboardStep == 1) {
                { TextButton(onClick = { sleepOnboardStep = 0 }) { Text("← Back") } }
            } else null
        )
    }

    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        // ── Header ────────────────────────────────────────────────────
        item {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\uD83D\uDC9A", fontSize = 20.sp)
                Column {
                    Text("TODAY'S VITALS", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
                    Text(SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date()),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f))
                }
            }
        }

        // ── Vitals Insight digest ─────────────────────────────────────
        item {
            val stepStatus = when {
                !hasActivity              -> null
                stepCount <= 0            -> null
                stepCount >= 8000         -> "\uD83C\uDFC3 Goal crushed! $stepCount steps today."
                stepCount >= 5000         -> "\uD83D\uDC5F $stepCount steps — keep going, almost there!"
                else                      -> "\uD83D\uDC5F $stepCount steps so far. Try a short walk."
            }
            val screenStatus = when {
                !hasUsage                 -> null
                totalScreenMin == 0L      -> null
                totalScreenMin > 240      -> "\uD83D\uDCF1 ${totalScreenMin/60}h ${totalScreenMin%60}m screen — take breaks!"
                totalScreenMin > 120      -> "\uD83D\uDCF1 ${totalScreenMin/60}h ${totalScreenMin%60}m screen time today."
                else                      -> "\uD83D\uDCF1 ${totalScreenMin}m screen — looking good."
            }
            val insights = listOfNotNull(stepStatus, screenStatus)
            if (insights.isNotEmpty()) Card(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp),
                CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                CardDefaults.cardElevation(0.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("\uD83D\uDCA1", fontSize = 14.sp)
                        Text("TODAY'S INSIGHT", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp, color = Color(0xFF4CAF50))
                    }
                    insights.forEach { Text(it, fontSize = 13.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }

        // ── Three rings: Steps · Screen · Sleep ───────────────────────
        item {
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(24.dp),
                CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                CardDefaults.cardElevation(0.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top   // anchor tops, not centers
                    ) {

                        // Steps
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            VitalRingItem(
                                progress  = stepFrac,
                                value     = if (stepCount > 0) "$stepCount" else "--",
                                sub       = "/ 8k steps",
                                emoji     = "\uD83D\uDC5F",
                                ringColor = Color(0xFF4CAF50),
                                enabled   = hasActivity,
                                onEnable  = { activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
                            )
                        }

                        // Divider — fixed height so it doesn't stretch
                        Box(Modifier.width(1.dp).height(130.dp).padding(top = 8.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(0.15f)))

                        // Screen time
                        val scrH = totalScreenMin / 60; val scrM = totalScreenMin % 60
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            VitalRingItem(
                                progress  = screenFrac,
                                value     = if (hasUsage) (if (scrH > 0) "${scrH}h${scrM}m" else "${scrM}m") else "--",
                                sub       = "/ 2h limit",
                                emoji     = "\uD83D\uDCF1",
                                ringColor = when {
                                    screenFrac > 0.9f -> Color(0xFFEF5350)
                                    screenFrac > 0.7f -> Color(0xFFFFA726)
                                    else              -> Color(0xFF42A5F5)
                                },
                                enabled   = hasUsage,
                                onEnable  = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                            )
                        }

                        // Divider
                        Box(Modifier.width(1.dp).height(130.dp).padding(top = 8.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(0.15f)))

                        // Sleep
                        val sleepFrac = (estimatedSleepH / sleepTargetHours).coerceIn(0f, 1f)
                        val sleepLabel = when {
                            isSleepingNow                          -> "sleeping now 💤"
                            estimatedSleepH <= 0f && hour < 20     -> "no data yet"
                            estimatedSleepH <= 0f                  -> "tap to set up"
                            else -> "/ ${if (sleepTargetHours == sleepTargetHours.toLong().toFloat()) "${sleepTargetHours.toInt()}h" else "${sleepTargetHours}h"} goal"
                        }
                        val sleepValueText = when {
                            isSleepingNow -> {
                                val h = estimatedSleepH.toInt()
                                val m = ((estimatedSleepH - h) * 60).toInt()
                                if (m > 0) "${h}h ${m}m" else "${h}h"
                            }
                            estimatedSleepH > 0f -> "${"%.1f".format(estimatedSleepH)}h"
                            else -> "--"
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                            VitalRingItem(
                                progress  = sleepFrac,
                                value     = sleepValueText,
                                sub       = sleepLabel,
                                emoji     = if (isSleepingNow) "🌙" else "\uD83D\uDCA4",
                                ringColor = when {
                                    isSleepingNow         -> Color(0xFF5C6BC0)
                                    estimatedSleepH >= sleepTargetHours -> Color(0xFF7C4DFF)
                                    estimatedSleepH >= sleepTargetHours * 0.7f -> Color(0xFFFFA726)
                                    estimatedSleepH > 0f  -> Color(0xFFEF5350)
                                    else                  -> Color(0xFF7C4DFF)
                                },
                                enabled          = true,
                                onEnable         = {},
                                alwaysClickable  = true,
                                onRingClick      = { showSleepOnboard = true; sleepOnboardStep = 0 }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(0.15f))
                    StepProgressBar(stepCount, stepGoal, stepFrac, hasActivity)
                }
            }
        }

        // ── Quick stat chips: Calories · Distance · Active ─────────────
        item {
            val caloriesBurned = if (stepCount > 0) (stepCount * 0.04f).toInt() else 0
            val distanceKm     = if (stepCount > 0) stepCount * 0.00078f else 0f
            val activeMins     = if (stepCount > 0) (stepCount / 100).coerceAtMost(90) else 0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("\uD83D\uDD25", if (stepCount > 0) "$caloriesBurned" else "--", "kcal"),
                    Triple("\uD83D\uDCCD", if (stepCount > 0) "${"%.1f".format(distanceKm)}" else "--", "km walked"),
                    Triple("\u23F1\uFE0F", if (stepCount > 0) "$activeMins" else "--", "active min")
                ).forEach { (emoji, value, label) ->
                    Card(Modifier.weight(1f), RoundedCornerShape(16.dp),
                        CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                        CardDefaults.cardElevation(0.dp)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(emoji, fontSize = 17.sp)
                            Text(value, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                            Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.65f),
                                textAlign = TextAlign.Center, lineHeight = 11.sp)
                        }
                    }
                }
            }
        }

        // ── Hydration tracker with +/- ─────────────────────────────────
        item {
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp),
                CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                CardDefaults.cardElevation(0.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    // Header row
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("\uD83D\uDCA7", fontSize = 18.sp)
                            Text("HYDRATION", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        val hydMsg = when {
                            waterCups >= 8 -> "\uD83C\uDF89 Goal met!"
                            hour < 10      -> "Morning — start hydrating"
                            hour in 10..13 -> "Midday check"
                            hour in 14..17 -> "Afternoon boost"
                            else           -> "Evening — wind down"
                        }
                        Text(hydMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f))
                    }

                    // Cup icons row
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(8) { i ->
                            val filled = i < waterCups
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (filled) Color(0xFF42A5F5).copy(alpha = 0.85f)
                                        else MaterialTheme.colorScheme.outline.copy(0.12f)
                                    )
                                    .clickable {
                                        setWater(if (filled && i == waterCups - 1) waterCups - 1 else i + 1)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (filled) "\uD83D\uDCA7" else "\u25A1",
                                    fontSize = if (filled) 14.sp else 12.sp,
                                    color = if (filled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f))
                            }
                        }
                    }

                    // +/- control row
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {

                        // Minus button
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (waterCups > 0) MaterialTheme.colorScheme.primary.copy(0.12f)
                                    else MaterialTheme.colorScheme.outline.copy(0.08f)
                                )
                                .clickable(enabled = waterCups > 0) { setWater(waterCups - 1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("\u2212", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                color = if (waterCups > 0) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f))
                        }

                        // Count + label
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$waterCups / 8", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text("glasses today", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f))
                        }

                        // Plus button
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (waterCups < 8) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline.copy(0.08f)
                                )
                                .clickable(enabled = waterCups < 8) { setWater(waterCups + 1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                color = if (waterCups < 8) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f))
                        }
                    }

                    // Progress bar
                    val waterFrac = (waterCups / 8f).coerceIn(0f, 1f)
                    var wBarVis by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { kotlinx.coroutines.delay(200L); wBarVis = true }
                    val animWater by animateFloatAsState(if (wBarVis) waterFrac else 0f,
                        tween(700, easing = FastOutSlowInEasing), label = "water")
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.outline.copy(0.12f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(animWater)
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF81D4FA), Color(0xFF0288D1)))))
                    }
                }
            }
        }

        // ── App usage breakdown ────────────────────────────────────────
        if (hasUsage && topApps.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp),
                    CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                    CardDefaults.cardElevation(0.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("\uD83D\uDCCA", fontSize = 15.sp)
                                Text("APP USAGE TODAY", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            val totalH = totalScreenMin / 60; val totalM = totalScreenMin % 60
                            Text(if (totalH > 0) "${totalH}h ${totalM}m total" else "${totalM}m total",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f))
                        }
                        val maxMin = topApps.maxOfOrNull { it.minutesUsed } ?: 1L
                        topApps.forEachIndexed { i, app -> AppUsageBar(app, maxMin, i) }
                    }
                }
            }
        }

        // ── Smart AI daily health tip ─────────────────────────────────
        item {
            var healthTip   by remember { mutableStateOf("") }
            var tipLoading  by remember { mutableStateOf(false) }
            val tipScope    = rememberCoroutineScope()

            LaunchedEffect(stepCount, estimatedSleepH, waterCups) {
                tipLoading = true
                healthTip = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    buildHealthTip(stepCount, estimatedSleepH, waterCups, hour)
                }
                tipLoading = false
            }

            AnimatedVisibility(visible = healthTip.isNotBlank() || tipLoading,
                enter = fadeIn() + expandVertically()) {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
                    CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                    CardDefaults.cardElevation(0.dp)) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("💡", fontSize = 22.sp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("TODAY'S HEALTH TIP", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.primary)
                            if (tipLoading)
                                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(50)),
                                    color = MaterialTheme.colorScheme.primary)
                            else if (healthTip.isNotBlank())
                                Text(healthTip, fontSize = 13.sp, lineHeight = 19.sp,
                                    color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        // ── Health Connect cards (heart rate, SpO2, sleep stages, calories) ──
        if (hcSnap != null) {
            val snap = hcSnap!!

            // Source badge
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF4CAF50).copy(alpha = 0.15f)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("\u2764\uFE0F", fontSize = 10.sp)
                            Text("Health Connect", fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold, color = Color(0xFF4CAF50))
                        }
                    }
                }
            }

            // Heart rate card
            snap.heartRate?.let { hr ->
                item {
                    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
                        CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                        CardDefaults.cardElevation(0.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("\uD83D\uDC93", fontSize = 16.sp)
                                Text("HEART RATE", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp, color = Color(0xFFEF5350))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                listOf(
                                    Triple("resting", "${hr.restingBpm}", "bpm"),
                                    Triple("current", "${hr.latestBpm}", "bpm"),
                                    Triple("max", "${hr.maxBpm}", "bpm")
                                ).forEach { (lbl, value, unit) ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFFEF5350))
                                        Text(unit, fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(lbl, fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // SpO2 card
            snap.spO2?.let { spo2 ->
                item {
                    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
                        CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                        CardDefaults.cardElevation(0.dp)) {
                        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("\uD83D\uDCA8", fontSize = 22.sp)
                                Column {
                                    Text("BLOOD OXYGEN", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp, color = Color(0xFF2196F3))
                                    Text("SpO\u2082", fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${"%.1f".format(spo2)}%", fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold, color = Color(0xFF2196F3))
                                val status = when {
                                    spo2 >= 95 -> "Normal \u2713"
                                    spo2 >= 90 -> "Low — rest"
                                    else       -> "Very low"
                                }
                                Text(status, fontSize = 11.sp,
                                    color = if (spo2 >= 95) Color(0xFF4CAF50) else Color(0xFFFF9800))
                            }
                        }
                    }
                }
            }

            // Sleep stages card (from HC — much richer than screen-off heuristic)
            snap.sleep?.let { sl ->
                item {
                    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
                        CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                        CardDefaults.cardElevation(0.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("\uD83D\uDECF\uFE0F", fontSize = 16.sp)
                                Text("SLEEP STAGES", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp, color = Color(0xFF7C4DFF))
                                Spacer(Modifier.weight(1f))
                                Text("${"%.1f".format(sl.totalMinutes / 60f)}h total",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            // Stage bars
                            data class Stage(val label: String, val min: Long, val color: Color)
                            listOf(
                                Stage("Deep",  sl.deepMinutes,  Color(0xFF3F51B5)),
                                Stage("REM",   sl.remMinutes,   Color(0xFF9C27B0)),
                                Stage("Light", sl.lightMinutes, Color(0xFF5C6BC0)),
                                Stage("Awake", sl.awakMinutes,  Color(0xFFFF9800))
                            ).filter { it.min > 0 }.forEach { stage ->
                                val frac = (stage.min.toFloat() / sl.totalMinutes.coerceAtLeast(1)).coerceIn(0f, 1f)
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(stage.label, fontSize = 11.sp, modifier = Modifier.width(40.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Box(Modifier.weight(1f).height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surface)) {
                                        Box(Modifier.fillMaxHeight()
                                            .fillMaxWidth(frac)
                                            .background(stage.color, RoundedCornerShape(4.dp)))
                                    }
                                    Text("${stage.min}m", fontSize = 11.sp, modifier = Modifier.width(36.dp),
                                        textAlign = TextAlign.End,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // Calories from HC (more accurate than step-derived estimate)
            if (snap.calories > 0) {
                item {
                    Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
                        CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                        CardDefaults.cardElevation(0.dp)) {
                        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("\uD83D\uDD25", fontSize = 22.sp)
                                Column {
                                    Text("CALORIES BURNED", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp, color = Color(0xFFFF5722))
                                    Text("from Health Connect", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("${snap.calories.toInt()} kcal", fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold, color = Color(0xFFFF5722))
                        }
                    }
                }
            }
        }

        // ── Permissions card ──────────────────────────────────────────
        if (!hasActivity || !hasUsage) {
            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp),
                    CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                    CardDefaults.cardElevation(0.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("\uD83D\uDD12 Grant permissions for full tracking",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface)
                        if (!hasActivity) PermissionRow("\uD83D\uDC5F", "Physical Activity", "Count your daily steps") {
                            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        }
                        if (!hasUsage) PermissionRow("\uD83D\uDCF1", "Usage Access", "Track screen time & sleep") {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                        if (hcRepo.needsUpdate) PermissionRow("\u2764\uFE0F", "Health Connect", "Update required — tap to open Play Store") {
                            // Guide: SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED → Play Store
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                            context.startActivity(intent)
                        }
                        else if (hcAvailable && hcSnap == null) {
                            // Restricted Settings warning for sideloaded APKs (Android 13+)
                            // Per guide: user must allow restricted settings before HC works
                            var hcBlocked by remember { mutableStateOf(false) }

                            if (hcBlocked) {
                                Card(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp),
                                    CardDefaults.cardColors(Color(0xFFFF5722).copy(alpha = 0.12f)),
                                    CardDefaults.cardElevation(0.dp)) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Text("\uD83D\uDD12", fontSize = 16.sp)
                                            Text("RESTRICTED SETTINGS BLOCKED", fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                                                color = Color(0xFFFF5722))
                                        }
                                        Text("Because this APK was sideloaded, Android blocks Health Connect. To fix:",
                                            fontSize = 12.sp, lineHeight = 18.sp,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        listOf(
                                            "1. Try tapping Connect Health Connect below",
                                            "2. When blocked, dismiss the error dialog",
                                            "3. Long-press the app icon \u2192 App Info (\u24D8)",
                                            "4. Tap the 3-dot menu \u22EE \u2192 Allow restricted settings",
                                            "5. Authenticate with PIN / fingerprint",
                                            "6. Come back here and tap Connect again"
                                        ).forEach { step ->
                                            Text(step, fontSize = 12.sp, lineHeight = 17.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text("\uD83D\uDCF1 Samsung users: Settings \u2192 Security \u2192 Auto Blocker \u2192 OFF",
                                            fontSize = 11.sp, lineHeight = 16.sp,
                                            color = Color(0xFFFF9800), fontWeight = FontWeight.Medium)
                                        Button(onClick = {
                                            hcBlocked = false
                                            hcPermLauncher.launch(hcRepo.requiredPermissions)
                                        }, modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)) {
                                            Text("Connect Health Connect")
                                        }
                                    }
                                }
                            } else {
                                PermissionRow("\u2764\uFE0F", "Health Connect",
                                    "Steps, sleep stages, heart rate & more") {
                                    try {
                                        hcPermLauncher.launch(hcRepo.requiredPermissions)
                                    } catch (e: Exception) {
                                        // Likely blocked by Restricted Settings
                                        hcBlocked = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun VitalRingItem(
    progress: Float, value: String, sub: String,
    emoji: String, ringColor: Color,
    enabled: Boolean, onEnable: () -> Unit,
    alwaysClickable: Boolean = false,
    onRingClick: (() -> Unit)? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val clickMod = if (alwaysClickable && onRingClick != null)
            Modifier.size(100.dp).clickable(onClick = onRingClick)
        else
            Modifier.size(100.dp)
        AnimatedRingChart(
            progress  = progress,
            label     = sub,
            valueText = value,
            subText   = sub,
            ringColor = ringColor,
            modifier  = clickMod
        )
        Text(emoji, fontSize = 14.sp)
        if (!enabled) {
            TextButton(onClick = onEnable, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("Enable", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
        } else if (alwaysClickable) {
            TextButton(onClick = onRingClick ?: {}, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("Edit goal", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun RowScope.VitalDivider() {
    Box(Modifier.width(1.dp).height(110.dp)
        .background(MaterialTheme.colorScheme.outline.copy(0.15f))
        .align(Alignment.CenterVertically))
}

@Composable
private fun StepProgressBar(stepCount: Int, stepGoal: Float, stepFrac: Float, hasActivity: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Step Progress", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val remaining = if (stepCount > 0) (stepGoal.toInt() - stepCount).coerceAtLeast(0) else -1
            Text(
                when {
                    !hasActivity   -> "Enable activity permission"
                    stepCount <= 0 -> "Waiting for first steps..."
                    remaining == 0 -> "Goal reached! \uD83C\uDF89"
                    else           -> "$remaining steps left"
                },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = when {
                    remaining == 0 -> Color(0xFF4CAF50)
                    else           -> MaterialTheme.colorScheme.onSurface
                }
            )
        }
        var barVis by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(400L); barVis = true }
        val barF by animateFloatAsState(if (barVis) stepFrac else 0f,
            tween(1000, easing = FastOutSlowInEasing), label = "stepPBar")
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.outline.copy(0.12f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(barF).clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Color(0xFF81C784), Color(0xFF2E7D32)))))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "2k", "4k", "6k", "8k").forEach {
                Text(it, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.45f))
            }
        }
    }
}

@Composable
private fun PermissionRow(emoji: String, title: String, sub: String, onGrant: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 18.sp)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onGrant) { Text("Grant", fontSize = 12.sp) }
    }
}

// ─── SHARED ───────────────────────────────────────────────────────────
@Composable
fun WarmLoadingScreen(message: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
            Text(message, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
