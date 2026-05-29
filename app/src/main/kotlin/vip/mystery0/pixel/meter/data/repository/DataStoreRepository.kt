package com.kakao.taxi.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

const val DATA_STORE_NAME = "pixel_pulse_preferences"
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = DATA_STORE_NAME)

class DataStoreRepository(private val dataStore: DataStore<Preferences>) {

    /** 暴露原始 Preferences Flow，供批量读取初始值 */
    val allPreferences: Flow<Preferences> = dataStore.data

    // Keys mapped from legacy SharedPreferences in NetworkRepository.kt
    companion object {
        val KEY_LIVE_UPDATE = booleanPreferencesKey("key_live_update")
        val KEY_NOTIFICATION_ENABLED = booleanPreferencesKey("key_notification_enabled")
        val KEY_SHOW_ON_LOCKSCREEN = booleanPreferencesKey("key_show_on_lockscreen")
        val KEY_OVERLAY_ENABLED = booleanPreferencesKey("key_overlay_enabled")
        val KEY_OVERLAY_LOCKED = booleanPreferencesKey("key_overlay_locked")
        val KEY_OVERLAY_X = intPreferencesKey("key_overlay_x")
        val KEY_OVERLAY_Y = intPreferencesKey("key_overlay_y")

        val KEY_SAMPLING_INTERVAL = longPreferencesKey("key_sampling_interval")
        val KEY_OVERLAY_BG_COLOR = intPreferencesKey("key_overlay_bg_color")
        val KEY_OVERLAY_TEXT_COLOR = intPreferencesKey("key_overlay_text_color")
        val KEY_OVERLAY_CORNER_RADIUS = intPreferencesKey("key_overlay_corner_radius")
        val KEY_OVERLAY_TEXT_SIZE = floatPreferencesKey("key_overlay_text_size")
        val KEY_OVERLAY_TEXT_UP = stringPreferencesKey("key_overlay_text_up")
        val KEY_OVERLAY_TEXT_DOWN = stringPreferencesKey("key_overlay_text_down")
        val KEY_OVERLAY_ORDER_UP_FIRST = booleanPreferencesKey("key_overlay_order_up_first")
        val KEY_NOTIFICATION_TEXT_UP = stringPreferencesKey("key_notification_text_up")
        val KEY_NOTIFICATION_TEXT_DOWN = stringPreferencesKey("key_notification_text_down")
        val KEY_NOTIFICATION_ORDER_UP_FIRST =
            booleanPreferencesKey("key_notification_order_up_first")
        val KEY_NOTIFICATION_DISPLAY_MODE = intPreferencesKey("key_notification_display_mode")
        val KEY_NOTIFICATION_TEXT_SIZE = floatPreferencesKey("key_notification_text_size")
        val KEY_NOTIFICATION_UNIT_SIZE = floatPreferencesKey("key_notification_unit_size")

        val KEY_HIDE_FROM_RECENTS = booleanPreferencesKey("key_hide_from_recents")
        val KEY_OVERLAY_USE_DEFAULT_COLORS = booleanPreferencesKey("key_overlay_use_default_colors")
        val KEY_AUTO_START_SERVICE = booleanPreferencesKey("key_auto_start_service")
        val KEY_NOTIFICATION_THRESHOLD = longPreferencesKey("key_notification_threshold")
        val KEY_NOTIFICATION_LOW_TRAFFIC_MODE =
            intPreferencesKey("key_notification_low_traffic_mode")
        val KEY_NOTIFICATION_USE_CUSTOM_COLOR =
            booleanPreferencesKey("key_notification_use_custom_color")
        val KEY_NOTIFICATION_COLOR = intPreferencesKey("key_notification_color")
        val KEY_SPEED_UNIT = stringPreferencesKey("key_speed_unit")
        val KEY_OLED_THEME = booleanPreferencesKey("key_oled_theme")
        val KEY_DARK_THEME = booleanPreferencesKey("key_dark_theme")
        val KEY_NEO_THEME  = booleanPreferencesKey("key_neo_theme")
        val KEY_COMPACT_SPEED_TEXT = booleanPreferencesKey("key_compact_speed_text")
        val KEY_BLANK_NOTIFICATION = booleanPreferencesKey("key_blank_notification")
        val KEY_NOTIFICATION_TRANSPARENT_ICON = booleanPreferencesKey("key_notification_transparent_icon")
        val KEY_SKIPPED_UPDATE_VERSION = stringPreferencesKey("key_skipped_update_version")

        // Persisted location so app remembers last known position across restarts
        val KEY_LAST_LAT    = stringPreferencesKey("key_last_lat")
        val KEY_LAST_LON    = stringPreferencesKey("key_last_lon")
        val KEY_LAST_CITY   = stringPreferencesKey("key_last_city")

        // Hydration — resets at midnight via date check
        val KEY_WATER_CUPS  = intPreferencesKey("key_water_cups")
        val KEY_WATER_DATE  = stringPreferencesKey("key_water_date") // "yyyy-MM-dd"

        // Sleep onboarding & history
        val KEY_SLEEP_ONBOARD_DONE   = booleanPreferencesKey("key_sleep_onboard_done")
        val KEY_SLEEP_AVG_TARGET     = floatPreferencesKey("key_sleep_avg_target")    // hours, user's self-reported goal
        val KEY_SLEEP_BEHAVIORS      = stringPreferencesKey("key_sleep_behaviors")    // comma-separated list
        val KEY_SLEEP_HISTORY        = stringPreferencesKey("key_sleep_history")      // JSON array of {date,hours}
        val KEY_SLEEP_SESSION_START  = longPreferencesKey("key_sleep_session_start")  // epoch ms when sleep started
        val KEY_SLEEP_SESSION_ACTIVE = booleanPreferencesKey("key_sleep_session_active")

        // Beta channel — user opts in to receive pre-release builds in addition to stable
        val KEY_BETA_MODE = booleanPreferencesKey("key_beta_mode")

        // Health Connect — persist grant state so UI doesn't reset on refresh
        val KEY_HC_GRANTED = booleanPreferencesKey("key_hc_granted")
    }

