@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.rohit.labelaudit

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.*
import org.json.*

@Composable
fun StatsScreen(v: TrackerViewModel) {
    var period by rememberSaveable { mutableIntStateOf(1) }
    var group by rememberSaveable { mutableIntStateOf(0) }
    var goalIndex by rememberSaveable { mutableIntStateOf(-1) }
    val days =
        remember(period, v.revision) {
            val history = LoggedHistory(v.repo, System.currentTimeMillis())
            val start = history.today.minusDays(period.toLong() - 1)
            history.days.tailMap(start, true).values.map { it.nutrition }
        }
    val range =
        when (group) {
            1 -> 5..15
            2 -> 16 until Nutrients.KEYS.size
            else -> 0..4
        }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = { v.go("summary") }) {
                Icon(Icons.Default.BarChart, null)
                Spacer(Modifier.width(8.dp))
                Text("Energy balance")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 7, 30).forEach { p ->
                    FilterChip(
                        selected = period == p,
                        onClick = { period = p },
                        label = { Text(if (p == 1) "Today" else "$p days") },
                        modifier =
                            Modifier.semantics {
                                contentDescription =
                                    if (p == 1) "Show today's nutrients" else "Show $p day averages"
                            },
                    )
                }
            }
        }
        item {
            Text(
                if (period == 1) "Today · so far"
                else "${days.size}/$period days logged · includes today so far",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            TextButton(onClick = v::openNutrientProfile) {
                Text(
                    if (v.repo.settings.has("nutrientProfile")) "Reference profile"
                    else "Set reference profile"
                )
            }
        }
        item {
            ScrollableTabRow(selectedTabIndex = group, edgePadding = 0.dp) {
                listOf("Overview", "Vitamins", "Minerals").forEachIndexed { i, label ->
                    Tab(selected = group == i, onClick = { group = i }, text = { Text(label) })
                }
            }
        }
        if (days.isEmpty())
            item {
                EmptyState(
                    Icons.Default.BarChart,
                    "A little more history",
                    if (period == 1) "Log a food or portion to see today’s nutrients."
                    else "Log a food or portion to start your daily averages.",
                )
            }
        items(range.toList(), key = { it }) { i ->
            val tally = Nutrients.Days()
            days.forEach {
                tally.add(
                    it.values[i],
                    it.missing[i],
                    v.repo.goal(i),
                    Nutrients.KEYS[i] == "sodium",
                )
            }
            Card(onClick = { if (i > 0) goalIndex = i }, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(Nutrients.NAMES[i], style = MaterialTheme.typography.titleMedium)
                        if (i > 0)
                            Icon(
                                Icons.Default.Tune,
                                "Adjust target",
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                    Text(
                        if (period == 1)
                            days.firstOrNull()?.let { v.repo.nutrientText(it, i) } ?: "—"
                        else if (tally.average().isNaN()) "—"
                        else
                            "${fmt(tally.average()*Nutrients.displayFactor(i))} ${Nutrients.unit(i)} / day",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (i > 0) {
                        val goal = v.repo.goal(i)
                        val today = days.firstOrNull()
                        if (goal > 0 || i == 3)
                            Text(
                                if (period == 1 && goal > 0) {
                                    if (today == null) "No intake logged today"
                                    else if (today.missing[i] && !today.known[i])
                                        "${v.repo.goalBasis(i)} · ${fmt(goal*Nutrients.displayFactor(i))} ${v.repo.goalUnit(i)}"
                                    else
                                        "${if (today.missing[i]) "≥" else ""}${fmt(today.values[i] / goal * 100)}% of daily ${if (Nutrients.KEYS[i] == "sodium") "limit" else "reference"} · ${fmt(goal*Nutrients.displayFactor(i))} ${v.repo.goalUnit(i)} (${v.repo.goalBasis(i)})"
                                } else if (goal > 0)
                                    "${tally.met}/${days.size} days ${if(Nutrients.KEYS[i]=="sodium")"within limit"else "reached reference"} · ${fmt(goal*Nutrients.displayFactor(i))} ${v.repo.goalUnit(i)} (${v.repo.goalBasis(i)})"
                                else if (i == 3) "No fixed fat RDA · 20–35% of energy range"
                                else "Choose one reference profile for automatic targets",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        if (
                            days.isNotEmpty() &&
                                goal > 0 &&
                                (period != 1 || today?.known?.get(i) == true)
                        )
                            LinearProgressIndicator(
                                progress = {
                                    if (period == 1)
                                        (today!!.values[i] / goal).toFloat().coerceIn(0f, 1f)
                                    else tally.met.toFloat() / days.size
                                },
                                Modifier.fillMaxWidth(),
                            )
                    }

                    if (tally.unknown > 0)
                        Text(
                            if (period == 1) {
                                if (days.first().known[i])
                                    "Partial total · some foods have no data for this nutrient"
                                else "Not provided by logged foods"
                            } else
                                "${tally.unknown} days incomplete · average uses ${tally.complete} complete days",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        }
        item {
            Text(
                if (period == 1)
                    "≥ is a known minimum. Missing nutrients aren't zero. Unlogged food isn't included."
                else
                    "Current reference profile and custom targets. Missing-data days are excluded per nutrient. Unlogged food isn't included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (goalIndex > 0) {
        val i = goalIndex
        var text by
            remember(i) {
                mutableStateOf(
                    if (v.repo.goal(i) > 0) fmt(v.repo.goal(i) * Nutrients.displayFactor(i)) else ""
                )
            }
        AlertDialog(
            onDismissRequest = { goalIndex = -1 },
            title = { Text("${Nutrients.NAMES[i]} target") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        text,
                        { text = it },
                        label = { Text("Daily ${v.repo.goalUnit(i)}") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    TextButton(
                        onClick = {
                            v.run {
                                v.saveGoal(i, null)
                                goalIndex = -1
                            }
                        }
                    ) {
                        Text(
                            if (v.repo.settings.has("nutrientProfile")) "Use profile reference"
                            else "Clear custom target"
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        v.run {
                            v.saveGoal(i, text.toDouble() / Nutrients.displayFactor(i))
                            goalIndex = -1
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { goalIndex = -1 }) { Text("Cancel") } },
        )
    }
}
