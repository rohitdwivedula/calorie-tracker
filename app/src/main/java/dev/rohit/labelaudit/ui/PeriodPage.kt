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

@Composable
internal fun PeriodPage(
    today: LocalDate,
    weekStart: Int,
    content: @Composable (List<BalanceBar>) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(StatsPeriod.Week) }
    var anchorText by rememberSaveable { mutableStateOf(today.toString()) }
    var pickDate by remember { mutableStateOf(false) }
    val anchor = LocalDate.parse(anchorText)
    val bars = remember(mode, anchor, weekStart) { mode.bars(anchor, weekStart) }
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
                            selected = mode == option,
                            onClick = { mode = option },
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
                IconButton(onClick = { anchorText = anchor.minus(mode.step).toString() }) {
                    Icon(Icons.Default.ChevronLeft, "Previous period")
                }
                TextButton(onClick = { pickDate = true }) {
                    Text(
                        if (mode == StatsPeriod.Year) anchor.year.toString()
                        else if (mode == StatsPeriod.Day)
                            anchor.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                        else
                            "${start.format(DateTimeFormatter.ofPattern("MMM d"))} – ${end.minusDays(1).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}"
                    )
                }
                IconButton(
                    enabled = end <= today,
                    onClick = { anchorText = minOf(today, anchor.plus(mode.step)).toString() },
                ) {
                    Icon(Icons.Default.ChevronRight, "Next period")
                }
            }
        }
        content(bars)
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
