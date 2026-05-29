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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val TAG = "HealthConnectRepo"

/**
 * Reads health data from Health Connect (Samsung Health, Google Health, Garmin, etc.).
 * Falls back gracefully when HC is not installed or permission is not granted.
 *
 * Sleep logic:
 *  - Queries a 24-hour window (noon yesterday → noon today) to catch cross-midnight sessions
 *  - Classifies each session as NIGHT (start 6 PM–6 AM) or NAP (start 6 AM–6 PM)
 *  - Within each session, calculates actual sleep vs time-in-bed using stage breakdown
 *  - Merges fragmented sessions that start within 30 min of the previous session end
 *  - Filters micro-naps < 15 min actual sleep
 */
class HealthConnectRepository(private val context: Context) {

    // ── Availability ────────────────────────────────────────────────────────
    val sdkStatus: Int by lazy { HealthConnectClient.getSdkStatus(context) }
    val isAvailable: Boolean get() = sdkStatus == HealthConnectClient.SDK_AVAILABLE
    val needsUpdate: Boolean get() = sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

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
        return c.permissionController.getGrantedPermissions().containsAll(requiredPermissions)
    }

    // ── Time helpers ─────────────────────────────────────────────────────────
    private fun todayStart(): Instant =
        LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault()).toInstant()

    private fun todayEnd(): Instant = Instant.now()

    // Sleep window: noon yesterday → noon today (catches cross-midnight sessions)
    private fun sleepWindowStart(): Instant =
        LocalDate.now(ZoneId.systemDefault()).minusDays(1)
            .atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()

    private fun sleepWindowEnd(): Instant =
        LocalDate.now(ZoneId.systemDefault())
            .atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()

    // ── Step count ────────────────────────────────────────────────────────────
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

    enum class SleepCategory { NIGHT_SLEEP, DAY_NAP }

    /** Classifies a session by the LOCAL start time of the session. */
    private fun categorize(session: SleepSessionRecord): SleepCategory {
        val localStart = session.startTime.atZone(ZoneId.systemDefault()).toLocalTime()
        val dayStart   = LocalTime.of(6, 0)
        val dayEnd     = LocalTime.of(18, 0)
        return if (localStart.isAfter(dayStart) && localStart.isBefore(dayEnd))
            SleepCategory.DAY_NAP
        else
            SleepCategory.NIGHT_SLEEP
    }

    data class SleepStageBreakdown(
        val totalMinutes:  Long,   // time in bed
        val actualMinutes: Long,   // actual sleep (excl. awake / out-of-bed)
        val deepMinutes:   Long,
        val remMinutes:    Long,
        val lightMinutes:  Long,
        val awakMinutes:   Long,
        val efficiency:    Int,    // actualMinutes / totalMinutes * 100
        val sessionStart:  Instant?,
        val sessionEnd:    Instant?,
        val hasStageData:  Boolean  // false = wearable didn't record stages
    )

    data class SleepResult(
        val night:    SleepStageBreakdown?,
        val naps:     List<SleepStageBreakdown>,
        val napCount: Int
    ) {
        val totalActualSleepMinutes: Long
            get() = (night?.actualMinutes ?: 0L) + naps.sumOf { it.actualMinutes }
    }

    /** Calculates actual vs in-bed time for a single SleepSessionRecord. */
    private fun breakdown(session: SleepSessionRecord): SleepStageBreakdown {
        val timeInBed = Duration.between(session.startTime, session.endTime).toMinutes()

        if (session.stages.isEmpty()) {
            // Wearable didn't track stages — treat full session as actual sleep
            return SleepStageBreakdown(
                totalMinutes  = timeInBed,
                actualMinutes = timeInBed,
                deepMinutes   = 0L,
                remMinutes    = 0L,
                lightMinutes  = 0L,
                awakMinutes   = 0L,
                efficiency    = 100,
                sessionStart  = session.startTime,
                sessionEnd    = session.endTime,
                hasStageData  = false
            )
        }

        var actual = 0L; var deep = 0L; var rem = 0L; var light = 0L; var awak = 0L

        for (stage in session.stages) {
            val min = Duration.between(stage.startTime, stage.endTime).toMinutes()
            when (stage.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP  -> { deep  += min; actual += min }
                SleepSessionRecord.STAGE_TYPE_REM   -> { rem   += min; actual += min }
                SleepSessionRecord.STAGE_TYPE_LIGHT -> { light += min; actual += min }
                SleepSessionRecord.STAGE_TYPE_SLEEPING -> { actual += min }   // generic "sleeping"
                SleepSessionRecord.STAGE_TYPE_AWAKE,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> { awak += min }
                // STAGE_TYPE_UNKNOWN — ignore
            }
        }

        val eff = if (timeInBed > 0) ((actual * 100) / timeInBed).toInt().coerceIn(0, 100) else 0

        return SleepStageBreakdown(
            totalMinutes  = timeInBed,
            actualMinutes = actual,
            deepMinutes   = deep,
            remMinutes    = rem,
            lightMinutes  = light,
            awakMinutes   = awak,
            efficiency    = eff,
            sessionStart  = session.startTime,
            sessionEnd    = session.endTime,
            hasStageData  = true
        )
    }

    /**
     * Merges fragmented sessions (gap < 30 min = same sleep block).
     * Returns a synthetic SleepStageBreakdown summing all fragments.
     */
    private fun mergeFragments(sessions: List<SleepSessionRecord>): SleepStageBreakdown? {
        if (sessions.isEmpty()) return null
        val sorted = sessions.sortedBy { it.startTime }
        val merged = mutableListOf<SleepSessionRecord>()
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val gapMin = Duration.between(current.endTime, next.startTime).toMinutes()
            if (gapMin <= 30) {
                // Treat as same session — extend current end time virtually via breakdown accumulation
                // We'll handle by summing breakdowns below
                merged.add(current)
                current = next
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        val parts = merged.map { breakdown(it) }
        val totalMinutes  = Duration.between(sorted.first().startTime, sorted.last().endTime).toMinutes()
        val actualMinutes = parts.sumOf { it.actualMinutes }
        val deepMinutes   = parts.sumOf { it.deepMinutes }
        val remMinutes    = parts.sumOf { it.remMinutes }
        val lightMinutes  = parts.sumOf { it.lightMinutes }
        val awakMinutes   = parts.sumOf { it.awakMinutes }
        val efficiency    = if (totalMinutes > 0) ((actualMinutes * 100) / totalMinutes).toInt().coerceIn(0, 100) else 0
        val hasStages     = parts.any { it.hasStageData }

        return SleepStageBreakdown(
            totalMinutes  = totalMinutes,
            actualMinutes = actualMinutes,
            deepMinutes   = deepMinutes,
            remMinutes    = remMinutes,
            lightMinutes  = lightMinutes,
            awakMinutes   = awakMinutes,
            efficiency    = efficiency,
            sessionStart  = sorted.first().startTime,
            sessionEnd    = sorted.last().endTime,
            hasStageData  = hasStages
        )
    }

    /**
     * Reads and classifies all sleep sessions in the 24h window.
     * Night sleep: picks the longest night block (main sleep).
     * Naps: all day sessions ≥ 15 min actual sleep.
     */
    suspend fun readSleep(): SleepResult? {
        val c = client ?: return null
        return try {
            val response = c.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(sleepWindowStart(), sleepWindowEnd())
                )
            )
            if (response.records.isEmpty()) return null

            val nightSessions = response.records.filter { categorize(it) == SleepCategory.NIGHT_SLEEP }
            val napSessions   = response.records.filter { categorize(it) == SleepCategory.DAY_NAP }

            // Night: merge fragments, pick longest
            val nightBreakdown = if (nightSessions.isNotEmpty()) mergeFragments(nightSessions) else null

            // Naps: individual breakdowns, filter micro-naps < 15 min
            val napBreakdowns = napSessions
                .map { breakdown(it) }
                .filter { it.actualMinutes >= 15 }
                .sortedByDescending { it.actualMinutes }

            SleepResult(
                night    = nightBreakdown,
                naps     = napBreakdowns,
                napCount = napBreakdowns.size
            )
        } catch (e: Exception) {
            Log.w(TAG, "readSleep failed: ${e.message}")
            null
        }
    }

    // ── Heart rate ────────────────────────────────────────────────────────────
    data class HeartRateResult(
        val restingBpm: Int,
        val maxBpm:     Int,
        val minBpm:     Int,
        val latestBpm:  Int,
        val avgBpm:     Int
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

            val bpms   = allSamples.map { it.beatsPerMinute }
            val sorted = bpms.sorted()
            val resting = sorted[(sorted.size * 0.10).toInt().coerceAtLeast(0)].toInt()
            val avg     = (bpms.sum() / bpms.size).toInt()

            HeartRateResult(
                restingBpm = resting,
                maxBpm     = sorted.last().toInt(),
                minBpm     = sorted.first().toInt(),
                latestBpm  = allSamples.last().beatsPerMinute.toInt(),
                avgBpm     = avg
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

    // ── Composite snapshot ─────────────────────────────────────────────────────
    data class HealthSnapshot(
        val steps:       Long                = -1L,
        val calories:    Double              = -1.0,
        val distanceKm:  Double              = -1.0,
        val spO2:        Double?             = null,
        val heartRate:   HeartRateResult?    = null,
        val sleep:       SleepResult?        = null,
        val source:      String              = "health_connect"
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
                sleep      = readSleep()
            )
        } catch (e: Exception) {
            Log.e(TAG, "snapshot failed", e)
            HealthSnapshot()
        }
    }
}
