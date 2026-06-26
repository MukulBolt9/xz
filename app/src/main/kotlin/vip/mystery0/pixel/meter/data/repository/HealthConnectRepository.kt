package com.kakao.taxi.data.repository

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume

private const val TAG = "HCRepo"

/**
 * Native Health Connect repository — zero Samsung Health dependency.
 *
 * ═══════════════════════════════════════════════════════════════
 *  SLEEP LOGIC  (YESTERDAY / TODAY-NAP phases)
 * ═══════════════════════════════════════════════════════════════
 *
 * PHASE A — YESTERDAY_NIGHT  (no nap detected yet today)
 *   Window : yesterday noon → today 06:00 AM
 *   Filter : only sessions starting at/after yesterday 18:00 or before today 06:00
 *   Display: combined night total + stage breakdown
 *   Goal   : user's NIGHT goal (default 7.5h)
 *
 * PHASE B — TODAY_NAP  (user has slept past today noon)
 *   Window : today 06:00 AM → now
 *   Trigger: any session ending after today noon with actualMinutes ≥ 30
 *   Display: combined nap total
 *   Goal   : user's NAP goal (default 1.5h)
 *
 * ═══════════════════════════════════════════════════════════════
 *  HC FORCE-DATA STRATEGY
 * ═══════════════════════════════════════════════════════════════
 *  • freshClient() — new HealthConnectClient.getOrCreate() every call (no stale cache)
 *  • 2-ring fetch — retry once with 300ms gap on any exception
 *  • snapshot() runs ALL reads in parallel (coroutineScope + async)
 *  • Per-read 8s timeout — no single read can block the whole snapshot
 *  • Partial data returned even if some reads fail (null/-1 for that field)
 * ═══════════════════════════════════════════════════════════════
 */
class HealthConnectRepository(private val context: Context) {

    // ── Availability ──────────────────────────────────────────────────────────

