package com.kakao.taxi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import com.kakao.taxi.MainActivity
import com.kakao.taxi.NowBriefActivity
import com.kakao.taxi.R
import com.kakao.taxi.data.repository.NowBriefRepository

class NowBriefService : Service() {

    companion object {
        private const val TAG = "NowBriefService"
        const val CHANNEL_ID = "nowbrief_live"
        const val NOTIFICATION_ID = 2001
        const val ACTION_OPEN_BRIEF = "com.kakao.taxi.OPEN_BRIEF"

        fun createNotificationChannel(context: android.content.Context) {
            val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NowBrief Live",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "NowBrief live notification with weather, news and AI summary"
                setShowBadge(true)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private val briefRepository: NowBriefRepository by inject()
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }
    private val scope = CoroutineScope(Dispatchers.Default)
    private var refreshJob: Job? = null
    private var tickJob: Job? = null

    // Rotating chip messages shown in Now Bar
    private val chipMessages = listOf(
        "Good morning! ☀️",
        "Hope you're doing well 🌟",
        "Stay hydrated! 💧",
        "You've got this! 💪",
        "Have a great day! 😊",
        "Take a deep breath 🌿",
        "Smile, it's contagious 😄",
        "Keep going! 🚀",
        "Wishing you well ✨",
        "Enjoy the moment 🌸"
    )
    private var chipIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initial = buildNotification(liveGreeting(), "Loading your daily brief…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error", e)
            stopSelf()
            return START_NOT_STICKY
        }

        startTickingMessages()
        startPeriodicRefresh()

        return START_STICKY
    }

    // Returns a greeting that always matches the current time of day
    private fun liveGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour < 5  -> "Good night 🌙"
            hour < 12 -> "Good morning ☀️"
            hour < 17 -> "Good afternoon 🌤️"
            hour < 21 -> "Good evening 🌅"
            else      -> "Good night 🌙"
        }
    }

    private fun startTickingMessages() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                val state = briefRepository.briefState.value
                val summary = state.summary

                // Build a rich pool of secondary messages from live data
                val pool = mutableListOf<String>()
                if (summary.weatherSummary.isNotBlank()) pool.add(summary.weatherSummary)
                if (summary.daySummary.isNotBlank())     pool.add(summary.daySummary)
                summary.tips.filter { it.isNotBlank() }.forEach { pool.add("💡 $it") }
                if (summary.quote.isNotBlank()) {
                    // trim quote to fit notification line
                    val q = summary.quote.let { if (it.length > 80) it.take(77) + "…" else it }
                    pool.add("\"$q\"")
                }
                summary.music?.let { m ->
                    pool.add("🎵 ${m.title} · ${m.artist} — ${m.reason}")
                }
                if (state.weather.cityName.isNotBlank() && state.weather.temperature != 0.0) {
                    pool.add("📍 ${state.weather.cityName}: ${state.weather.temperature.toInt()}°C, ${state.weather.condition}")
                }
                // Always have at least one fallback
                if (pool.isEmpty()) pool.add("Tap to open your daily brief")

                // Cycle through the pool indefinitely
                val secondary = pool[chipIndex % pool.size]

                // Always use a time-accurate greeting as the primary
                val greeting = liveGreeting()
                val notif = buildNotification(greeting, secondary)
                notificationManager.notify(NOTIFICATION_ID, notif)
                chipIndex++
                delay(30_000L) // rotate every 30 seconds
            }
        }
    }

    private fun startPeriodicRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            // Initial full fetch
            try { briefRepository.refreshAll() } catch (e: Exception) { Log.e(TAG, "initial refresh failed", e) }
            var cyclesSinceFullRefresh = 0
            while (true) {
                delay(10 * 60 * 1000L) // every 10 minutes
                cyclesSinceFullRefresh++
                if (cyclesSinceFullRefresh >= 3) {
                    // Full refresh every 30 minutes
                    try { briefRepository.refreshAll() } catch (e: Exception) { Log.e(TAG, "periodic refresh failed", e) }
                    cyclesSinceFullRefresh = 0
                } else {
                    // Just rotate quote + music between full refreshes
                    try { briefRepository.refreshQuoteAndMusic() } catch (e: Exception) { Log.e(TAG, "quote/music refresh failed", e) }
                }
            }
        }
    }

    private fun applySamsungHack(notification: Notification): Notification {
        try {
            val field = Notification::class.java.getDeclaredField("semFlags")
            field.isAccessible = true
            field.setInt(notification, field.getInt(notification) or 32768)
        } catch (_: Exception) {}
        return notification
    }

    private fun buildNotification(primaryText: String, secondaryText: String): Notification {
        val openIntent = Intent(this, NowBriefActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_nowbrief)
            .setContentTitle(primaryText)
            .setContentText(secondaryText)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        // Samsung Live Notification / Now Bar extras
        val extras = Bundle().apply {
            putInt("android.ongoingActivityNoti.style", 1)
            putString("android.ongoingActivityNoti.primaryInfo", primaryText)
            putString("android.ongoingActivityNoti.secondaryInfo", secondaryText)
            putString("android.ongoingActivityNoti.nowbarPrimaryInfo", "NowBrief")
            putString("android.ongoingActivityNoti.nowbarSecondaryInfo", primaryText)
            putString("android.ongoingActivityNoti.chipExpandedText", primaryText)
            // Use a gentle purple tint for the chip
            putInt("android.ongoingActivityNoti.chipBgColor", 0xFF6750A4.toInt())
        }
        builder.addExtras(extras)
        builder.setRequestPromotedOngoing(true)

        return applySamsungHack(builder.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshJob?.cancel()
        tickJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
