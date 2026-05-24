package com.kakao.taxi.data.repository

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "HealthConnectRepo"

/**
 * Reads health data from Health Connect (Samsung Health, Google Health, Garmin, Whoop, etc.).
 * Falls back gracefully when HC is not installed or permission is not granted.
 */
class HealthConnectRepository(private val context: Context) {

    // ── Availability ───────────────────────────────────────────────────────────

    val isAvailable: Boolean by lazy {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    private val client: HealthConnectClient? by lazy {
        if (isAvailable) HealthConnectClient.getOrCreate(context) else null
    }

    val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    )

    suspend fun hasPermissions(): Boolean {
        val c = client ?: return false
        return c.permissionController.getGrantedPermissions()
            .containsAll(requiredPermissions)
    }

    // ── Time helpers ───────────────────────────────────────────────────────────

    private fun todayStart(): Instant =
        LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault()).toInstant()

    private fun todayEnd(): Instant = Instant.now()

    private fun yesterdayStart(): Instant =
        LocalDate.now(ZoneId.systemDefault()).minusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant()

    // ── Step count ────────────────────────────────────────────────────────────

    /**
     * Returns total steps today from Health Connect.
     * HC aggregates across all sources (Samsung Health, Pixel health, etc.)
     * so this is much more accurate than the raw pedometer sensor.
     */
    suspend fun readStepsToday(): Long {
        val c = client ?: return -1L
        return try {
            val response = c.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(todayStart(), todayEnd())
                )
            )
            response.records.sumOf { it.count }
        } catch (e: Exception) {
            Log.w(TAG, "readStepsToday failed: ${e.message}")
            -1L
        }
    }

    // ── Sleep ─────────────────────────────────────────────────────────────────

    data class SleepResult(
        val totalMinutes: Long,
        val lightMinutes: Long,
        val deepMinutes: Long,
        val remMinutes: Long,
        val awakMinutes: Long,
        val sessionStart: Instant?,
        val sessionEnd: Instant?
    )

    /**
     * Returns last night's sleep from Health Connect with full stage breakdown.
     * Looks back 18 hours to catch early sleepers.
     */
    suspend fun readLastSleep(): SleepResult? {
        val c = client ?: return null
        return try {
            val lookback = Instant.now().minusSeconds(18 * 3600)
            val response = c.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(lookback, Instant.now())
                )
            )

            // Pick the longest session (main sleep, not a nap)
            val main = response.records
                .filter {
                    val dur = (it.endTime.epochSecond - it.startTime.epochSecond) / 3600f
                    dur >= 1.5f
                }
                .maxByOrNull { it.endTime.epochSecond - it.startTime.epochSecond }
                ?: return null

            var light = 0L; var deep = 0L; var rem = 0L; var awak = 0L
            main.stages.forEach { stage ->
                val min = (stage.endTime.epochSecond - stage.startTime.epochSecond) / 60
                when (stage.stage) {
                    SleepSessionRecord.STAGE_TYPE_LIGHT  -> light += min
                    SleepSessionRecord.STAGE_TYPE_DEEP   -> deep  += min
                    SleepSessionRecord.STAGE_TYPE_REM    -> rem   += min
                    SleepSessionRecord.STAGE_TYPE_AWAKE  -> awak  += min
                }
            }
            val total = (main.endTime.epochSecond - main.startTime.epochSecond) / 60

            SleepResult(
                totalMinutes = total,
                lightMinutes = light,
                deepMinutes  = deep,
                remMinutes   = rem,
                awakMinutes  = awak,
                sessionStart = main.startTime,
                sessionEnd   = main.endTime
            )
        } catch (e: Exception) {
            Log.w(TAG, "readLastSleep failed: ${e.message}")
            null
        }
    }

    // ── Heart rate ────────────────────────────────────────────────────────────

    data class HeartRateResult(
        val restingBpm: Int,
        val maxBpm: Int,
        val minBpm: Int,
        val latestBpm: Int
    )

    suspend fun readHeartRateToday(): HeartRateResult? {
        val c = client ?: return null
        return try {
            val response = c.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(todayStart(), todayEnd())
                )
            )
            val allSamples = response.records.flatMap { it.samples }
            if (allSamples.isEmpty()) return null

            val bpms = allSamples.map { it.beatsPerMinute }
            // Approximate resting HR as the 10th percentile
            val sorted = bpms.sorted()
            val resting = sorted[(sorted.size * 0.10).toInt().coerceAtLeast(0)].toInt()

            HeartRateResult(
                restingBpm = resting,
                maxBpm     = sorted.last().toInt(),
                minBpm     = sorted.first().toInt(),
                latestBpm  = allSamples.last().beatsPerMinute.toInt()
            )
        } catch (e: Exception) {
            Log.w(TAG, "readHeartRateToday failed: ${e.message}")
            null
        }
    }

    // ── Calories ──────────────────────────────────────────────────────────────

    suspend fun readCaloriesToday(): Double {
        val c = client ?: return -1.0
        return try {
            val response = c.readRecords(
                ReadRecordsRequest(
                    recordType = TotalCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(todayStart(), todayEnd())
                )
            )
            response.records.sumOf { it.energy.inKilocalories }
        } catch (e: Exception) {
            Log.w(TAG, "readCaloriesToday failed: ${e.message}")
            -1.0
        }
    }

    // ── Distance ──────────────────────────────────────────────────────────────

    suspend fun readDistanceTodayKm(): Double {
        val c = client ?: return -1.0
        return try {
            val response = c.readRecords(
                ReadRecordsRequest(
                    recordType = DistanceRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(todayStart(), todayEnd())
                )
            )
            response.records.sumOf { it.distance.inKilometers }
        } catch (e: Exception) {
            Log.w(TAG, "readDistanceTodayKm failed: ${e.message}")
            -1.0
        }
    }

    // ── Blood Oxygen ──────────────────────────────────────────────────────────

    suspend fun readLatestSpO2(): Double? {
        val c = client ?: return null
        return try {
            val response = c.readRecords(
                ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(todayStart(), todayEnd())
                )
            )
            response.records.lastOrNull()?.percentage?.value
        } catch (e: Exception) {
            Log.w(TAG, "readLatestSpO2 failed: ${e.message}")
            null
        }
    }

    // ── Composite snapshot ────────────────────────────────────────────────────

    /**
     * Single call that fetches everything and returns a unified snapshot.
     * Use this in VitalsTab to avoid multiple coroutine launches.
     */
    data class HealthSnapshot(
        val steps: Long           = -1L,
        val calories: Double      = -1.0,
        val distanceKm: Double    = -1.0,
        val spO2: Double?         = null,
        val heartRate: HeartRateResult? = null,
        val sleep: SleepResult?   = null,
        val source: String        = "sensor"   // "health_connect" or "sensor"
    )

    suspend fun snapshot(): HealthSnapshot {
        if (!isAvailable || !hasPermissions()) return HealthSnapshot()
        return try {
            HealthSnapshot(
                steps      = readStepsToday(),
                calories   = readCaloriesToday(),
                distanceKm = readDistanceTodayKm(),
                spO2       = readLatestSpO2(),
                heartRate  = readHeartRateToday(),
                sleep      = readLastSleep(),
                source     = "health_connect"
            )
        } catch (e: Exception) {
            Log.e(TAG, "snapshot failed", e)
            HealthSnapshot()
        }
    }
}
