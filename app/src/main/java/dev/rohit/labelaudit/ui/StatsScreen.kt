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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.*
import org.json.*

@Composable
fun StatsScreen(v: TrackerViewModel) {
    var period by rememberSaveable { mutableIntStateOf(7) }
    var group by rememberSaveable { mutableIntStateOf(0) }
    var goalIndex by rememberSaveable { mutableIntStateOf(-1) }
    val days =
        remember(period, v.revision) {
            val today = LocalDate.now(v.repo.zone)
            val start =
                maxOf(
                    today.minusDays(period.toLong()),
                    LocalDate.parse(v.repo.settings.getString("start")),
                )
            buildList {
                var day = start
                while (day < today) {
                    add(
                        v.repo.nutrientTotals(
                            v.repo.midnight(day),
                            v.repo.midnight(day.plusDays(1)),
                            System.currentTimeMillis(),
                        )
                    )
                    day = day.plusDays(1)
                }
            }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30).forEach { p ->
                    FilterChip(
                        selected = period == p,
                        onClick = { period = p },
                        label = { Text("$p days") },
                    )
                }
            }
        }
        item {
            Text(
                "${days.size} completed tracking days · today excluded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    "Daily averages start after your first full tracking day.",
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
                                "Set goal",
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                    Text(
                        if (tally.average().isNaN()) "—"
                        else
                            "${fmt(tally.average()*Nutrients.displayFactor(i))} ${Nutrients.unit(i)} / day",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (i > 0) {
                        val goal = v.repo.goal(i)
                        Text(
                            if (goal > 0)
                                "${tally.met}/${days.size} days ${if(Nutrients.KEYS[i]=="sodium")"within limit"else "met goal"} · ${fmt(goal*Nutrients.displayFactor(i))} ${Nutrients.unit(i)}"
                            else "Set a daily ${if(Nutrients.KEYS[i]=="sodium")"limit"else "goal"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (days.isNotEmpty() && goal > 0)
                            LinearProgressIndicator(
                                progress = { tally.met.toFloat() / days.size },
                                Modifier.fillMaxWidth(),
                            )
                    }
                    if (tally.unknown > 0)
                        Text(
                            "${tally.unknown} days incomplete · average uses ${tally.complete} complete days",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        }
        item {
            Text(
                "Current personal goals, not personalized RDAs. Missing-data days are excluded per nutrient. Unlogged food isn't included.",
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
            title = {
                Text("${Nutrients.NAMES[i]} ${if(Nutrients.KEYS[i]=="sodium")"limit"else "goal"}")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        text,
                        { text = it },
                        label = { Text("Daily ${Nutrients.unit(i)}") },
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
                        Text("Clear goal")
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
