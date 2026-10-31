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
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import org.json.*

@Composable
fun TodayScreen(v: TrackerViewModel, scan: () -> Unit) {
    val r = v.repo
    val revision = v.revision
    val now = remember(revision) { System.currentTimeMillis() }
    val today = LocalDate.now(r.zone)
    val start = maxOf(r.midnight(today), r.midnight(LocalDate.parse(r.settings.getString("start"))))
    val week =
        maxOf(
            r.midnight(today.minusDays((today.dayOfWeek.value % 7).toLong())),
            r.midnight(LocalDate.parse(r.settings.getString("start"))),
        )
    val totals = remember(revision) { r.nutrientTotals(start, now, now) }
    val daily = r.expenditure(start, now) - totals.values[0]
    val weekly = r.expenditure(week, now) - r.intake(week, now, now)[0]
    val batches = remember(revision) { r.db.list("batch").filter { it.isNull("completed") } }
    Page {
        Section(today.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))) {
            Text(
                "${kcal(totals.values[0])} kcal",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Protein",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(r.nutrientText(totals, 1), style = MaterialTheme.typography.titleLarge)
                    if (r.goal(1) > 0)
                        Text(
                            "of ${fmt(r.goal(1))} g goal",
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Fiber",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(r.nutrientText(totals, 4), style = MaterialTheme.typography.titleLarge)
                    if (r.goal(4) > 0)
                        Text(
                            "of ${fmt(r.goal(4))} g goal",
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
            }
            if (totals.missing[1] || totals.missing[4])
                Text(
                    "Some nutrients are unknown · ≥ is a known minimum",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Metric(
                        "Today's ${if(daily>=0)"deficit" else "surplus"}",
                        "${kcal(kotlin.math.abs(daily))} kcal",
                        "Estimated so far",
                    )
                }
            }
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Metric(
                        "Weekly ${if(weekly>=0)"deficit" else "surplus"}",
                        "${kcal(kotlin.math.abs(weekly))} kcal",
                        "Est. Sunday–now",
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { v.run { v.beginCook() } },
                Modifier.weight(1f).heightIn(min = 52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("New dish", maxLines = 1)
            }
            FilledTonalButton(
                onClick = scan,
                Modifier.weight(1f).heightIn(min = 52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Icon(Icons.Default.QrCodeScanner, null)
                Spacer(Modifier.width(8.dp))
                Text("Quick log", maxLines = 1)
            }
        }
        Text("Available dishes", style = MaterialTheme.typography.titleLarge)
        if (batches.isEmpty())
            EmptyState(
                Icons.Default.DinnerDining,
                "Ready when you cook",
                "Your available dishes will appear here.",
            )
        batches.forEach { b ->
            Card(onClick = { v.detail("batch", b.getString("id")) }, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(b.optString("name"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (b.optBoolean("portionTracking")) "${fmt(r.loggedShare(b)*100)}% logged"
                        else "Provisional estimate until a portion is logged",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            onClick = { v.portionBatch = b.getString("id") },
                            Modifier.weight(1f),
                        ) {
                            Text("Ate")
                        }
                        OutlinedButton(
                            onClick = {
                                v.run {
                                    if (BatchOperations.remaining(b) <= 1e-9)
                                        v.finish(b.getString("id"))
                                    else v.finishBatch = b.getString("id")
                                }
                            },
                            Modifier.weight(1f),
                        ) {
                            Text("Finished", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