    val sdkStatus: Int by lazy { HealthConnectClient.getSdkStatus(context) }
    val isAvailable: Boolean get() = sdkStatus == HealthConnectClient.SDK_AVAILABLE
    val needsUpdate: Boolean
        get() = sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    /** Fresh client every call — stale cached clients silently return empty lists. */
    private fun freshClient(): HealthConnectClient? =
        if (isAvailable) try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            Log.e(TAG, "freshClient: ${e.message}"); null
        } else null

    val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    )

    suspend fun hasPermissions(): Boolean = withContext(Dispatchers.IO) {
        val c = freshClient() ?: return@withContext false
        try { c.permissionController.getGrantedPermissions().containsAll(requiredPermissions) }
        catch (e: Exception) { Log.w(TAG, "hasPermissions: ${e.message}"); false }
    }

    // ── Live snapshot StateFlow ───────────────────────────────────────────────

    private val _liveSnapshot = MutableStateFlow<HealthSnapshot?>(null)
    val liveSnapshot: StateFlow<HealthSnapshot?> = _liveSnapshot.asStateFlow()

    // ── 2-ring fetch — retry once with fresh client on failure ────────────────

    private suspend fun <T> hcFetch(block: suspend (HealthConnectClient) -> T): T? {
        repeat(2) { ring ->
            val c = freshClient() ?: return null
            try { return block(c) }
            catch (e: Exception) {
                Log.w(TAG, "hcFetch ring $ring: ${e.message}")
                if (ring == 0) delay(300L)
            }
        }
        return null
    }

    /** hcFetch with per-call timeout so no single read can block the snapshot. */
    private suspend fun <T> hcFetchTimed(timeoutMs: Long = 8_000L, block: suspend (HealthConnectClient) -> T): T? =
        withTimeoutOrNull(timeoutMs) { hcFetch(block) }

    // ── Time helpers ──────────────────────────────────────────────────────────

    private val zone get() = ZoneId.systemDefault()

    private fun todayStart(): Instant = LocalDate.now(zone).atStartOfDay(zone).toInstant()
    private fun now(): Instant = Instant.now()
    private fun yesterdayNoon(): Instant =
        LocalDate.now(zone).minusDays(1).atTime(12, 0).atZone(zone).toInstant()
    private fun today6am(): Instant =
        LocalDate.now(zone).atTime(6, 0).atZone(zone).toInstant()
    private fun todayNoon(): Instant =
        LocalDate.now(zone).atTime(12, 0).atZone(zone).toInstant()

    // ── Steps ─────────────────────────────────────────────────────────────────

    suspend fun readStepsToday(): Long = withContext(Dispatchers.IO) {
        hcFetchTimed { c ->
            c.readRecords(ReadRecordsRequest(StepsRecord::class,
                TimeRangeFilter.between(todayStart(), now()))).records.sumOf { it.count }
        } ?: -1L
    }

    // ── Sleep — data classes ──────────────────────────────────────────────────

    data class SleepStageBreakdown(
        val totalMinutes:  Long,
        val actualMinutes: Long,
        val deepMinutes:   Long,
        val remMinutes:    Long,
        val lightMinutes:  Long,
        val awakMinutes:   Long,
        val efficiency:    Int,
        val sessionStart:  Instant?,
        val sessionEnd:    Instant?,
        val hasStageData:  Boolean
    )

    enum class SleepDisplayMode { YESTERDAY_NIGHT, TODAY_NAP }

    data class SleepResult(
        val primary:   SleepStageBreakdown?,
        val mode:      SleepDisplayMode,
        val todayNaps: List<SleepStageBreakdown> = emptyList(),
        val napCount:  Int = 0
    )

    // ── Sleep — helpers ───────────────────────────────────────────────────────

    private fun breakdown(s: SleepSessionRecord): SleepStageBreakdown {
        val bed = Duration.between(s.startTime, s.endTime).toMinutes()
        if (s.stages.isEmpty())
            return SleepStageBreakdown(bed, bed, 0L, 0L, 0L, 0L, 100, s.startTime, s.endTime, false)
        var actual = 0L; var deep = 0L; var rem = 0L; var light = 0L; var awak = 0L
        for (st in s.stages) {
            val m = Duration.between(st.startTime, st.endTime).toMinutes()
            when (st.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP       -> { deep  += m; actual += m }
                SleepSessionRecord.STAGE_TYPE_REM        -> { rem   += m; actual += m }
                SleepSessionRecord.STAGE_TYPE_LIGHT      -> { light += m; actual += m }
                SleepSessionRecord.STAGE_TYPE_SLEEPING   -> {              actual += m }
                SleepSessionRecord.STAGE_TYPE_AWAKE,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> { awak  += m }
            }
        }
        val eff = if (bed > 0) ((actual * 100) / bed).toInt().coerceIn(0, 100) else 0
        return SleepStageBreakdown(bed, actual, deep, rem, light, awak, eff, s.startTime, s.endTime, true)
    }

    /** Merge sessions whose gap ≤ 30 min into chains; return the chain with most actual sleep. */
    private fun mergeAndPickBest(sessions: List<SleepSessionRecord>): SleepStageBreakdown? {
        if (sessions.isEmpty()) return null
        val sorted = sessions.sortedBy { it.startTime }
        val chains = mutableListOf<MutableList<SleepSessionRecord>>()
        var cur = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val gap = Duration.between(sorted[i - 1].endTime, sorted[i].startTime).toMinutes()
            if (gap <= 30) cur.add(sorted[i]) else { chains.add(cur); cur = mutableListOf(sorted[i]) }
        }
        chains.add(cur)
        return chains.map { chain ->
            val parts = chain.map { breakdown(it) }
            val first = chain.first().startTime; val last = chain.last().endTime
            val total = Duration.between(first, last).toMinutes()
            val act = parts.sumOf { it.actualMinutes }
            val eff = if (total > 0) ((act * 100) / total).toInt().coerceIn(0, 100) else 0
            SleepStageBreakdown(total, act, parts.sumOf { it.deepMinutes }, parts.sumOf { it.remMinutes },
                parts.sumOf { it.lightMinutes }, parts.sumOf { it.awakMinutes }, eff,
                first, last, parts.any { it.hasStageData })
        }.maxByOrNull { it.actualMinutes }
    }

    /** Combine a flat list of breakdowns into one aggregate block. */
    private fun combine(parts: List<SleepStageBreakdown>, start: Instant?, end: Instant?): SleepStageBreakdown? {
        if (parts.isEmpty()) return null
        val total = if (start != null && end != null) Duration.between(start, end).toMinutes()
                    else parts.sumOf { it.totalMinutes }
        val act = parts.sumOf { it.actualMinutes }
        val eff = if (total > 0) ((act * 100) / total).toInt().coerceIn(0, 100) else 0
        return SleepStageBreakdown(total, act, parts.sumOf { it.deepMinutes }, parts.sumOf { it.remMinutes },
            parts.sumOf { it.lightMinutes }, parts.sumOf { it.awakMinutes }, eff,
            start ?: parts.first().sessionStart, end ?: parts.last().sessionEnd,
            parts.any { it.hasStageData })
    }

    // ── Sleep — main read ─────────────────────────────────────────────────────

    suspend fun readSleep(): SleepResult? = withContext(Dispatchers.IO) {
        // Step 1: today nap window (separate query + own 2-ring)
        val napRecords = hcFetchTimed { c ->
            c.readRecords(ReadRecordsRequest(SleepSessionRecord::class,
                TimeRangeFilter.between(today6am(), now()))).records
        } ?: emptyList()

        val napBreakdowns = napRecords.map { breakdown(it) }
            .filter { it.actualMinutes >= 30 }
            .sortedBy { it.sessionStart }

        if (napBreakdowns.isNotEmpty()) {
            val primary = combine(napBreakdowns, napBreakdowns.first().sessionStart, napBreakdowns.last().sessionEnd)
            Log.d(TAG, "sleep: TODAY_NAP ${napBreakdowns.size} naps actual=${primary?.actualMinutes}min")
            return@withContext SleepResult(primary, SleepDisplayMode.TODAY_NAP, napBreakdowns, napBreakdowns.size)
        }

        // Step 2: yesterday night window
        val nightRecords = hcFetchTimed { c ->
            c.readRecords(ReadRecordsRequest(SleepSessionRecord::class,
                TimeRangeFilter.between(yesterdayNoon(), today6am()))).records
        } ?: return@withContext null

        if (nightRecords.isEmpty()) return@withContext null

        val yesterday6pm = LocalDate.now(zone).minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        val candidates = nightRecords.filter { it.startTime >= yesterday6pm || it.startTime < today6am() }
            .ifEmpty { nightRecords }

        val sorted   = candidates.sortedBy { it.startTime }
        val allParts = sorted.map { breakdown(it) }
        val merged   = mergeAndPickBest(candidates)
        val combined = combine(allParts, sorted.first().startTime, sorted.last().endTime)

        val primary = when {
            merged == null -> combined
            combined == null -> merged
            combined.actualMinutes >= merged.actualMinutes -> combined
            else -> merged
        }

        Log.d(TAG, "sleep: YESTERDAY_NIGHT ${candidates.size} sessions actual=${primary?.actualMinutes}min eff=${primary?.efficiency}%")
        if (primary == null || primary.actualMinutes < 15) return@withContext null

        SleepResult(primary, SleepDisplayMode.YESTERDAY_NIGHT)
    }

    // ── Heart Rate ────────────────────────────────────────────────────────────

    data class HeartRateResult(
        val restingBpm: Int,
        val maxBpm: Int,
        val minBpm: Int,
        val latestBpm: Int,
        val avgBpm: Int
    )

    suspend fun readHeartRateToday(): HeartRateResult? = withContext(Dispatchers.IO) {
        val records = hcFetchTimed { c ->
            c.readRecords(ReadRecordsRequest(HeartRateRecord::class,
                TimeRangeFilter.between(todayStart(), now()))).records
        } ?: return@withContext null
        val samples = records.flatMap { it.samples }
        if (samples.isEmpty()) return@withContext null
        val bpms = samples.map { it.beatsPerMinute }.sorted()
        HeartRateResult(
            restingBpm = bpms[(bpms.size * 0.10).toInt().coerceAtLeast(0)].toInt(),
            maxBpm     = bpms.last().toInt(),
            minBpm     = bpms.first().toInt(),
            latestBpm  = samples.last().beatsPerMinute.toInt(),
            avgBpm     = (bpms.sum() / bpms.size).toInt()
        )
    }

    // ── Calories ──────────────────────────────────────────────────────────────

    suspend fun readCaloriesToday(): Double = withContext(Dispatchers.IO) {
        hcFetchTimed { c ->
            c.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class,
                TimeRangeFilter.between(todayStart(), now()))).records.sumOf { it.energy.inKilocalories }
        } ?: -1.0
    }

    // ── Distance ──────────────────────────────────────────────────────────────

    suspend fun readDistanceTodayKm(): Double = withContext(Dispatchers.IO) {
        hcFetchTimed { c ->
            c.readRecords(ReadRecordsRequest(DistanceRecord::class,
                TimeRangeFilter.between(todayStart(), now()))).records.sumOf { it.distance.inKilometers }
        } ?: -1.0
    }

    // ── Blood Oxygen ──────────────────────────────────────────────────────────

    suspend fun readLatestSpO2(): Double? = withContext(Dispatchers.IO) {
        hcFetchTimed { c ->
            c.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class,
                TimeRangeFilter.between(todayStart(), now()))).records.lastOrNull()?.percentage?.value
        }
    }

    // ── Native sensors ────────────────────────────────────────────────────────

    suspend fun readNativeSteps(): Long = withContext(Dispatchers.IO) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return@withContext -1L
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return@withContext -1L
        withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values[0].toLong())
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            }
        } ?: -1L
    }

    fun readBatteryLevel(): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    fun readBatteryCharging(): Boolean =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging ?: false

    // ── Composite snapshot — ALL reads in parallel, per-read timeout ──────────

    data class HealthSnapshot(
        val steps:           Long             = -1L,
        val calories:        Double           = -1.0,
        val distanceKm:      Double           = -1.0,
        val spO2:            Double?          = null,
        val heartRate:       HeartRateResult? = null,
        val sleep:           SleepResult?     = null,
        val batteryLevel:    Int              = -1,
        val batteryCharging: Boolean          = false,
        val source:          String           = "health_connect"
    )

    /**
     * Builds a full snapshot immediately.
     * • All HC reads fire in PARALLEL via coroutineScope + async.
     * • Each read has its own 8s timeout and 2-ring retry.
     * • Battery reads are instant (no timeout needed).
     * • Partial snapshot returned even if some reads fail.
     * • Result pushed to [liveSnapshot] StateFlow for UI observers.
     */
    suspend fun snapshot(): HealthSnapshot = withContext(Dispatchers.IO) {
        val bat   = readBatteryLevel()
        val charg = readBatteryCharging()

        val available = isAvailable
        val permsOk   = if (available) hasPermissions() else false
        Log.d(TAG, "snapshot START: HC=$available perms=$permsOk bat=$bat%")

        if (!available || !permsOk) {
            val snap = HealthSnapshot(batteryLevel = bat, batteryCharging = charg,
                source = if (!available) "native_no_hc" else "native_no_perm")
            _liveSnapshot.value = snap
            Log.d(TAG, "snapshot DONE (native-only): $snap")
            return@withContext snap
        }

        // All reads in parallel — fastest possible snapshot
        val snap = coroutineScope {
            val dSteps    = async { hcFetchTimed { c -> c.readRecords(ReadRecordsRequest(StepsRecord::class,
                TimeRangeFilter.between(todayStart(), now()))).records.sumOf { it.count } } ?: -1L }
            val dCals     = async { hcFetchTimed { c -> c.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class,
                TimeRangeFilter.between(todayStart(), now()))).records.sumOf { it.energy.inKilocalories } } ?: -1.0 }
            val dDist     = async { hcFetchTimed { c -> c.readRecords(ReadRecordsRequest(DistanceRecord::class,
                TimeRangeFilter.between(todayStart(), now()))).records.sumOf { it.distance.inKilometers } } ?: -1.0 }
            val dSpo2     = async { hcFetchTimed { c -> c.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class,
                TimeRangeFilter.between(todayStart(), now()))).records.lastOrNull()?.percentage?.value } }
            val dHr       = async { readHeartRateToday() }
            val dSleep    = async { readSleep() }

            HealthSnapshot(
                steps           = dSteps.await(),
                calories        = dCals.await(),
                distanceKm      = dDist.await(),
                spO2            = dSpo2.await(),
                heartRate       = dHr.await(),
                sleep           = dSleep.await(),
                batteryLevel    = bat,
                batteryCharging = charg,
                source          = "health_connect"
            )
        }

        _liveSnapshot.value = snap
        Log.d(TAG, "snapshot DONE: steps=${snap.steps} sleep=${snap.sleep?.mode}/" +
              "${snap.sleep?.primary?.actualMinutes}min hr=${snap.heartRate?.latestBpm}bpm bat=$bat%")
        snap
    }
}
