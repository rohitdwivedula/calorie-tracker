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
import org.json.*

@Composable
fun SettingsScreen(v: TrackerViewModel, export: () -> Unit, restore: () -> Unit) {
    val r = v.repo
    val s = r.settings
    var pick by remember { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now(r.zone)
    val week = v.field("settings", "week", s.getInt("week").toString()).toInt()
    Page {
        Section("Nutrient references") {
            val profile = s.optJSONObject("nutrientProfile")
            Text(
                if (profile == null)
                    "Set birth month/year and reference sex for automatic RDA/AI targets"
                else
                    "Age ${NutrientReferences.age(profile, YearMonth.now(r.zone))} · ${profile.getString("sex").replaceFirstChar { it.uppercase() }} · ${profile.getString("stage")}",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = v::openNutrientProfile) {
                Text(if (profile == null) "Set reference profile" else "Edit reference profile")
            }
        }
        Section("Energy assumptions") {
            FormField(
                v,
                "settings",
                "daily",
                "Resting metabolic rate (kcal/day)",
                fmt(r.restingRate(today)),
                true,
            )
            FormField(
                v,
                "settings",
                "goal",
                "Weekly deficit goal (kcal)",
                fmt(s.getDouble("goal")),
                true,
            )
            TextButton(onClick = { pick = "effective" }) {
                Icon(Icons.Default.DateRange, null)
                Spacer(Modifier.width(8.dp))
                Text("Effective ${v.field("settings","effective",today.toString())}")
            }
            TextButton(onClick = { pick = "start" }) {
                Icon(Icons.Default.DateRange, null)
                Spacer(Modifier.width(8.dp))
                Text("Tracking since ${v.field("settings","start",s.getString("start"))}")
            }
            Text("Detailed week starts", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.values().forEach { day ->
                    FilterChip(
                        week == day.value,
                        onClick = { v.field("settings", "week", day.value.toString(), true) },
                        label = {
                            Text(day.name.take(3).lowercase().replaceFirstChar { it.uppercase() })
                        },
                    )
                }
            }
            Text(
                "The home weekly tile always starts Sunday.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Resting accrues through the day. Digestion, steps and extra activity are added separately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { v.openEnergy() }) { Text("Steps & activity") }
            TextButton(onClick = { advanced = !advanced }) {
                Text("Expenditure history & advanced")
            }
            if (advanced) {
                r.db
                    .list("resting")
                    .sortedByDescending { it.getString("date") }
                    .forEach {
                        InfoRow(it.getString("date"), "${fmt(it.getDouble("value"))} kcal/day")
                    }
                Text(
                    "New model since ${s.getString("energyStart")}. Earlier dates keep the previous total-expenditure assumptions.",
                    style = MaterialTheme.typography.bodySmall,
                )
                r.db
                    .list("rate")
                    .sortedByDescending { it.getString("date") }
                    .forEach {
                        InfoRow(
                            "Legacy · ${it.getString("date")}",
                            "${fmt(it.getDouble("value"))} kcal/day",
                        )
                    }
                FormField(
                    v,
                    "settings",
                    "duration",
                    "Legacy expected duration (hours)",
                    fmt(s.getDouble("duration")),
                    true,
                )
            }
            Button(
                onClick = {
                    v.run {
                        v.saveSettings(
                            LocalDate.parse(v.field("settings", "start", s.getString("start"))),
                            LocalDate.parse(v.field("settings", "effective", today.toString())),
                            v.field("settings", "daily", fmt(r.restingRate(today))).toDouble(),
                            v.field("settings", "goal", fmt(s.getDouble("goal"))).toDouble(),
                            week,
                            v.field("settings", "duration", fmt(s.getDouble("duration"))).toDouble(),
                        )
                    }
                },
                Modifier.fillMaxWidth(),
            ) {
                Text("Save settings")
            }
        }
        Section("Your data", "Stored locally on this phone") {
            FilledTonalButton(onClick = export, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FileDownload, null)
                Spacer(Modifier.width(8.dp))
                Text("Export backup")
            }
            OutlinedButton(onClick = restore, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FileUpload, null)
                Spacer(Modifier.width(8.dp))
                Text("Restore backup")
            }
            Text(
                "Backups include foods, portions, activities, resting rates, saved step totals and lookup responses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Open Food Facts · ODbL\nBatch Nutrition ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (pick != null) {
        val key = pick!!
        val date =
            LocalDate.parse(
                v.field(
                    "settings",
                    key,
                    if (key == "start") s.getString("start") else today.toString(),
                )
            )
        val selected =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            )
        DatePickerDialog(
            onDismissRequest = { pick = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        selected.selectedDateMillis?.let {
                            v.field(
                                "settings",
                                key,
                                Instant.ofEpochMilli(it)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                    .toString(),
                                true,
                            )
                        }
                        pick = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { pick = null }) { Text("Cancel") } },
        ) {
            DatePicker(selected)
        }
    }
}
