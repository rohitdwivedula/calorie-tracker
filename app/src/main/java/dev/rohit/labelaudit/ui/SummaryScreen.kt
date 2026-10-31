@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.rohit.labelaudit

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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

private data class BalanceBar(val start: LocalDate, val end: LocalDate, val label: String)

@Composable
fun SummaryScreen(v: TrackerViewModel) {
    val r = v.repo
    val history = remember(v.revision) { LoggedHistory(r, System.currentTimeMillis()) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var anchorText by rememberSaveable { mutableStateOf(history.today.toString()) }
    var deficit by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var pickDate by remember { mutableStateOf(false) }
    val anchor = LocalDate.parse(anchorText)
    val start =
        when (mode) {
            0 -> LoggedHistory.weekStart(anchor, r.settings.getInt("week"))
            1 -> LoggedHistory.weekStart(anchor, r.settings.getInt("week")).minusWeeks(7)
            else -> anchor.withDayOfYear(1)
        }
    val bars =
        when (mode) {
            0 ->
                (0..6).map {
                    val d = start.plusDays(it.toLong())
                    BalanceBar(d, d.plusDays(1), d.dayOfMonth.toString())
                }
            1 ->
                (0..7).map {
                    val d = start.plusWeeks(it.toLong())
                    BalanceBar(d, d.plusWeeks(1), d.format(DateTimeFormatter.ofPattern("M/d")))
                }
            else ->
                (0..11).map {
                    val d = start.plusMonths(it.toLong())
                    BalanceBar(d, d.plusMonths(1), d.format(DateTimeFormatter.ofPattern("MMM")))
                }
        }
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
    val cumulative =
        history.summary(LocalDate.parse(r.settings.getString("start")), history.today.plusDays(1))
    Page {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Days", "Weeks", "Months").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = mode == i,
                    onClick = {
                        mode = i
                        selected = -1
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                ) {
                    Text(label)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = {
                    anchorText =
                        when (mode) {
                            0 -> anchor.minusWeeks(1)
                            1 -> anchor.minusWeeks(8)
                            else -> anchor.minusYears(1)
                        }.toString()
                    selected = -1
                }
            ) {
                Icon(Icons.Default.ChevronLeft, "Previous period")
            }
            TextButton(onClick = { pickDate = true }) {
                Text(
                    if (mode == 2) anchor.year.toString()
                    else
                        "${start.format(DateTimeFormatter.ofPattern("MMM d"))} – ${end.minusDays(1).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}"
                )
            }
            IconButton(
                enabled = end <= history.today,
                onClick = {
                    anchorText =
                        when (mode) {
                            0 -> anchor.plusWeeks(1)
                            1 -> anchor.plusWeeks(8)
                            else -> anchor.plusYears(1)
                        }.toString()
                    selected = -1
                },
            ) {
                Icon(Icons.Default.ChevronRight, "Next period")
            }
        }
        Section(if (focused == null) "Period total" else rangeText) {
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
            HorizontalDivider()
            InfoRow(
                "Average daily intake",
                if (shown.loggedDays == 0) "—" else "${kcal(shown.averageIntake())} kcal",
            )
            InfoRow(
                "Estimated expenditure",
                if (shown.loggedDays == 0) "—" else "${kcal(shown.expenditure)} kcal",
            )
            InfoRow("Protein", r.nutrientText(shown.nutrition, 1))
            InfoRow("Fiber", r.nutrientText(shown.nutrition, 4))
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
        if (mode == 0)
            Section("Full-week target") {
                val target =
                    (0..6).sumOf { r.rate(start.plusDays(it.toLong())) } -
                        r.settings.getDouble("goal")
                InfoRow("Intake", "${kcal(target)} kcal")
                InfoRow("Deficit goal", "${kcal(r.settings.getDouble("goal"))} kcal")
            }
        Section("Since tracking began") {
            Metric(
                "Estimated ${if(cumulative.deficit() >= 0) "deficit" else "surplus"}",
                if (cumulative.loggedDays == 0) "—" else "${kcal(abs(cumulative.deficit()))} kcal",
                "${cumulative.loggedDays} days logged",
            )
        }
    }
    if (pickDate) {
        val state =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    anchor.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            anchorText =
                                Instant.ofEpochMilli(it)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                    .toString()
                        }
                        selected = -1
                        pickDate = false
                    }
                ) {
                    Text("Show period")
                }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state)
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
