@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.rohit.labelaudit

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.*

@Composable
fun ExpenditureBreakdown(
    v: TrackerViewModel,
    date: LocalDate,
    connect: () -> Unit,
    compact: Boolean = false,
    asOf: Long? = null,
) {
    val r = v.repo
    val now = asOf ?: remember(v.revision, date) { System.currentTimeMillis() }
    val e = r.dailyExpenditure(date, now)
    var expanded by rememberSaveable(date.toString()) { mutableStateOf(!compact) }
    val today = date == LocalDate.now(r.zone)
    TextButton(onClick = { expanded = !expanded }) {
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        Spacer(Modifier.width(6.dp))
        Text(
            "${if(today) "Spent so far" else "Estimated expenditure"} · ${kcal(e.total)} kcal${if(e.incomplete()) "*" else ""}"
        )
    }
    if (expanded) {
        if (e.legacy)
            Text("Previous daily-expenditure model", style = MaterialTheme.typography.bodySmall)
        InfoRow(
            if (e.legacy) "Legacy expenditure" else "Resting${if(today) " so far" else ""}",
            "${kcal(e.resting)} kcal",
        )
        if (!e.legacy) {
            InfoRow(
                "Food digestion · est.",
                if (e.nutritionIncomplete && e.digestion == 0.0) "—"
                else "${if(e.nutritionIncomplete) "≥" else ""}${kcal(e.digestion)} kcal",
            )
            InfoRow("Steps · est.", e.steps?.let { "${kcal(it)} kcal" } ?: "—")
            InfoRow("Extra activity", "${kcal(e.extra)} kcal")
            val cache = r.db.stepCache(date.toString(), r.zone.id)
            val connection = r.db.stepCache("_connection", "_").optString("status")
            val status = cache.optString("status", "unavailable")
            Text(
                when {
                    connection == "permission" || status == "permission" ->
                        "Connect Health Connect to read steps"
                    connection == "update" -> "Health Connect needs installing or updating"
                    e.steps == null -> "Steps unavailable · not counted as zero"
                    e.stepsIncomplete ->
                        "${kcal(cache.optLong("steps").toDouble())} steps · saved value, refresh needed"
                    else -> "${kcal(cache.optLong("steps").toDouble())} steps · Health Connect"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cache.has("synced"))
                Text(
                    "Last synced ${whenText(cache.getLong("synced"), r.zone)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (e.nutritionIncomplete)
                Text(
                    "Digestion incomplete · some food macros are unknown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (e.incomplete())
                Text(
                    "* Partial estimate; unavailable components are excluded and saved steps may be stale.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connection != "ok" || status == "permission")
                    TextButton(onClick = connect) { Text("Connect steps") }
                TextButton(enabled = !v.syncingSteps, onClick = { v.refreshSteps(date) }) {
                    Text(if (v.syncingSteps) "Refreshing…" else "Refresh steps")
                }
            }
            TextButton(onClick = { v.beginActivity(date) }) { Text("Add activity calories") }
        }
        if (compact)
            TextButton(onClick = { v.openEnergy(date) }) { Text("Daily details & activity") }
    }
}

@Composable
fun ExpenditureScreen(v: TrackerViewModel, connect: () -> Unit) {
    val date = LocalDate.parse(v.field("energy", "date", LocalDate.now(v.repo.zone).toString()))
    var pick by remember { mutableStateOf(false) }
    LaunchedEffect(date) { v.refreshSteps(date) }
    Page {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(
                onClick = { v.field("energy", "date", date.minusDays(1).toString(), true) }
            ) {
                Icon(Icons.Default.ChevronLeft, "Previous day")
            }
            TextButton(onClick = { pick = true }) { Text(date.toString()) }
            IconButton(
                enabled = date < LocalDate.now(v.repo.zone),
                onClick = { v.field("energy", "date", date.plusDays(1).toString(), true) },
            ) {
                Icon(Icons.Default.ChevronRight, "Next day")
            }
        }
        Section(
            "Daily expenditure",
            if (date == LocalDate.now(v.repo.zone))
                "Estimated so far · resting accrues through the day"
            else "Estimated from recorded food and activity",
        ) {
            ExpenditureBreakdown(v, date, connect)
        }
        val entries =
            remember(v.revision, date) {
                v.repo.db.list("activity").filter { it.getString("date") == date.toString() }
            }
        if (entries.isNotEmpty())
            Section("Extra activity") {
                entries.forEach { a ->
                    ListItem(
                        headlineContent = {
                            Text(a.optString("description").ifBlank { "Extra activity" })
                        },
                        supportingContent = { Text("${kcal(a.getDouble("kcal"))} kcal") },
                        trailingContent = {
                            TextButton(onClick = { v.beginActivity(date, a) }) { Text("Edit") }
                        },
                        colors =
                            ListItemDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                    )
                }
            }
    }
    if (pick)
        DateOnlyDialog(date, { pick = false }) {
            if (it <= LocalDate.now(v.repo.zone)) v.field("energy", "date", it.toString(), true)
            pick = false
        }
}

@Composable
fun ActivityCaloriesScreen(v: TrackerViewModel) {
    val date = LocalDate.parse(v.field("activity", "date"))
    var pick by remember { mutableStateOf(false) }
    Page {
        Section("Extra activity") {
            TextButton(onClick = { pick = true }) {
                Icon(Icons.Default.DateRange, null)
                Spacer(Modifier.width(8.dp))
                Text(date.toString())
            }
            FormField(v, "activity", "kcal", "Additional kcal", numeric = true)
            FormField(v, "activity", "description", "Description (optional)")
            Text(
                "Only calories above resting and beyond movement already counted through steps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { v.run { v.saveActivity() } }, Modifier.fillMaxWidth()) {
                Text("Save activity")
            }
            if (v.repo.db.list("activity").any { it.getString("id") == v.field("activity", "id") })
                TextButton(onClick = { v.deleteRecord = "activity" to v.field("activity", "id") }) {
                    Text("Delete activity")
                }
        }
    }
    if (pick)
        DateOnlyDialog(date, { pick = false }) {
            v.field("activity", "date", it.toString(), true)
            pick = false
        }
}

@Composable
fun DateOnlyDialog(date: LocalDate, dismiss: () -> Unit, selected: (LocalDate) -> Unit) {
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    DatePickerDialog(
        onDismissRequest = dismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let {
                        selected(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                }
            ) {
                Text("Choose date")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    ) {
        DatePicker(state)
    }
}
