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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import com.kakao.taxi.NowBriefActivity
import com.kakao.taxi.R
import com.kakao.taxi.data.repository.DataStoreRepository
import com.kakao.taxi.data.repository.HealthConnectRepository
import com.kakao.taxi.data.repository.NowBriefRepository
import com.kakao.taxi.data.repository.dataStore
import java.util.Calendar

class NowBriefService : Service() {

    companion object {
        private const val TAG = "NowBriefService"
        const val NOTIFICATION_ID = 2001

        // ── Two separate channels ──────────────────────────────────────────────
        //
        // SAMSUNG_CHANNEL_ID  → IMPORTANCE_MIN
        //   Invisible in notification drawer — no sound, no badge, no peek.
        //   The Now Bar reads content from the notification's bundle extras
        //   regardless of channel importance, so the pill works perfectly.
        //   We use this on Samsung so ONLY the Now Bar chip is visible.
        //
        // LIVE_CHANNEL_ID     → IMPORTANCE_LOW
        //   Quiet persistent notification — required for the Android 16
        //   Live Update chip (POST_PROMOTED_ONGOING). IMPORTANCE_MIN would
        //   disqualify it from promotion, so we keep it at LOW here.
        //
        private const val SAMSUNG_CHANNEL_ID = "nowbrief_samsung_nowbar"
        private const val LIVE_CHANNEL_ID    = "nowbrief_live"

        fun createNotificationChannels(context: android.content.Context) {
            val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // Samsung-only: completely silent, hidden channel
            if (manager.getNotificationChannel(SAMSUNG_CHANNEL_ID) == null) {
                NotificationChannel(
                    SAMSUNG_CHANNEL_ID,
                    "NowBrief Now Bar",                     // name shown in settings
                    NotificationManager.IMPORTANCE_MIN      // hidden from drawer
                ).apply {
                    description = "Powers the Samsung Now Bar pill — invisible in notification drawer"
                    setShowBadge(false)
                    setSound(null, null)
                    enableLights(false)
                    enableVibration(false)
                }.also { manager.createNotificationChannel(it) }
            }

            // Non-Samsung: quiet but visible channel for Android 16 Live Update
            if (manager.getNotificationChannel(LIVE_CHANNEL_ID) == null) {
                NotificationChannel(
                    LIVE_CHANNEL_ID,
                    "NowBrief Live",
                    NotificationManager.IMPORTANCE_LOW      // must NOT be MIN for promotion
                ).apply {
                    description = "NowBrief live brief — weather, news and AI summary"
                    setShowBadge(false)
                    setSound(null, null)
                    enableLights(false)
                    enableVibration(false)
                }.also { manager.createNotificationChannel(it) }
            }
        }

        // Keep old name for callers in MainApplication
        fun createNotificationChannel(context: android.content.Context) =
            createNotificationChannels(context)
    }

