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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.*
import java.time.format.DateTimeFormatter

internal data class BalanceBar(val start: LocalDate, val end: LocalDate, val label: String)

internal enum class StatsPeriod(val label: String, val step: Period) {
    Day("Day", Period.ofDays(1)),
    Week("Week", Period.ofWeeks(1)),
    Month("Month", Period.ofMonths(1)),
    EightWeeks("8 wk", Period.ofWeeks(8)),
    Year("Year", Period.ofYears(1));

    fun bars(anchor: LocalDate, weekStart: Int): List<BalanceBar> {
        val week = LoggedHistory.weekStart(anchor, weekStart)
        val starts =
            when (this) {
                Day -> listOf(anchor)
                Week -> (0L..6L).map(week::plusDays)
                Month ->
                    (0L until anchor.lengthOfMonth().toLong()).map(
                        anchor.withDayOfMonth(1)::plusDays
                    )
                EightWeeks -> (0L..7L).map(week.minusWeeks(7)::plusWeeks)
                Year -> (0L..11L).map(anchor.withDayOfYear(1)::plusMonths)
            }
        return starts.map { start ->
            when (this) {
                Day,
                Week,
                Month -> BalanceBar(start, start.plusDays(1), start.dayOfMonth.toString())
                EightWeeks ->
                    BalanceBar(
                        start,
                        start.plusWeeks(1),
                        start.format(DateTimeFormatter.ofPattern("M/d")),
                    )
                Year ->
                    BalanceBar(
                        start,
                        start.plusMonths(1),
                        start.format(DateTimeFormatter.ofPattern("MMM")),
                    )
            }
        }
    }
}

/** Bucket long ranges for the chart while preserving the exact selected endpoints. */
internal fun customBars(start: LocalDate, days: Long): List<BalanceBar> {
    require(days > 0)
    val end = start.plusDays(days)
    val label = DateTimeFormatter.ofPattern(when {
        days <= 180 -> "M/d"
        days <= 1096 -> "MMM yy"
        else -> "yyyy"
    })
    val step = when {
        days <= 31 -> Period.ofDays(1)
        days <= 180 -> Period.ofWeeks(1)
        days <= 1096 -> Period.ofMonths(1)
        else -> Period.ofYears(1)
    }
    return buildList {
        var from = start
        while (from < end) {
            val until = minOf(from.plus(step), end)
            add(BalanceBar(from, until, from.format(label)))
            from = until
        }
    }
}

@Composable
internal fun PeriodPage(
    today: LocalDate,
    weekStart: Int,
    content: @Composable (List<BalanceBar>) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(StatsPeriod.Week) }
    var anchorText by rememberSaveable { mutableStateOf(today.toString()) }
    var customDays by rememberSaveable { mutableLongStateOf(0L) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    val anchor = LocalDate.parse(anchorText)
    val bars = remember(mode, anchor, weekStart, customDays) {
        if (customDays > 0) customBars(anchor, customDays) else mode.bars(anchor, weekStart)
    }
    val start = bars.first().start
    val end = bars.last().end
    Column {
        Column(Modifier.padding(horizontal = 20.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                SingleChoiceSegmentedButtonRow(
                    Modifier.horizontalScroll(rememberScrollState()).width(maxOf(maxWidth, 360.dp))
                ) {
                    StatsPeriod.entries.forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = customDays == 0L && mode == option,
                            onClick = {
                                mode = option
                                customDays = 0
                            },
                            icon = {},
                            shape = SegmentedButtonDefaults.itemShape(i, StatsPeriod.entries.size),
                        ) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = {
                    val previous = if (customDays > 0) anchor.minusDays(customDays) else anchor.minus(mode.step)
                    anchorText = previous.toString()
                }) {
                    Icon(Icons.Default.ChevronLeft, "Previous period")
                }
                TextButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DateRange, "Select date range", Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (customDays == 0L && mode == StatsPeriod.Year) anchor.year.toString()
                        else if (end == start.plusDays(1))
                            anchor.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                        else
                            "${start.format(DateTimeFormatter.ofPattern(if (start.year == end.minusDays(1).year) "MMM d" else "MMM d, yyyy"))} – ${end.minusDays(1).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}"
                    )
                }
                IconButton(
                    enabled = end <= today,
                    onClick = {
                        val next = if (customDays > 0)
                            minOf(end, today.minusDays(customDays - 1))
                        else minOf(today, anchor.plus(mode.step))
                        anchorText = next.toString()
                    },
                ) {
                    Icon(Icons.Default.ChevronRight, "Next period")
                }
            }
        }
        content(bars)
    }
    if (pickDate) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = start.toEpochDay() * 86_400_000L,
            initialSelectedEndDateMillis = minOf(today, end.minusDays(1)).toEpochDay() * 86_400_000L,
            selectableDates = remember(today) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) =
                        utcTimeMillis <= today.toEpochDay() * 86_400_000L
                }
            },
            initialDisplayedMonthMillis = start.toEpochDay() * 86_400_000L,
        )
        Dialog(
            onDismissRequest = { pickDate = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding().imePadding()) {
                    DateRangePicker(state, Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { pickDate = false }) { Text("Cancel") }
                        Button(
                            enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                            onClick = {
                                val first = Instant.ofEpochMilli(state.selectedStartDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                                val last = Instant.ofEpochMilli(state.selectedEndDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                                anchorText = first.toString()
                                customDays = last.toEpochDay() - first.toEpochDay() + 1
                                pickDate = false
                            },
                        ) { Text("Apply range") }
                    }
                }
            }
        }
    }
}
