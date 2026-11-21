package dev.rohit.labelaudit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun NutrientIntakeDialog(
    repo: TrackerRepository,
    history: LoggedHistory,
    start: LocalDate,
    end: LocalDate,
    i: Int,
    close: () -> Unit,
) {
    val single = end == start.plusDays(1)
    var picked by rememberSaveable(i, start.toString(), end.toString()) { mutableStateOf<String?>(null) }
    val date = if (single) start else picked?.let(LocalDate::parse)
    fun amountText(total: Nutrients.Total) =
        if (total.known[i]) repo.nutrientText(total, i) else "Unreported"
    val goal = repo.goal(i)
    val limit = Nutrients.KEYS[i] == "sodium"
    val format = DateTimeFormatter.ofPattern("MMM d, yyyy")
    AlertDialog(
        onDismissRequest = close,
        title = { Text(Nutrients.NAMES[i]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (date != null) {
                    val total = history.days[date]?.nutrition
                    Text(date.format(format), style = MaterialTheme.typography.labelLarge)
                    if (total == null) Text("No food logged")
                    else if (!total.known[i]) Text("No logged food reports an amount for ${Nutrients.NAMES[i].lowercase()}.")
                    else {
                        val foods = remember(history, date, i) {
                            repo.foodContributions(repo.midnight(date), repo.midnight(date.plusDays(1)), history.asOf)
                                .entries.filter { it.value.known[i] }.sortedWith(
                                    compareByDescending<Map.Entry<String, Nutrients.Total>> { it.value.values[i] }
                                        .thenBy { it.key }
                                )
                        }
                        Text("Recorded intake", style = MaterialTheme.typography.labelLarge)
                        Text(amountText(total), style = MaterialTheme.typography.headlineSmall)
                        NutrientTableHeader("Food", "Amount", false)
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(foods, key = { it.key }) { (food, amount) ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(food, Modifier.weight(1f))
                                    Text(amountText(amount))
                                }
                            }
                        }
                    }
                } else {
                    if (goal > 0) Text("Daily ${if (limit) "limit" else "target"} · ${fmt(goal * Nutrients.displayFactor(i))} ${repo.goalUnit(i)}")
                    val last = minOf(history.today, end.minusDays(1))
                    val count = (last.toEpochDay() - start.toEpochDay() + 1).coerceAtLeast(0).toInt()
                    NutrientTableHeader("Date", "Intake", goal > 0)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(count) { offset ->
                            val day = last.minusDays(offset.toLong())
                            val total = history.days[day]?.nutrition
                            Row(Modifier.fillMaxWidth().clickable { picked = day.toString() }.padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(if (day == history.today) "Today · so far" else day.format(format), Modifier.weight(1f))
                                Text(if (total == null) "No log" else amountText(total))
                                if (goal > 0) {
                                    val met = total?.let { Nutrients.targetMet(it.values[i], goal, limit) }
                                    Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
                                        when (met) {
                                            true -> Icon(Icons.Default.Check, if (limit) "Within limit" else "Target reached", tint = Color(0xFF91D6AC))
                                            false -> Icon(Icons.Default.Close, if (limit) "Over limit" else "Below target", tint = MaterialTheme.colorScheme.error)
                                            null -> Text("—")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (goal > 0) Text("Checks use recorded amounts. — means no food log.", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        dismissButton = {
            if (!single && picked != null) TextButton(onClick = { picked = null }) { Text("All dates") }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done") } },
    )
}

@Composable
private fun NutrientTableHeader(first: String, amount: String, showTarget: Boolean) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Row(
            Modifier.fillMaxWidth().semantics { heading() }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideTextStyle(MaterialTheme.typography.titleSmall) {
                Text(first, Modifier.weight(1f))
                Text(amount)
                if (showTarget) Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
                    Text("Goal")
                }
            }
        }
    }
}
