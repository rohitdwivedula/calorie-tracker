@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.rohit.labelaudit

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun SummaryScreen(v: TrackerViewModel) {
    val r = v.repo
    val history = remember(v.revision) { LoggedHistory(r, System.currentTimeMillis()) }
    var deficit by rememberSaveable { mutableStateOf(true) }
    PeriodPage(history.today, r.settings.getInt("week"), 0) { mode, bars ->
        var selected by rememberSaveable { mutableIntStateOf(-1) }
        val start = bars.first().start
        val end = bars.last().end
        val summaries = bars.map { history.summary(it.start, it.end) }
        val period = history.summary(start, end)
        val focused = selected.takeIf { it in bars.indices }
        val shown = if (focused == null) period else summaries[focused]
        val from = if (focused == null) start else bars[focused].start
        val until = if (focused == null) end else bars[focused].end
        val daysCount = java.time.temporal.ChronoUnit.DAYS.between(from, until)
        val pattern = DateTimeFormatter.ofPattern("MMM d, yyyy")
        val rangeText = "${from.format(pattern)} – ${until.minusDays(1).format(pattern)}"
        val singleDay = daysCount == 1L
        val currentWeek = daysCount == 7L && history.today >= from && history.today < until
        Section(
            when {
                currentWeek -> "This week so far"
                singleDay -> from.format(pattern)
                focused != null -> rangeText
                else -> "Period total"
            }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(deficit, { deficit = true }, label = { Text("Deficit") })
                FilterChip(!deficit, { deficit = false }, label = { Text("Intake") })
            }
            val value = if (deficit) shown.deficit() else shown.nutrition.values[0]
            Metric(
                if (!deficit) "Intake" else "Estimated ${if(value >= 0) "deficit" else "surplus"}",
                if (shown.loggedDays == 0) "—" else "${kcal(abs(value))} kcal",
                "${shown.loggedDays}/$daysCount days logged${if(history.today >= from && history.today < until) " · today so far" else ""}",
            )
            if (daysCount == 7L)
                Text(
                    "Full-week deficit goal · ${kcal(r.settings.getDouble("goal"))} kcal",
                    style = MaterialTheme.typography.bodySmall,
                )
            BalanceChart(bars, summaries, deficit, focused) {
                selected = if (selected == it) -1 else it
            }
            Text(
                "Tap a bar to inspect · empty days excluded",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (shown.allocatedKcal > 0)
                Text(
                    "Includes ${kcal(shown.allocatedKcal)} kcal estimated from older dishes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (shown.incompleteDays > 0)
                Text(
                    "Incomplete expenditure data · ${shown.incompleteDays} ${if (shown.incompleteDays == 1) "day" else "days"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (focused != null && mode == 0)
                TextButton(onClick = { v.openEnergy(from) }) { Text("View expenditure") }
        }
        Section(if (singleDay) "That day" else "Daily averages") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((label, sum) in
                    listOf(
                        "Intake" to shown.nutrition.values[0],
                        "Expenditure" to shown.expenditure,
                    )) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (shown.loggedDays == 0) "—"
                            else "${kcal(sum / shown.loggedDays)} kcal",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
            HorizontalDivider()
            for (i in listOf(1, 4, 2, 3)) {
                if (singleDay)
                    InfoRow(
                        Nutrients.NAMES[i],
                        if (shown.loggedDays == 0) "—" else r.nutrientText(shown.nutrition, i),
                    )
                else {
                    val days = history.nutrientDays(from, until, i)
                    InfoRow(Nutrients.NAMES[i], days.averageText(i))
                }
            }
            if (
                shown.loggedDays > 0 &&
                    (1..4).any { shown.nutrition.known[it] && shown.nutrition.missing[it] }
            )
                Text("≥ includes only known amounts.", style = MaterialTheme.typography.bodySmall)
            TextButton(
                enabled = shown.loggedDays > 0,
                onClick = {
                    v.clearForm("history")
                    v.field("history", "from", r.midnight(from).toString(), true)
                    v.field("history", "until", r.midnight(until).toString(), true)
                    v.go("history")
                },
            ) {
                Text("View food log")
            }
        }
    }
}

@Composable
private fun BalanceChart(
    bars: List<BalanceBar>,
    totals: List<LoggedHistory.Summary>,
    deficit: Boolean,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    val values = totals.map { if (deficit) it.deficit() else it.nutrition.values[0] }
    val scale = maxOf(1.0, values.maxOf { abs(it) })
    val hasNegative = values.any { it < 0 }
    val height = if (hasNegative) 80.dp else 150.dp
    val scroll = rememberScrollState()
    LaunchedEffect(bars.first().start, bars.size, scroll.maxValue) {
        val latest = totals.indexOfLast { it.loggedDays > 0 }
        if (bars.size > 7 && latest >= 0)
            scroll.scrollTo((scroll.maxValue * latest.toFloat() / (bars.size - 1)).toInt())
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bars.forEachIndexed { i, bar ->
            val value = values[i]
            val logged = totals[i].loggedDays > 0
            val color =
                if (!logged) MaterialTheme.colorScheme.outlineVariant
                else if (value < 0 && deficit) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary
            Column(
                Modifier.width(if (bars.size == 7) 30.dp else 44.dp)
                    .clickable { onSelect(i) }
                    .semantics {
                        val kind =
                            if (!deficit) "intake" else if (value >= 0) "deficit" else "surplus"
                        contentDescription =
                            "${bar.start}: " +
                                if (!logged) "no logs" else "${kcal(abs(value))} kcal $kind"
                        this.selected = selected == i
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.height(height).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (value >= 0 || !logged)
                        Box(
                            Modifier.width(14.dp)
                                .height(
                                    if (!logged) 2.dp
                                    else maxOf(3.dp, height * (abs(value) / scale).toFloat())
                                )
                                .background(color, MaterialTheme.shapes.small)
                        )
                }
                HorizontalDivider(
                    color =
                        if (selected == i) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outlineVariant
                )
                if (hasNegative)
                    Box(
                        Modifier.height(height).fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        if (value < 0)
                            Box(
                                Modifier.width(14.dp)
                                    .height(maxOf(3.dp, height * (abs(value) / scale).toFloat()))
                                    .background(color, MaterialTheme.shapes.small)
                            )
                    }
                Text(
                    bar.label,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (selected == i) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
