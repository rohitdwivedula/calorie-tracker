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
import java.time.*
import java.time.format.DateTimeFormatter
import org.json.*

@Composable
fun SummaryScreen(v: TrackerViewModel) {
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val r = v.repo
    val now = remember(v.revision, offset) { System.currentTimeMillis() }
    val tracking = r.midnight(LocalDate.parse(r.settings.getString("start")))
    val today = LocalDate.now(r.zone)
    val week =
        today
            .minusDays(Math.floorMod(today.dayOfWeek.value - r.settings.getInt("week"), 7).toLong())
            .plusWeeks(offset.toLong())
    val from = maxOf(tracking, r.midnight(week))
    val to = maxOf(from, minOf(now, r.midnight(week.plusWeeks(1))))
    val n = r.nutrientTotals(from, to, now)
    val exp = r.expenditure(from, to)
    val balance = exp - n.values[0]
    val cumulative = r.expenditure(tracking, now) - r.intake(tracking, now, now)[0]
    val target = (0..6).sumOf { r.rate(week.plusDays(it.toLong())) } - r.settings.getDouble("goal")
    Page {
        Section("Estimated cumulative ${if(cumulative>=0)"deficit"else "surplus"}") {
            Metric(
                "Since ${r.settings.optString("start")}",
                "${fmt(kotlin.math.abs(cumulative))} kcal",
            )
        }
        Section(
            if (offset == 0) "This week · so far"
            else "Week of ${week.format(DateTimeFormatter.ofPattern("MMM d"))}"
        ) {
            InfoRow("Estimated intake", r.nutrientText(n, 0))
            InfoRow("Estimated expenditure", "${fmt(exp)} kcal")
            InfoRow(
                "Estimated ${if(balance>=0)"deficit"else "surplus"}",
                "${fmt(kotlin.math.abs(balance))} kcal",
            )
            HorizontalDivider()
            InfoRow("Protein", r.nutrientText(n, 1))
            InfoRow("Fiber", r.nutrientText(n, 4))
            InfoRow("Carbs", r.nutrientText(n, 2))
            InfoRow("Fat", r.nutrientText(n, 3))
            if (
                r.db.list("batch").any {
                    it.isNull("completed") &&
                        !it.optBoolean("portionTracking") &&
                        r.batchFraction(it, from, to, now) > 0
                }
            )
                Text(
                    "Includes provisional dish estimates",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        Section("Full-week targets") {
            InfoRow("Intake", "${fmt(target)} kcal")
            InfoRow("Deficit goal", "${fmt(r.settings.getDouble("goal"))} kcal")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { offset-- }) {
                Icon(Icons.Default.ArrowBack, null)
                Text("Previous")
            }
            if (offset < 0)
                OutlinedButton(onClick = { offset++ }) {
                    Text("Next")
                    Icon(Icons.Default.ArrowForward, null)
                }
        }
    }
}
