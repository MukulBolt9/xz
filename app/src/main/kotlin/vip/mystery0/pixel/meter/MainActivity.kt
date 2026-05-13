package com.kakao.taxi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kakao.taxi.data.repository.NetworkRepository
import com.kakao.taxi.data.source.NetSpeedData
import com.kakao.taxi.service.NowBriefService
import com.kakao.taxi.ui.MainViewModel
import com.kakao.taxi.ui.settings.SettingsActivity
import com.kakao.taxi.ui.theme.PixelPulseTheme
import com.kakao.taxi.util.isServiceRunning

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val isOledTheme by viewModel.isOledThemeEnabled.collectAsState(initial = false)
            val isDarkTheme by viewModel.isDarkThemeEnabled.collectAsState(initial = false)
            PixelPulseTheme(darkTheme = isDarkTheme, isOledTheme = isOledTheme) {
                HomeScreen()
            }
        }
    }

    @Composable
    fun HomeScreen() {
        val context = LocalContext.current
        val view = LocalView.current
        val speed by viewModel.currentSpeed.collectAsState()
        val isServiceRunning by viewModel.isServiceRunning.collectAsState()
        val isNotificationEnabled by viewModel.isNotificationEnabled.collectAsState()
        val isHideFromRecents by viewModel.isHideFromRecents.collectAsState(initial = false)
        val serviceError by viewModel.serviceStartError.collectAsState()
        val speedUnit by viewModel.speedUnit.collectAsState()
        val compactMode by viewModel.isCompactSpeedTextEnabled.collectAsState(initial = false)
        val isOledTheme by viewModel.isOledThemeEnabled.collectAsState(initial = false)
        val updateInfo by viewModel.updateAvailable.collectAsState()
        val skippedVersion by viewModel.skippedUpdateVersion.collectAsState(initial = "")

        var isBriefRunning by remember {
            mutableStateOf(isServiceRunning(context, NowBriefService::class.java))
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isBriefRunning = isServiceRunning(context, NowBriefService::class.java)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
        var isIgnoringBattery by remember {
            mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
        }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isIgnoringBattery = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(skippedVersion) {
            viewModel.checkForUpdates(BuildConfig.VERSION_NAME, skippedVersion)
        }

        if (updateInfo != null) {
            AlertDialog(
                onDismissRequest = { viewModel.clearUpdateDialog() },
                title = { Text("Update Available") },
                text = { Text("Version ${updateInfo!!.versionName} is now available.") },
                confirmButton = {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, updateInfo!!.url.toUri()))
                        viewModel.clearUpdateDialog()
                    }) { Text("Update") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.skipUpdate(updateInfo!!.versionName) }) { Text("Skip") }
                }
            )
        }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { if (it) viewModel.clearError() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        val isDk by viewModel.isDarkThemeEnabled.collectAsState(initial = false)
                        IconButton(onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            viewModel.toggleDarkTheme()
                        }) {
                            Icon(
                                if (isDk) Icons.Default.WbSunny else Icons.Default.NightsStay,
                                contentDescription = if (isDk) "Switch to Day" else "Switch to Night"
                            )
                        }
                        IconButton(onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            startActivity(Intent(context, SettingsActivity::class.java))
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── NowBrief Control Card ──────────────────────────────
                item {
                    Text("NowBrief", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isBriefRunning)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isBriefRunning) Icons.Default.CheckCircle else Icons.Default.Circle,
                                    contentDescription = null,
                                    tint = if (isBriefRunning) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isBriefRunning) "NowBrief Active — Live Notification ON"
                                    else "NowBrief Inactive",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isBriefRunning) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (isBriefRunning)
                                    "Shows AI-powered greetings, weather and news in Samsung Now Bar"
                                else
                                    "Tap Start → allow background permission → tap Start again",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isBriefRunning) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        // Step 1: check overlay (background display) permission first
                                        if (!android.provider.Settings.canDrawOverlays(context)) {
                                            val intent = Intent(
                                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                "package:${context.packageName}".toUri()
                                            )
                                            context.startActivity(intent)
                                        } else {
                                            // Permission granted — start service
                                            val svc = Intent(context, NowBriefService::class.java)
                                            context.startForegroundService(svc)
                                            isBriefRunning = true
                                        }
                                    },
                                    enabled = !isBriefRunning,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Start") }
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        context.stopService(Intent(context, NowBriefService::class.java))
                                        isBriefRunning = false
                                    },
                                    enabled = isBriefRunning,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Stop") }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    startActivity(Intent(context, NowBriefActivity::class.java))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open NowBrief")
                            }
                        }
                    }
                }

                // ── Battery Warning ──────────────────────────────────
                if (!isIgnoringBattery) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = "package:${context.packageName}".toUri()
                                })
                            }
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.battery_optimization_warning_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(stringResource(R.string.battery_optimization_warning_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }

                // ── Speed Meter Section ──────────────────────────────
                item {
                    Text(stringResource(R.string.title_monitor_control),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                item { SpeedDashboardCard(speed, speedUnit, compactMode) }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isServiceRunning) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isServiceRunning) Icons.Default.NetworkCheck else Icons.Default.NetworkCheck,
                                    contentDescription = null,
                                    tint = if (isServiceRunning) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isServiceRunning) "NowBar Meter Active — Speed Live"
                                    else "NowBar Meter Inactive",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isServiceRunning) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Shows live network upload/download speed in Now Bar",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isServiceRunning)
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        viewModel.startService()
                                    },
                                    enabled = !isServiceRunning,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Start NowBar Meter") }
                                Button(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        viewModel.stopService()
                                    },
                                    enabled = isServiceRunning,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f)
                                ) { Text(stringResource(R.string.action_stop)) }
                            }
                        }
                    }
                }

                if (serviceError != null) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(serviceError?.first ?: stringResource(R.string.error_unknown),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End) {
                                    Button(onClick = {
                                        serviceError?.let { (_, action) ->
                                            if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                else {
                                                    startActivity(Intent(action).apply {
                                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                    })
                                                }
                                            } else {
                                                startActivity(Intent(action).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                    data = "package:${context.packageName}".toUri()
                                                })
                                                viewModel.clearError()
                                            }
                                        }
                                    }) { Text(stringResource(R.string.action_request_fix)) }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { viewModel.clearError() }) {
                                        Text(stringResource(R.string.action_dismiss))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpeedDashboardCard(speed: NetSpeedData, speedUnit: String = "0", compactMode: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.label_total_speed), style = MaterialTheme.typography.labelMedium)
            Text(
                NetworkRepository.formatSpeedLine(speed.totalSpeed, speedUnit, compactMode),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.label_download), style = MaterialTheme.typography.bodySmall)
                    Text("▼ " + NetworkRepository.formatSpeedLine(speed.downloadSpeed, speedUnit, compactMode),
                        style = MaterialTheme.typography.titleMedium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.label_upload), style = MaterialTheme.typography.bodySmall)
                    Text("▲ " + NetworkRepository.formatSpeedLine(speed.uploadSpeed, speedUnit, compactMode),
                        style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
