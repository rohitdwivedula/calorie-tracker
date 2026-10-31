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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.*
import org.json.*

@Composable
fun DetailsScreen(v: TrackerViewModel) {
    val b = remember(v.selectedId, v.revision) { v.record(v.selectedKind, v.selectedId) }
    val r = v.repo
    val selectedPortion = v.field("details", "portionId", "")
    val events = b.optJSONArray("eatingEvents")
    val portionIndex =
        (0 until (events?.length() ?: 0)).firstOrNull {
            events!!.getJSONObject(it).getString("id") == selectedPortion
        }
    var whole by
        rememberSaveable(b.getString("id"), selectedPortion) {
            mutableStateOf(selectedPortion.isEmpty())
        }
    val fraction =
        if (!whole && portionIndex != null)
            events!!.getJSONObject(portionIndex).getDouble("fraction")
        else if (v.selectedKind == "direct") b.getDouble("share") else 1.0
    val totals = r.batchNutrients(b, fraction)
    var timeField by remember { mutableStateOf<String?>(null) }
    val start = v.field("details", "start", b.getLong("available").toString()).toLong()
    val completion =
        v.field(
                "details",
                "completion",
                if (b.isNull("completed")) "" else b.getLong("completed").toString(),
            )
            .toLongOrNull()
    Page {
        Section(b.optString("name")) {
            if (portionIndex != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!whole, { whole = false }, label = { Text("This portion") })
                    FilterChip(whole, { whole = true }, label = { Text("Whole dish") })
                }
                Text(
                    "${fmt(events!!.getJSONObject(portionIndex).getDouble("fraction") * 100)}% · ${whenText(events.getJSONObject(portionIndex).getLong("time"), r.zone)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { v.eventEdit = b.getString("id") to portionIndex }) {
                    Text("Edit portion")
                }
            }
            InfoRow(
                if (!whole && portionIndex != null) "This portion"
                else if (v.selectedKind == "direct") "Logged food" else "Whole dish",
                r.nutrientText(totals, 0),
            )
            InfoRow("Protein", r.nutrientText(totals, 1))
            InfoRow("Fiber", r.nutrientText(totals, 4))
        }
        if (v.selectedKind == "batch")
            Section("Ingredients") {
                val ingredients = b.getJSONArray("ingredients")
                for (i in 0 until ingredients.length()) {
                    val x = ingredients.getJSONObject(i)
                    val food = x.getJSONObject("food")
                    val contribution =
                        Nutrients.Total().apply {
                            add(
                                r.nutrientValues(food),
                                fraction * x.getDouble("quantity") / food.getDouble("basis"),
                            )
                        }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(food.optString("name"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${fmt(x.getDouble("quantity") * fraction)} ${food.optString("unit")} · ${if (fraction == 1.0) "whole dish" else "in this portion"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${r.nutrientText(contribution, 0)} · Protein ${r.nutrientText(contribution, 1)} · Fiber ${r.nutrientText(contribution, 4)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (i < ingredients.length() - 1) HorizontalDivider()
                }
                TextButton(onClick = { v.beginCook(b) }) { Text("Edit ingredients") }
            }
        Section("Recorded details") {
            if (v.selectedKind == "batch")
                TextButton(onClick = { timeField = "start" }) {
                    Icon(Icons.Default.Schedule, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Available · ${whenText(start,r.zone)}")
                }
            if (completion != null)
                TextButton(onClick = { timeField = "completion" }) {
                    Text(
                        "${if(v.selectedKind=="direct")"Ate"else "Finished"} · ${whenText(completion,r.zone)}"
                    )
                }
            FormField(
                v,
                "details",
                "share",
                "My share of the batch (%)",
                fmt(b.getDouble("share") * 100),
                true,
            )
            Button(
                onClick = {
                    v.run {
                        v.saveDetails(
                            start,
                            completion,
                            v.field("details", "share", fmt(b.getDouble("share") * 100))
                                .toDouble() / 100,
                        )
                    }
                }
            ) {
                Text("Save corrections")
            }
        }
        if (events != null && events.length() > 0)
            Section("Recorded portions") {
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    ListItem(
                        headlineContent = {
                            Text("${fmt(event.getDouble("fraction")*100)}% of dish")
                        },
                        supportingContent = { Text(whenText(event.getLong("time"), r.zone)) },
                        trailingContent = { Icon(Icons.Default.Edit, null) },
                        modifier = Modifier.clickable { v.eventEdit = b.getString("id") to i },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        if (v.selectedKind == "batch") {
            FilledTonalButton(onClick = { v.beginCook(b, true) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentCopy, null)
                Spacer(Modifier.width(8.dp))
                Text("Cook this again")
            }
            if (!b.isNull("completed"))
                OutlinedButton(
                    onClick = { v.run { v.undo(b.getString("id")) } },
                    Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Undo, null)
                    Text("Undo completion")
                }
        }
        TextButton(onClick = { v.deleteRecord = v.selectedKind to b.getString("id") }) {
            Icon(Icons.Default.Delete, null)
            Spacer(Modifier.width(8.dp))
            Text("Delete record")
        }
    }
    if (timeField != null)
        TimestampDialog(
            if (timeField == "start") start else completion ?: start,
            r.zone,
            { timeField = null },
        ) {
            v.field("details", timeField!!, it.toString(), true)
            timeField = null
        }
}
