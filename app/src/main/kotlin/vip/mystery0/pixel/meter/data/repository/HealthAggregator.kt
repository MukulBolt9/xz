package com.kakao.taxi.data.repository

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  HEALTH AGGREGATOR — multi-source fallback chain
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Priority order (AUTO mode):
 *   STEPS     : Health Connect → TYPE_STEP_COUNTER → TYPE_STEP_DETECTOR (daily total)
 *   SLEEP     : Health Connect only (no reliable non-HC sleep source on-device)
 *   HEART RATE: Health Connect only
 *
 * NOTE on "Google Fit bridge" / "Samsung Health bridge":
 *   Reading directly from the Google Fit REST API or the Samsung Health SDK
 *   requires a separate OAuth client (Fit) or partner SDK agreement (Samsung) —
 *   neither of which can be wired up without real credentials from you.
 *   In practice, BOTH of those apps already write into Health Connect when
 *   the user enables it under Health Connect → App permissions, which is why
 *   [diagnostics] detects whether they're installed and tells the user to
 *   connect them there — that's the real "bridge".
 *
 * Everything below works fully offline with no extra setup.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** User-selectable health data source. Persisted via [DataStoreRepository.healthSourceMode]. */
enum class HealthSourceMode(val key: String, val label: String) {
    AUTO("auto", "Auto (Recommended)"),
    HEALTH_CONNECT("health_connect", "Health Connect"),
    DEVICE_SENSORS("device_sensors", "Device Sensors");

