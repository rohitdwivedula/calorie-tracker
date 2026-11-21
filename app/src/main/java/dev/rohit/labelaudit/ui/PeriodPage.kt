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
import java.time.*
import java.time.format.DateTimeFormatter

internal data class BalanceBar(val start: LocalDate, val end: LocalDate, val label: String)

@Composable
internal fun PeriodPage(
    today: LocalDate,
    weekStart: Int,
    defaultMode: Int,
    content: @Composable (Int, List<BalanceBar>) -> Unit,
) {
    var mode by rememberSaveable { mutableIntStateOf(defaultMode) }
    var anchorText by rememberSaveable { mutableStateOf(today.toString()) }
    var pickDate by remember { mutableStateOf(false) }
    val anchor = LocalDate.parse(anchorText)
    val start =
        when (mode) {
            0 -> LoggedHistory.weekStart(anchor, weekStart)
            1 -> LoggedHistory.weekStart(anchor, weekStart).minusWeeks(7)
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
    Page {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Days", "Weeks", "Months").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = mode == i,
                    onClick = { mode = i },
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
                enabled = end <= today,
                onClick = {
                    anchorText =
                        when (mode) {
                            0 -> anchor.plusWeeks(1)
                            1 -> anchor.plusWeeks(8)
                            else -> anchor.plusYears(1)
                        }.toString()
                },
            ) {
                Icon(Icons.Default.ChevronRight, "Next period")
            }
        }
        key(mode, start) { content(mode, bars) }
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
