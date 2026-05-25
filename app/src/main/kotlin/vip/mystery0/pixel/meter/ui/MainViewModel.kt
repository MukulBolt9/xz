package com.kakao.taxi.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.kakao.taxi.R
import com.kakao.taxi.data.repository.NetworkRepository
import com.kakao.taxi.service.NetworkMonitorService
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainViewModel(
    private val application: Application,
) : AndroidViewModel(application), KoinComponent {
    companion object {
        private const val TAG = "MainViewModel"
    }

    private val repository: NetworkRepository by inject()

    val currentSpeed = repository.netSpeed

    val isOverlayEnabled = repository.isOverlayEnabled
    val isNotificationEnabled = repository.isNotificationEnabled
    val isHideFromRecents = repository.isHideFromRecents

    val speedUnit = repository.speedUnit
    val isOledThemeEnabled = repository.isOledThemeEnabled
    val isDarkThemeEnabled = repository.isDarkThemeEnabled

    fun toggleDarkTheme() {
        repository.setDarkThemeEnabled(!isDarkThemeEnabled.value)
    }
    val isCompactSpeedTextEnabled = repository.isCompactSpeedTextEnabled
    val isBlankNotificationEnabled = repository.isBlankNotificationEnabled

    val isServiceRunning = repository.isMonitoring

    private val _serviceStartError = MutableStateFlow<Pair<String, String>?>(null)
    val serviceStartError = _serviceStartError.asStateFlow()

    data class UpdateInfo(val versionName: String, val url: String)
    private val _updateAvailable = MutableStateFlow<UpdateInfo?>(null)
    val updateAvailable = _updateAvailable.asStateFlow()

    val skippedUpdateVersion = repository.skippedUpdateVersion

    // ── Update state ─────────────────────────────────────────────────
    enum class DownloadState { IDLE, DOWNLOADING, READY_TO_INSTALL, ERROR }
    private val _downloadState = MutableStateFlow(DownloadState.IDLE)
    val downloadState = _downloadState.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress = _downloadProgress.asStateFlow()
    private var pendingApkFile: File? = null
    private var latestApkUrl: String = ""

    fun checkForUpdates(currentVersion: String, skippedVersion: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/MukulBolt9/xz/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val tagName = json.optString("tag_name", "")
                    val cleanedTag = tagName.removePrefix("v")

                    // Find the APK asset URL from the release assets
                    val assets = json.optJSONArray("assets")
                    var apkUrl = "https://github.com/MukulBolt9/xz/releases/latest"
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", apkUrl)
                                break
                            }
                        }
                    }
                    latestApkUrl = apkUrl

                    if (cleanedTag.isNotBlank()
                        && isNewerVersion(cleanedTag, currentVersion)
                        && cleanedTag != skippedVersion) {
                        _updateAvailable.value = UpdateInfo(
                            versionName = cleanedTag,
                            url = apkUrl
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkForUpdates: failed", e)
            }
        }
    }

    /** Downloads the APK and triggers system install prompt — no browser needed */
    fun downloadAndInstallUpdate() {
        if (_downloadState.value == DownloadState.DOWNLOADING) return
        val apkUrl = latestApkUrl.ifBlank {
            _updateAvailable.value?.url ?: return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _downloadState.value = DownloadState.DOWNLOADING
            _downloadProgress.value = 0f
            try {
                val cacheDir = File(application.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(cacheDir, "NowBrief-update.apk")
                if (apkFile.exists()) apkFile.delete()

                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.connect()
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: 1L
                var downloaded = 0L
                conn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            _downloadProgress.value = downloaded.toFloat() / total.toFloat()
                        }
                    }
                }
                pendingApkFile = apkFile
                _downloadState.value = DownloadState.READY_TO_INSTALL
            } catch (e: Exception) {
                Log.e(TAG, "downloadAndInstallUpdate: failed", e)
                _downloadState.value = DownloadState.ERROR
            }
        }
    }

    /** Opens system package installer with the downloaded APK */
    fun installDownloadedApk() {
        val file = pendingApkFile ?: return
        try {
            val uri = FileProvider.getUriForFile(
                application,
                "${application.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            application.startActivity(intent)
            _downloadState.value = DownloadState.IDLE
            _updateAvailable.value = null
        } catch (e: Exception) {
            Log.e(TAG, "installDownloadedApk: failed", e)
            _downloadState.value = DownloadState.ERROR
        }
    }

    fun resetDownloadState() { _downloadState.value = DownloadState.IDLE }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLength = maxOf(latestParts.size, currentParts.size)
        
        for (i in 0 until maxLength) {
            val l = if (i < latestParts.size) latestParts[i] else 0
            val c = if (i < currentParts.size) currentParts[i] else 0
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun skipUpdate(version: String) {
        viewModelScope.launch {
            repository.setSkippedUpdateVersion(version)
            _updateAvailable.value = null
        }
    }

    fun clearUpdateDialog() {
        _updateAvailable.value = null
    }

    fun startService() {
        _serviceStartError.value = null

        // 1. Check Notification Permission (Android 13+)
        if (ContextCompat.checkSelfPermission(
                application,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _serviceStartError.value =
                application.getString(R.string.error_notification_permission) to Settings.ACTION_APP_NOTIFICATION_SETTINGS
            return
        }

        // 2. Check Overlay Permission if enabled
        if (isOverlayEnabled.value) {
            if (!Settings.canDrawOverlays(application)) {
                _serviceStartError.value =
                    application.getString(R.string.error_overlay_permission) to Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                return
            }
        }

        val intent = Intent(application, NetworkMonitorService::class.java)
        try {
            application.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startService: start foreground service error", e)
            _serviceStartError.value =
                application.getString(
                    R.string.error_service_start_failed,
                    e.message
                ) to Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
    }

    fun stopService(clearError: Boolean = true) {
        val intent = Intent(application, NetworkMonitorService::class.java)
        application.stopService(intent)

        if (clearError) {
            clearError()
        }
    }

    fun clearError() {
        _serviceStartError.value = null
    }

    fun setOverlayEnabled(enable: Boolean) {
        if (enable && isServiceRunning.value) {
            if (!Settings.canDrawOverlays(application)) {
                _serviceStartError.value =
                    application.getString(R.string.error_overlay_permission) to Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                stopService(false)
            }
        }
        repository.setOverlayEnabled(enable)
    }

    fun setNotificationEnabled(enable: Boolean) {
        if (enable && isServiceRunning.value) {
            if (ContextCompat.checkSelfPermission(
                    application,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                _serviceStartError.value =
                    application.getString(R.string.error_notification_permission) to Settings.ACTION_APP_NOTIFICATION_SETTINGS
                stopService(false)
            }
        }
        repository.setNotificationEnabled(enable)
    }
}