    companion object {
        fun fromKey(key: String): HealthSourceMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** Diagnostic report explaining what's connected and why data may be missing. */
data class HealthDiagnostics(
    val healthConnectInstalled: Boolean,
    val healthConnectAvailable: Boolean,
    val permissionsGranted: Boolean,
    val stepRecordsAvailable: Boolean,
    val sleepRecordsAvailable: Boolean,
    val heartRateRecordsAvailable: Boolean,
    val samsungHealthInstalled: Boolean,
    val googleFitInstalled: Boolean,
    val deviceSensorAvailable: Boolean,
    val activeSourceLabel: String,
    /** Actionable guidance, or null if everything looks healthy. */
    val message: String?,
)

class HealthAggregator(
    private val context: Context,
    private val hcRepo: HealthConnectRepository,
    private val dataStore: DataStoreRepository,
) {

    // ── App presence checks (does NOT mean they're syncing to HC) ──────────

    fun isSamsungHealthInstalled(): Boolean = isPackageInstalled("com.sec.android.app.shealth")
    fun isGoogleFitInstalled(): Boolean = isPackageInstalled("com.google.android.apps.fitness")

    private fun isPackageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    // ── TYPE_STEP_DETECTOR fallback — call from a long-lived service ────────

    /**
     * Registers a TYPE_STEP_DETECTOR listener that increments the daily DataStore
     * counter on every detected step. Used only when TYPE_STEP_COUNTER AND Health
     * Connect both report no data (rare, but covers some MIUI/custom-ROM devices).
     * Caller must keep the returned listener and call [unregisterStepDetector] in onDestroy.
     */
    fun registerStepDetector(scope: kotlinx.coroutines.CoroutineScope): SensorEventListener? {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if ((e.values.getOrNull(0) ?: 0f) > 0f) {
                    scope.launch { dataStore.incrementStepDetector() }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        return listener
    }

    fun unregisterStepDetector(listener: SensorEventListener) {
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(listener)
    }

    // ── Best snapshot — respects user source mode, applies fallback chain ──

    /**
     * Returns the best-available [HealthConnectRepository.HealthSnapshot]:
     *  - DEVICE_SENSORS mode: skip Health Connect entirely, sensors only.
     *  - HEALTH_CONNECT mode: HC only, no sensor fallback (steps may be -1).
     *  - AUTO (default): HC first; if HC has no step records, fill steps from
     *    TYPE_STEP_COUNTER, then TYPE_STEP_DETECTOR daily total.
     * Also maintains a same-day cache so UI cards show the last good values
     * instead of "--" immediately after a reboot, before the first read completes.
     */
    suspend fun getBestHealthSnapshot(): HealthConnectRepository.HealthSnapshot = withContext(Dispatchers.IO) {
        val mode = HealthSourceMode.fromKey(dataStore.getHealthSourceMode())

        var snap = if (mode == HealthSourceMode.DEVICE_SENSORS) {
            HealthConnectRepository.HealthSnapshot(
                batteryLevel = hcRepo.readBatteryLevel(),
                batteryCharging = hcRepo.readBatteryCharging(),
                source = "device_sensors"
            )
        } else {
            hcRepo.snapshot()
        }

        // Steps fallback chain (skip if user pinned HEALTH_CONNECT explicitly)
        if (mode != HealthSourceMode.HEALTH_CONNECT && snap.steps < 0) {
            val counterSteps = hcRepo.readNativeSteps()
            snap = when {
                counterSteps >= 0 -> snap.copy(
                    steps = counterSteps,
                    source = if (snap.source == "health_connect") "health_connect+sensor" else "device_sensors"
                )
                else -> {
                    val detectorCount = dataStore.getStepDetectorCountToday()
                    if (detectorCount > 0) snap.copy(
                        steps = detectorCount.toLong(),
                        source = if (snap.source == "health_connect") "health_connect+detector" else "device_sensors_detector"
                    ) else snap
                }
            }
        }

        // Persist good values for the same-day "don't show blank" cache
        val toCache = mutableMapOf<String, String>()
        if (snap.steps >= 0) toCache["steps"] = snap.steps.toString()
        if (snap.calories >= 0) toCache["calories"] = snap.calories.toString()
        if (snap.distanceKm >= 0) toCache["distanceKm"] = snap.distanceKm.toString()
        snap.heartRate?.latestBpm?.let { toCache["hr"] = it.toString() }
        snap.sleep?.primary?.actualMinutes?.takeIf { it > 0 }?.let { toCache["sleepMin"] = it.toString() }
        if (toCache.isNotEmpty()) {
            toCache["source"] = snap.source
            dataStore.cacheHealthSnapshot(toCache)
        }

        // Cold-boot fallback: nothing read yet at all -> use last cached values (any age)
        if (snap.steps < 0 && snap.calories < 0 && snap.distanceKm < 0 && snap.sleep == null && snap.heartRate == null) {
            val cached = dataStore.getCachedHealthSnapshotAnyAge()
            if (cached.isNotEmpty()) {
                snap = snap.copy(
                    steps = cached["steps"]?.toLongOrNull() ?: snap.steps,
                    calories = cached["calories"]?.toDoubleOrNull() ?: snap.calories,
                    distanceKm = cached["distanceKm"]?.toDoubleOrNull() ?: snap.distanceKm,
                    source = "${snap.source}_cached"
                )
            }
        }

        snap
    }

    // ── Diagnostics ──────────────────────────────────────────────────────

    suspend fun diagnostics(): HealthDiagnostics = withContext(Dispatchers.IO) {
        val hcInstalled = hcRepo.sdkStatus != HealthConnectClient.SDK_UNAVAILABLE
        val hcAvailable = hcRepo.isAvailable
        val permsOk = if (hcAvailable) hcRepo.hasPermissions() else false
        val samsungInstalled = isSamsungHealthInstalled()
        val fitInstalled = isGoogleFitInstalled()

        val snap = if (hcAvailable && permsOk) hcRepo.snapshot() else null
        val stepsAvail = (snap?.steps ?: -1L) >= 0
        val sleepAvail = snap?.sleep?.primary != null
        val hrAvail = snap?.heartRate != null

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val counterAvail = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

        val activeSourceLabel = when {
            stepsAvail && snap?.source == "health_connect" -> "Connected via Health Connect"
            !hcAvailable && counterAvail -> "Using Device Sensors (offline mode)"
            !hcAvailable -> "No data source available"
            !permsOk -> "Health Connect permission needed"
            counterAvail -> "Using Device Sensors (offline mode)"
            else -> "No data source available"
        }

        val message = when {
            !hcAvailable -> "Health Connect isn't available on this device. Using device sensors where possible."
            !permsOk -> "Health Connect permission not granted yet. Tap Connect to grant access."
            !stepsAvail && samsungInstalled ->
                "Health Connect connected but no step records found. Galaxy device detected — open Samsung Health → Settings → Health Connect → App permissions and allow Steps, Sleep, and Heart rate."
            !stepsAvail && fitInstalled ->
                "Health Connect connected but no step records found. Google Fit is installed as a fallback — open Health Connect → App permissions and enable Google Fit."
            !stepsAvail ->
                "Permission granted, but no apps are currently sharing data with Health Connect. Open Health Connect → App permissions and enable Samsung Health or Google Fit."
            !sleepAvail ->
                "Steps are syncing, but no sleep records were found yet. Enable sleep tracking in Samsung Health (or your wearable app) and allow it under Health Connect → App permissions."
            else -> null
        }

        HealthDiagnostics(
            healthConnectInstalled = hcInstalled,
            healthConnectAvailable = hcAvailable,
            permissionsGranted = permsOk,
            stepRecordsAvailable = stepsAvail,
            sleepRecordsAvailable = sleepAvail,
            heartRateRecordsAvailable = hrAvail,
            samsungHealthInstalled = samsungInstalled,
            googleFitInstalled = fitInstalled,
            deviceSensorAvailable = counterAvail,
            activeSourceLabel = activeSourceLabel,
            message = message,
        )
    }

    companion object {
        /** "Open Health Connect" → app permissions screen for this app. */
        fun openHealthConnectIntent(): Intent =
            Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")

        /** "Open Samsung Health" — falls back gracefully if not installed (caller should catch ActivityNotFoundException). */
        fun openSamsungHealthIntent(): Intent =
            Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.sec.android.app.shealth")
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

        /** "Connect Google Account" — opens system account settings (full Google Sign-In needs OAuth client setup, see chat). */
        fun openGoogleAccountIntent(): Intent =
            Intent(android.provider.Settings.ACTION_SYNC_SETTINGS)

        /** "Open Google Fit" app if installed. */
        fun openGoogleFitIntent(): Intent =
            Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.google.android.apps.fitness")
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
    }
}

