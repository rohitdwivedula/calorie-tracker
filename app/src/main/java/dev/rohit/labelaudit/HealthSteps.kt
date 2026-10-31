package dev.rohit.labelaudit

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

interface StepSource {
    suspend fun access(): String

    suspend fun aggregate(from: Instant, until: Instant): Long?
}

class AndroidStepSource(private val context: Context) : StepSource {
    override suspend fun access(): String {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                val granted =
                    HealthConnectClient.getOrCreate(context)
                        .permissionController
                        .getGrantedPermissions()
                if (READ_STEPS in granted) "ok" else "permission"
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
            else -> "unavailable"
        }
    }

    override suspend fun aggregate(from: Instant, until: Instant): Long? {
        // Aggregate ALL origins together. Health Connect applies source priorities/deduplication.
        // Never sum raw records or import active/total energy alongside these steps.
        val result =
            HealthConnectClient.getOrCreate(context)
                .aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(from, until),
                    )
                )
        return result[StepsRecord.COUNT_TOTAL]
    }

    companion object {
        val READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
    }
}

/**
 * Foreground only. Each date/zone key is replaced, never incremented. Failed reads preserve the
 * last successful count and timestamp. A null aggregate is unavailable, not measured zero.
 */
class StepsSync(private val repo: TrackerRepository, private val source: StepSource) {
    suspend fun refresh(
        dates: Collection<LocalDate>,
        now: Long = System.currentTimeMillis(),
    ): String {
        val access =
            try {
                source.access()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                "permission"
            } catch (e: Exception) {
                "error"
            }
        currentCoroutineContext().ensureActive()
        repo.db.saveStepCache(
            "_connection",
            "_",
            JSONObject().put("status", access).put("attempted", now),
        )
        for (date in dates.distinct().sortedDescending()) {
            currentCoroutineContext().ensureActive()
            if (date.isAfter(Instant.ofEpochMilli(now).atZone(repo.zone).toLocalDate())) continue
            val row = repo.db.stepCache(date.toString(), repo.zone.id)
            row.put("attempted", now)
            if (access != "ok") row.put("status", access)
            else {
                try {
                    val from = date.atStartOfDay(repo.zone).toInstant()
                    val to =
                        minOf(
                            date.plusDays(1).atStartOfDay(repo.zone).toInstant(),
                            Instant.ofEpochMilli(now),
                        )
                    val count = if (from < to) source.aggregate(from, to) else null
                    currentCoroutineContext().ensureActive()
                    require(count == null || count >= 0) { "Invalid step total" }
                    if (count == null) row.put("status", "no_data")
                    else
                        row.put("steps", count)
                            .put("synced", now)
                            .put("through", to.toEpochMilli())
                            .put("status", "ok")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    // History limits can also raise SecurityException even with READ_STEPS granted.
                    val stillGranted =
                        try {
                            source.access() == "ok"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false
                        }
                    row.put("status", if (stillGranted) "unavailable" else "permission")
                    if (!stillGranted) {
                        repo.db.saveStepCache(
                            "_connection",
                            "_",
                            JSONObject().put("status", "permission").put("attempted", now),
                        )
                        repo.db.saveStepCache(date.toString(), repo.zone.id, row)
                        return "permission"
                    }
                } catch (e: Exception) {
                    row.put("status", "error")
                }
            }
            repo.db.saveStepCache(date.toString(), repo.zone.id, row)
        }
        return access
    }
}
