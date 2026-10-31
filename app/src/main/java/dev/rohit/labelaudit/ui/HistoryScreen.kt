@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.rohit.labelaudit

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.*

private data class FoodLog(
    val key: String,
    val record: JSONObject,
    val kind: String,
    val time: Long,
    val fraction: Double,
    val portion: Int = -1,
)

@Composable
fun HistoryScreen(v: TrackerViewModel) {
    val logs =
        remember(v.revision) {
            buildList<FoodLog> {
                    v.repo.db.list("batch").forEach { b ->
                        val events = b.optJSONArray("eatingEvents")
                        if (events != null && events.length() > 0) {
                            for (i in 0 until events.length()) {
                                val e = events.getJSONObject(i)
                                add(
                                    FoodLog(
                                        "batch:${e.getString("id")}",
                                        b,
                                        "batch",
                                        e.getLong("time"),
                                        e.getDouble("fraction"),
                                        i,
                                    )
                                )
                            }
                        } else if (!b.isNull("completed") && !b.optBoolean("portionTracking"))
                            add(
                                FoodLog(
                                    "legacy:${b.getString("id")}",
                                    b,
                                    "legacy",
                                    b.getLong("completed"),
                                    b.getDouble("share"),
                                )
                            )
                    }
                    v.repo.db.list("direct").forEach {
                        add(
                            FoodLog(
                                "direct:${it.getString("id")}",
                                it,
                                "direct",
                                it.getLong("completed"),
                                it.getDouble("share"),
                            )
                        )
                    }
                    v.repo.db.list("calorie").forEach {
                        add(
                            FoodLog(
                                "calorie:${it.getString("id")}",
                                it,
                                "calorie",
                                it.getLong("time"),
                                1.0,
                            )
                        )
                    }
                }
                .sortedWith(compareByDescending<FoodLog> { it.time }.thenBy { it.key })
        }
    var limit by rememberSaveable { mutableIntStateOf(25) }
    val state = rememberLazyListState()
    LaunchedEffect(state, logs.size) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (last >= limit - 5 && limit < logs.size) limit = minOf(logs.size, limit + 25)
            }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (logs.isEmpty())
            item {
                EmptyState(
                    Icons.Default.History,
                    "Your story starts here",
                    "Logged foods and dish portions will appear here.",
                )
            }
        itemsIndexed(logs.take(limit), key = { _, item -> item.key }) { index, item ->
            val day = Instant.ofEpochMilli(item.time).atZone(v.repo.zone).toLocalDate()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (
                    index == 0 ||
                        day !=
                            Instant.ofEpochMilli(logs[index - 1].time)
                                .atZone(v.repo.zone)
                                .toLocalDate()
                )
                    Text(
                        day.format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        Instant.ofEpochMilli(item.time)
                            .atZone(v.repo.zone)
                            .format(DateTimeFormatter.ofPattern("HH:mm")),
                        Modifier.width(44.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Card(
                        onClick = {
                            if (item.kind == "calorie") v.beginCalories(item.record)
                            else if (item.portion >= 0)
                                v.eventEdit = item.record.getString("id") to item.portion
                            else
                                v.detail(
                                    if (item.kind == "legacy") "batch" else item.kind,
                                    item.record.getString("id"),
                                )
                        },
                        Modifier.weight(1f),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                item.record.optString("name").ifBlank { "Quick log" },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (item.kind == "calorie")
                                Text(
                                    "${fmt(item.record.getDouble("kcal"))} kcal · macros unknown",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            else {
                                val total = v.repo.batchNutrients(item.record, item.fraction)
                                Text(
                                    (if (item.kind == "batch")
                                        "${fmt(item.fraction*100)}% of dish · "
                                    else if (item.kind == "legacy") "Legacy estimate · " else "") +
                                        v.repo.nutrientText(total, 0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Protein ${v.repo.nutrientText(total,1)} · Fiber ${v.repo.nutrientText(total,4)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