    val isLiveUpdateEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_LIVE_UPDATE] ?: true
        }

    val isNotificationEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_NOTIFICATION_ENABLED]
                ?: true // Default TRUE as seen in NetworkRepository
        }

    val isShowOnLockscreenEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_SHOW_ON_LOCKSCREEN] ?: false
        }

    val isOverlayEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_OVERLAY_ENABLED] ?: false
        }

    val isOverlayLocked: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_OVERLAY_LOCKED] ?: false
        }

    val overlayX: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[KEY_OVERLAY_X] ?: 100
        }

    val overlayY: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[KEY_OVERLAY_Y] ?: 200
        }

    val samplingInterval: Flow<Long> = dataStore.data
        .map { preferences ->
            preferences[KEY_SAMPLING_INTERVAL] ?: 1500L
        }

    val overlayBgColor: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[KEY_OVERLAY_BG_COLOR]
                ?: 0xCC000000.toInt() // Default semi-transparent black
        }

    val overlayTextColor: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[KEY_OVERLAY_TEXT_COLOR]
                ?: 0xFFFFFFFF.toInt() // Default white
        }

    val overlayCornerRadius: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[KEY_OVERLAY_CORNER_RADIUS] ?: 8
        }

    val overlayTextSize: Flow<Float> = dataStore.data
        .map { preferences ->
            preferences[KEY_OVERLAY_TEXT_SIZE] ?: 10f
        }

    val overlayTextUp: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[KEY_OVERLAY_TEXT_UP] ?: "▲ "
        }

    val overlayTextDown: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[KEY_OVERLAY_TEXT_DOWN] ?: "▼ "
        }

    val overlayOrderUpFirst: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_OVERLAY_ORDER_UP_FIRST] ?: false // Default FALSE (Download first)
        }

    val notificationTextUp: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[KEY_NOTIFICATION_TEXT_UP] ?: "▲ "
        }

    val notificationTextDown: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[KEY_NOTIFICATION_TEXT_DOWN] ?: "▼ "
        }

    val notificationOrderUpFirst: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_NOTIFICATION_ORDER_UP_FIRST] ?: false // Default FALSE (Download first)
        }

    val notificationDisplayMode: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[KEY_NOTIFICATION_DISPLAY_MODE] ?: 0 // 0: Total, 1: Up, 2: Down
        }

    val notificationTextSize: Flow<Float> = dataStore.data
        .map { preferences ->
            preferences[KEY_NOTIFICATION_TEXT_SIZE] ?: 0.60f
        }

    val notificationUnitSize: Flow<Float> = dataStore.data
        .map { preferences ->
            preferences[KEY_NOTIFICATION_UNIT_SIZE] ?: 0.45f
        }

    val notificationThreshold: Flow<Long> = dataStore.data
        .map { preferences ->
            preferences[KEY_NOTIFICATION_THRESHOLD] ?: 0L
        }

    val notificationLowTrafficMode: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[KEY_NOTIFICATION_LOW_TRAFFIC_MODE] ?: 0 // 0: Static, 1: Dynamic
        }

    val notificationUseCustomColor: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_NOTIFICATION_USE_CUSTOM_COLOR] ?: true
        }

    val notificationColor: Flow<Int> = dataStore.data
        .map { preferences ->
            preferences[KEY_NOTIFICATION_COLOR] ?: 0xFF888888.toInt() // Default Gray
        }

    suspend fun setLiveUpdateEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_LIVE_UPDATE] = enabled
            if (enabled) {
                preferences[KEY_BLANK_NOTIFICATION] = false
            }
        }
    }

    suspend fun setNotificationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_ENABLED] = enabled
        }
    }

    suspend fun setShowOnLockscreenEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_SHOW_ON_LOCKSCREEN] = enabled
        }
    }

    suspend fun setOverlayEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_OVERLAY_ENABLED] = enabled
        }
    }

    suspend fun setOverlayLocked(locked: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_OVERLAY_LOCKED] = locked
        }
    }

    suspend fun saveOverlayPosition(x: Int, y: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_OVERLAY_X] = x
            preferences[KEY_OVERLAY_Y] = y
        }
    }

    suspend fun setSamplingInterval(interval: Long) {
        dataStore.edit { preferences ->
            preferences[KEY_SAMPLING_INTERVAL] = interval
        }
    }

    suspend fun setOverlayBgColor(color: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_OVERLAY_BG_COLOR] = color
        }
    }

    suspend fun setOverlayTextColor(color: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_OVERLAY_TEXT_COLOR] = color
        }
    }

    suspend fun setOverlayCornerRadius(radius: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_OVERLAY_CORNER_RADIUS] = radius
        }
    }

    suspend fun setOverlayTextSize(size: Float) {
        dataStore.edit { preferences ->
            preferences[KEY_OVERLAY_TEXT_SIZE] = size
        }
    }

    suspend fun setOverlayTextUp(text: String) {
        dataStore.edit { preferences ->
            preferences[KEY_OVERLAY_TEXT_UP] = text
        }
    }

    suspend fun setOverlayTextDown(text: String) {
        dataStore.edit { preferences ->
            preferences[KEY_OVERLAY_TEXT_DOWN] = text
        }
    }

    suspend fun setOverlayOrderUpFirst(upFirst: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_OVERLAY_ORDER_UP_FIRST] = upFirst
        }
    }

    suspend fun setNotificationTextUp(text: String) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_TEXT_UP] = text
        }
    }

    suspend fun setNotificationTextDown(text: String) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_TEXT_DOWN] = text
        }
    }

    suspend fun setNotificationOrderUpFirst(upFirst: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_ORDER_UP_FIRST] = upFirst
        }
    }

    suspend fun setNotificationDisplayMode(mode: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_DISPLAY_MODE] = mode
        }
    }

    suspend fun setNotificationTextSize(size: Float) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_TEXT_SIZE] = size
        }
    }

    suspend fun setNotificationUnitSize(size: Float) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_UNIT_SIZE] = size
        }
    }

    suspend fun setNotificationThreshold(threshold: Long) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_THRESHOLD] = threshold
        }
    }

    suspend fun setNotificationLowTrafficMode(mode: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_LOW_TRAFFIC_MODE] = mode
        }
    }

    suspend fun setNotificationUseCustomColor(useCustom: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_USE_CUSTOM_COLOR] = useCustom
        }
    }

    suspend fun setNotificationColor(color: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_COLOR] = color
        }
    }

    val isHideFromRecents: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_HIDE_FROM_RECENTS] ?: false
        }

    suspend fun setHideFromRecents(hide: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_HIDE_FROM_RECENTS] = hide
        }
    }

    val isOverlayUseDefaultColors: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_OVERLAY_USE_DEFAULT_COLORS] ?: false
        }

    suspend fun setOverlayUseDefaultColors(useDefault: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_OVERLAY_USE_DEFAULT_COLORS] = useDefault
        }
    }

    val isAutoStartServiceEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_AUTO_START_SERVICE] ?: false
        }

    suspend fun setAutoStartServiceEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_AUTO_START_SERVICE] = enabled
        }
    }

    val speedUnit: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[KEY_SPEED_UNIT] ?: "0"
        }

    suspend fun setSpeedUnit(unit: String) {
        dataStore.edit { preferences ->
            preferences[KEY_SPEED_UNIT] = unit
        }
    }

    val isOledThemeEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_OLED_THEME] ?: false
        }

    suspend fun setOledThemeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_OLED_THEME] = enabled
        }
    }

    val isDarkThemeEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_DARK_THEME] ?: false
        }

    suspend fun setDarkThemeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_DARK_THEME] = enabled
        }
    }

    val isNeoThemeEnabled: kotlinx.coroutines.flow.Flow<Boolean> = dataStore.data
        .map { preferences -> preferences[KEY_NEO_THEME] ?: false }

    suspend fun setNeoThemeEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_NEO_THEME] = enabled }
    }

    val isCompactSpeedTextEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_COMPACT_SPEED_TEXT] ?: true
        }

    suspend fun setCompactSpeedTextEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_COMPACT_SPEED_TEXT] = enabled
        }
    }

    val isBlankNotificationEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_BLANK_NOTIFICATION] ?: false
        }

    suspend fun setBlankNotificationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_BLANK_NOTIFICATION] = enabled
            if (enabled) {
                preferences[KEY_LIVE_UPDATE] = false
            }
        }
    }

    val isNotificationTransparentIconEnabled: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[KEY_NOTIFICATION_TRANSPARENT_ICON] ?: false
        }

    suspend fun setNotificationTransparentIconEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_TRANSPARENT_ICON] = enabled
        }
    }

    val skippedUpdateVersion: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[KEY_SKIPPED_UPDATE_VERSION] ?: ""
        }

    suspend fun setSkippedUpdateVersion(version: String) {
        dataStore.edit { preferences ->
            preferences[KEY_SKIPPED_UPDATE_VERSION] = version
        }
    }

    suspend fun saveLocation(lat: Double, lon: Double, city: String) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_LAT]  = lat.toString()
            preferences[KEY_LAST_LON]  = lon.toString()
            preferences[KEY_LAST_CITY] = city
        }
    }

    suspend fun loadLocation(): Triple<Double, Double, String>? {
        return try {
            val prefs = dataStore.data.first()
            val lat  = prefs[KEY_LAST_LAT]?.toDoubleOrNull()  ?: return null
            val lon  = prefs[KEY_LAST_LON]?.toDoubleOrNull()  ?: return null
            val city = prefs[KEY_LAST_CITY]                   ?: return null
            Triple(lat, lon, city)
        } catch (e: Exception) { null }
    }

    /** Water cups — auto-resets if saved date != today */
    suspend fun loadWaterCups(): Int {
        return try {
            val prefs   = dataStore.data.first()
            val today   = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            val saved   = prefs[KEY_WATER_DATE] ?: ""
            if (saved == today) prefs[KEY_WATER_CUPS] ?: 0 else 0
        } catch (_: Exception) { 0 }
    }

    suspend fun saveWaterCups(cups: Int) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        dataStore.edit { prefs ->
            prefs[KEY_WATER_CUPS] = cups
            prefs[KEY_WATER_DATE] = today
        }
    }

    // ── Sleep onboarding ──────────────────────────────────────────────
    suspend fun isSleepOnboardDone(): Boolean =
        dataStore.data.first()[KEY_SLEEP_ONBOARD_DONE] ?: false

    suspend fun saveSleepOnboarding(avgTargetHours: Float, behaviors: List<String>) {
        dataStore.edit { prefs ->
            prefs[KEY_SLEEP_ONBOARD_DONE]  = true
            prefs[KEY_SLEEP_AVG_TARGET]    = avgTargetHours
            prefs[KEY_SLEEP_BEHAVIORS]     = behaviors.joinToString(",")
        }
    }

    suspend fun loadSleepOnboarding(): Pair<Float, List<String>> {
        val prefs = dataStore.data.first()
        val target    = prefs[KEY_SLEEP_AVG_TARGET]  ?: 7.5f
        val behaviors = (prefs[KEY_SLEEP_BEHAVIORS]  ?: "").split(",").filter { it.isNotBlank() }
        return Pair(target, behaviors)
    }

    // ── Sleep session tracking ────────────────────────────────────────
    suspend fun startSleepSession(startMs: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_SLEEP_SESSION_START]  = startMs
            prefs[KEY_SLEEP_SESSION_ACTIVE] = true
        }
    }

    suspend fun endSleepSession(endMs: Long) {
        val prefs = dataStore.data.first()
        val startMs = prefs[KEY_SLEEP_SESSION_START] ?: return
        val active  = prefs[KEY_SLEEP_SESSION_ACTIVE] ?: false
        if (!active) return
        val hours = (endMs - startMs) / (1000f * 60 * 60)
        if (hours < 1f) { // ignore micro-sessions
            dataStore.edit { it[KEY_SLEEP_SESSION_ACTIVE] = false }
            return
        }
        val today  = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(endMs))
        val entry  = """{"date":"$today","hours":${"%.2f".format(hours)},"start":$startMs,"end":$endMs}"""
        val existing = (prefs[KEY_SLEEP_HISTORY] ?: "[]").trimStart('[').trimEnd(']')
        val updated  = if (existing.isBlank()) "[$entry]" else "[$existing,$entry]"
        dataStore.edit { p ->
            p[KEY_SLEEP_HISTORY]       = updated
            p[KEY_SLEEP_SESSION_ACTIVE]= false
        }
    }

    suspend fun loadTodaySleepHours(): Float {
        return try {
            val prefs = dataStore.data.first()
            // If session active right now, return elapsed so far
            val active = prefs[KEY_SLEEP_SESSION_ACTIVE] ?: false
            if (active) {
                val start = prefs[KEY_SLEEP_SESSION_START] ?: return 0f
                return ((System.currentTimeMillis() - start) / (1000f * 60 * 60)).coerceAtMost(16f)
            }
            val history = prefs[KEY_SLEEP_HISTORY] ?: return 0f
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            // Parse last entry that matches today
            val regex = Regex(""""date"\s*:\s*"($today)"\s*,\s*"hours"\s*:\s*([\d.]+)""")
            val matches = regex.findAll(history).toList()
            matches.lastOrNull()?.groupValues?.get(2)?.toFloatOrNull() ?: 0f
        } catch (_: Exception) { 0f }
    }

    suspend fun loadSleepHistory(days: Int = 7): List<Pair<String, Float>> {
        return try {
            val history = dataStore.data.first()[KEY_SLEEP_HISTORY] ?: return emptyList()
            val regex = Regex(""""date"\s*:\s*"([^"]+)"\s*,\s*"hours"\s*:\s*([\d.]+)""")
            regex.findAll(history).map {
                Pair(it.groupValues[1], it.groupValues[2].toFloat())
            }.toList().takeLast(days)
        } catch (_: Exception) { emptyList() }
    }

    suspend fun isSleepSessionActive(): Boolean =
        dataStore.data.first()[KEY_SLEEP_SESSION_ACTIVE] ?: false

    suspend fun getSleepSessionStart(): Long =
        dataStore.data.first()[KEY_SLEEP_SESSION_START] ?: 0L

    // ── Beta channel ──────────────────────────────────────────────────
    val isBetaModeEnabled: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_BETA_MODE] ?: false }

    suspend fun setBetaModeEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_BETA_MODE] = enabled }
    }

    // ── Health Connect grant persistence ──────────────────────────────
    val isHcGranted: Flow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_HC_GRANTED] ?: false }

    suspend fun setHcGranted(granted: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_HC_GRANTED] = granted }
    }
}
