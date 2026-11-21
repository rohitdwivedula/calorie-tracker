package dev.rohit.labelaudit

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.*

internal data class WeighIn(val id: String, val time: Long, val kg: Double, val origin: String)

internal fun Data.weighIns(from: Long, until: Long): List<WeighIn> =
    readableDatabase
        .rawQuery(
            "SELECT id,time,kg,origin FROM weight_cache WHERE time>=? AND time<? ORDER BY time,id",
            arrayOf(from.toString(), until.toString()),
        )
        .use { rows ->
            buildList {
                while (rows.moveToNext()) add(
                    WeighIn(
                        rows.getString(0),
                        rows.getLong(1),
                        rows.getDouble(2),
                        rows.getString(3),
                    )
                )
            }
        }

internal fun Data.lastWeightSync(): Long =
    readableDatabase.rawQuery("SELECT synced FROM weight_sync", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }

private fun WeightRecord.measurement() =
    WeighIn(metadata.id, time.toEpochMilli(), weight.inKilograms, metadata.dataOrigin.packageName)

// Apply all pages and the checkpoint atomically, preserving the last good snapshot on failure.
internal fun Data.applyWeightSync(
    updates: Map<String, WeighIn?>,
    snapshotFrom: Long?,
    until: Long,
    since: Long,
    token: String,
) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        if (snapshotFrom != null)
            db.delete(
                "weight_cache",
                "time>=? AND time<?",
                arrayOf(snapshotFrom.toString(), until.toString()),
            )
        for ((id, row) in updates) {
            if (row == null) db.delete("weight_cache", "id=?", arrayOf(id))
            else
                db.execSQL(
                    "INSERT OR REPLACE INTO weight_cache(id,time,kg,origin) VALUES(?,?,?,?)",
                    arrayOf(row.id, row.time, row.kg, row.origin),
                )
        }
        db.execSQL(
            "INSERT OR REPLACE INTO weight_sync(singleton,token,since,synced) VALUES(1,?,?,?)",
            arrayOf(token, since, until),
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

internal class WeightSync(private val context: Context, private val db: Data) {
    companion object {
        val PERMISSION = HealthPermission.getReadPermission(WeightRecord::class)
    }

    suspend fun refresh(recheckRecent: Boolean): String {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> Unit
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> return "update"
            else -> return "unavailable"
        }
        val client = HealthConnectClient.getOrCreate(context)
        if (PERMISSION !in client.permissionController.getGrantedPermissions()) return "permission"
        syncRecords(client, Instant.now(), recheckRecent)
        return "ok"
    }

    internal suspend fun syncRecords(
        client: HealthConnectClient,
        now: Instant,
        recheckRecent: Boolean,
    ) {
        val previous =
            db.readableDatabase.rawQuery("SELECT token,since FROM weight_sync", null).use {
                if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
            }
        val recentStart = now.minus(Duration.ofDays(30)).toEpochMilli()
        val since = previous?.second ?: recentStart
        val updates = mutableMapOf<String, WeighIn?>()
        var token = previous?.first
        var snapshotFrom: Long? = null
        var renewedToken = token == null
        var readFrom = if (token == null) since else if (recheckRecent) recentStart else null
        while (true) {
            if (token == null)
                token = client.getChangesToken(ChangesTokenRequest(setOf(WeightRecord::class)))
            if (readFrom != null) {
                snapshotFrom = readFrom
                var page: String? = null
                do {
                    val response =
                        client.readRecords(
                            ReadRecordsRequest(
                                WeightRecord::class,
                                TimeRangeFilter.between(Instant.ofEpochMilli(readFrom), now),
                                pageToken = page,
                            )
                        )
                    response.records.forEach { updates[it.metadata.id] = it.measurement() }
                    page = response.pageToken
                } while (page != null)
                readFrom = null
            }
            val changes = client.getChanges(token)
            if (changes.changesTokenExpired) {
                check(!renewedToken) { "Weight sync expired during refresh" }
                updates.clear()
                renewedToken = true
                token = null
                readFrom = since
                continue
            }
            for (change in changes.changes) when (change) {
                is DeletionChange -> updates[change.recordId] = null
                is UpsertionChange ->
                    (change.record as? WeightRecord)?.let {
                        updates[it.metadata.id] = it.measurement()
                    }
            }
            token = changes.nextChangesToken
            if (!changes.hasMore) {
                db.applyWeightSync(updates, snapshotFrom, now.toEpochMilli(), since, token)
                return
            }
        }
    }
}