    private val briefRepository: NowBriefRepository by inject()
    private val healthRepository: HealthConnectRepository by inject()
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }
    private val scope = CoroutineScope(Dispatchers.Default)
    private var refreshJob: Job? = null
    private var tickJob: Job? = null
    private var hcJob: Job? = null
    private var chipIndex = 0

    private val isSamsung: Boolean by lazy {
        Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initial = buildNotification(liveGreeting(), "Loading your daily brief…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, initial,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, initial,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error", e)
            stopSelf()
            return START_NOT_STICKY
        }

        startTickingMessages()
        startPeriodicRefresh()
        startPeriodicHealthSnapshot()   // force HC parse immediately
        return START_STICKY
    }

    private fun liveGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 5  -> "Good night \uD83C\uDF19"
            hour < 12 -> "Good morning \u2600\uFE0F"
            hour < 17 -> "Good afternoon \uD83C\uDF24\uFE0F"
            hour < 21 -> "Good evening \uD83C\uDF05"
            else      -> "Good night \uD83C\uDF19"
        }
    }

    private fun startTickingMessages() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                val state   = briefRepository.briefState.value
                val summary = state.summary
                val pool    = mutableListOf<String>()

                if (summary.weatherSummary.isNotBlank()) pool.add(summary.weatherSummary)
                if (summary.daySummary.isNotBlank())     pool.add(summary.daySummary)
                summary.tips.filter { it.isNotBlank() }.forEach { pool.add("\uD83D\uDCA1 $it") }
                if (summary.quote.isNotBlank())
                    pool.add("\u201C${summary.quote.take(80)}\u201D")
                summary.music?.let { m ->
                    pool.add("\uD83C\uDFB5 ${m.title} \u00B7 ${m.artist}")
                }
                val w = state.weather
                if (w.cityName.isNotBlank() && w.temperature != 0.0)
                    pool.add("${w.icon} ${w.cityName}: ${w.temperature.toInt()}\u00B0C, ${w.condition}")
                if (pool.isEmpty()) pool.add("Tap to open your daily brief")

                val secondary = pool[chipIndex % pool.size]
                chipIndex++

                val notif = buildNotification(liveGreeting(), secondary)
                notificationManager.notify(NOTIFICATION_ID, notif)
                delay(30_000L)
            }
        }
    }

    /**
     * Fires HC snapshot immediately on service start, then:
     *  • Every 15 min: refresh snapshot (steps, HR, SpO₂, etc.)
     *  • On day change (midnight): force-reset step baseline + immediate snapshot
     *    so the day's step counter starts from 0 correctly.
     *
     * Day-change detection: compare last-snapshot date stored in DataStore to today.
     * This works even if the phone was off at midnight.
     */
    private fun startPeriodicHealthSnapshot() {
        hcJob?.cancel()
        hcJob = scope.launch(Dispatchers.IO) {
            val ds = DataStoreRepository(this@NowBriefService.dataStore)

            // ── Ring-1: immediate on service start ────────────────────
            runCatching { healthRepository.snapshot() }
                .onSuccess { ds.setLastSnapshotDate(todayDateStr()) }
                .onFailure { Log.e(TAG, "hc initial: ${it.message}") }

            // ── Periodic loop ─────────────────────────────────────────
            while (true) {
                delay(15 * 60 * 1000L)  // every 15 min

                // Day-change check: if DataStore date ≠ today → midnight crossed
                if (ds.isNewDay()) {
                    Log.d(TAG, "Day change detected — resetting step baseline, forcing HC re-fetch")
                    // Reset step baseline: HC steps come from todayStart() so they auto-reset.
                    // Sensor steps need the DataStore baseline reset (getDailySteps handles this
                    // automatically on first call of the new day, but we reset explicitly here
                    // so the sensor composable gets 0 immediately rather than waiting for a read).
                    ds.resetStepBaseline(0L)   // 0 = unknown until next sensor event; getDailySteps handles reboot case
                    ds.setLastSnapshotDate(todayDateStr())
                }

                runCatching { healthRepository.snapshot() }
                    .onFailure { Log.e(TAG, "hc periodic: ${it.message}") }
            }
        }
    }

    private fun todayDateStr(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())

    private fun startPeriodicRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try { briefRepository.refreshAll() } catch (e: Exception) { Log.e(TAG, "initial refresh", e) }
            var cycles = 0
            while (true) {
                delay(10 * 60 * 1000L)
                cycles++
                if (cycles >= 3) {
                    try { briefRepository.refreshAll() } catch (e: Exception) { Log.e(TAG, "periodic", e) }
                    cycles = 0
                } else {
                    try { briefRepository.refreshQuoteAndMusic() } catch (e: Exception) { Log.e(TAG, "quote", e) }
                }
            }
        }
    }

    /**
     * Builds the notification. Two distinct paths:
     *
     * ── Samsung path ─────────────────────────────────────────────────────────
     * Channel: SAMSUNG_CHANNEL_ID (IMPORTANCE_MIN)
     *   • The notification is completely invisible in the drawer
     *   • semFlags hack (bit 32768) + Now Bar bundle extras inject content
     *     into the Samsung Now Bar pill — the pill still shows perfectly
     *   • setRequestPromotedOngoing NOT set (not needed; Samsung uses its own
     *     promotion mechanism via semFlags and the extras bundle)
     *   • Result: ONLY the Now Bar pill is visible — no drawer clutter
     *
     * ── Non-Samsung path (Android 16+) ───────────────────────────────────────
     * Channel: LIVE_CHANNEL_ID (IMPORTANCE_LOW)
     *   • setRequestPromotedOngoing(true) — system promotes to status bar chip
     *   • POST_PROMOTED_NOTIFICATIONS permission declared in manifest
     *   • setShortCriticalText() — text inside the chip (≤ 7 chars fits fully)
     *   • BigTextStyle — required by Live Update spec
     *   • NO setColorized(true) — would disqualify promotion
     *   • NO RemoteViews — would disqualify promotion
     *   • Falls back to a quiet persistent notification on Android < 16
     */
    private fun buildNotification(primaryText: String, secondaryText: String): Notification {
        val openIntent = Intent(this, NowBriefActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (isSamsung) buildSamsungNotification(primaryText, secondaryText, pendingIntent)
        else                  buildLiveUpdateNotification(primaryText, secondaryText, pendingIntent)
    }

    // ── Samsung: Now Bar only, notification drawer hidden ────────────────────

    private fun buildSamsungNotification(
        primaryText: String,
        secondaryText: String,
        pendingIntent: PendingIntent
    ): Notification {
        val extras = Bundle().apply {
            putInt("android.ongoingActivityNoti.style",              1)
            putString("android.ongoingActivityNoti.primaryInfo",     primaryText)
            putString("android.ongoingActivityNoti.secondaryInfo",   secondaryText)
            putString("android.ongoingActivityNoti.nowbarPrimaryInfo",   "NowBrief")
            putString("android.ongoingActivityNoti.nowbarSecondaryInfo", primaryText)
            putString("android.ongoingActivityNoti.chipExpandedText",    primaryText)
            putInt("android.ongoingActivityNoti.chipBgColor",        0xFF6750A4.toInt())
        }

        val notif = NotificationCompat.Builder(this, SAMSUNG_CHANNEL_ID)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_nowbrief)
            .setContentTitle(primaryText)
            .setContentText(secondaryText)
            .setContentIntent(pendingIntent)
            // IMPORTANCE_MIN channel means this won't peek or appear in drawer
            // but Android still requires a valid foreground notification object
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addExtras(extras)
            .build()

        // semFlags bit 32768 — tells Samsung One UI to show this in the Now Bar
        try {
            val field = Notification::class.java.getDeclaredField("semFlags")
            field.isAccessible = true
            field.setInt(notif, field.getInt(notif) or 32768)
        } catch (_: Exception) {}

        return notif
    }

    // ── Non-Samsung: Android 16 Live Update promoted chip ────────────────────

    private fun buildLiveUpdateNotification(
        primaryText: String,
        secondaryText: String,
        pendingIntent: PendingIntent
    ): Notification {
        // Chip label ≤ 7 chars fits fully in the 96dp pill
        val chipText = Regex("""(\d+)°C""").find(secondaryText)?.value
            ?: listOf("Brief", "News", "Now", "Live")[chipIndex % 4]

        return NotificationCompat.Builder(this, LIVE_CHANNEL_ID)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setSmallIcon(R.drawable.ic_nowbrief)
            .setContentTitle(primaryText)
            .setContentText(secondaryText)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShortCriticalText(chipText)
            .setRequestPromotedOngoing(true)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(secondaryText)
                    .setBigContentTitle(primaryText)
            )
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshJob?.cancel()
        tickJob?.cancel()
        hcJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
