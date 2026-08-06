package com.pikowalker.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HealthConnectHelper(private val context: Context) {

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class)
        )
    }

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun getClient(): HealthConnectClient? =
        if (isAvailable()) HealthConnectClient.getOrCreate(context) else null

    suspend fun hasPermissions(): Boolean {
        val client = getClient() ?: return false
        return client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    }

    /** Sums StepsRecord.COUNT_TOTAL across all sources for the local calendar day so far —
     *  not just what PikoWalker itself wrote. */
    suspend fun readTodaySteps(): Long {
        val client = getClient() ?: return 0L
        return try {
            val zone = ZoneId.systemDefault()
            val startOfDay = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, Instant.now())
                )
            )
            result[StepsRecord.COUNT_TOTAL] ?: 0L
        } catch (_: Exception) { 0L }
    }

    /** Adjusts today's total toward [targetTotal]. Increases are done by inserting a single
     *  corrective record for the shortfall — always exact. Decreases can only remove records
     *  PikoWalker itself wrote today (Health Connect won't let one app delete another app's
     *  data), so if other sources contributed most of today's count, the result may land above
     *  [targetTotal] — caller should re-read the actual total afterward rather than assume it
     *  landed exactly on target. */
    suspend fun adjustTodaySteps(targetTotal: Long): Boolean {
        val client = getClient() ?: return false
        return try {
            val zone = ZoneId.systemDefault()
            val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val now = Instant.now()
            val current = readTodaySteps()
            val delta = targetTotal - current

            when {
                delta > 0 -> {
                    val offset = zone.rules.getOffset(now).let { ZoneOffset.ofTotalSeconds(it.totalSeconds) }
                    val record = StepsRecord(
                        count = delta,
                        startTime = now.minusSeconds(60),
                        startZoneOffset = offset,
                        endTime = now,
                        endZoneOffset = offset
                    )
                    client.insertRecords(listOf(record))
                }
                delta < 0 -> {
                    val ownRecords = client.readRecords(
                        ReadRecordsRequest(
                            recordType = StepsRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startOfDay, now),
                            dataOriginFilter = setOf(DataOrigin(context.packageName))
                        )
                    ).records.sortedByDescending { it.startTime }

                    var remaining = -delta
                    val toDelete = mutableListOf<String>()
                    for (r in ownRecords) {
                        if (remaining <= 0) break
                        toDelete.add(r.metadata.id)
                        remaining -= r.count
                    }
                    if (toDelete.isNotEmpty()) {
                        client.deleteRecords(StepsRecord::class, recordIdsList = toDelete, clientRecordIdsList = emptyList())
                    }
                }
            }
            true
        } catch (_: Exception) { false }
    }

    suspend fun insertSteps(steps: Long, startMs: Long, endMs: Long): Boolean {
        if (steps <= 0 || endMs <= startMs) return false
        val client = getClient() ?: return false
        return try {
            val zone = java.time.ZoneId.systemDefault().rules
                .getOffset(java.time.Instant.ofEpochMilli(startMs))
                .let { ZoneOffset.ofTotalSeconds(it.totalSeconds) }
            val record = StepsRecord(
                count = steps,
                startTime = Instant.ofEpochMilli(startMs),
                startZoneOffset = zone,
                endTime = Instant.ofEpochMilli(endMs),
                endZoneOffset = zone
            )
            client.insertRecords(listOf(record))
            true
        } catch (_: Exception) { false }
    }
}
