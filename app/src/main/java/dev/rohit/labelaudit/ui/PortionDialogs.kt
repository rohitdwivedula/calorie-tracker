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
import androidx.compose.ui.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.*
import org.json.*

@Composable
fun PortionDialog(v: TrackerViewModel, id: String) {
    val batch = remember(id) { v.record("batch", id) }
    val left = BatchOperations.remaining(batch)
    val ticks =
        remember(id) {
            listOf(.1, .25, 1.0 / 3, .5, 2.0 / 3, .75, 1.0).filter { it < left - 1e-9 } + left
        }
    var fraction by remember { mutableDoubleStateOf(minOf(1.0 / 3, left)) }
    var percent by remember { mutableStateOf(fmt(fraction * 100)) }
    val calories = v.repo.nutrition(batch)[0]
    AlertDialog(
        onDismissRequest = { v.portionBatch = null },
        title = { Text("How much did you eat?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(batch.optString("name"), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${fmt(left*100)}% of the whole dish remains",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value =
                        (ticks.indices.minByOrNull { kotlin.math.abs(ticks[it] - fraction) } ?: 0)
                            .toFloat(),
                    onValueChange = {
                        fraction = ticks[it.toInt()]
                        percent = fmt(fraction * 100)
                    },
                    valueRange = 0f..maxOf(1, ticks.size - 1).toFloat(),
                    steps = maxOf(0, ticks.size - 2),
                    enabled = ticks.size > 1,
                )
                OutlinedTextField(
                    percent,
                    {
                        percent = it
                        fraction = (it.toDoubleOrNull() ?: Double.NaN) / 100
                    },
                    label = { Text("Percent of whole dish") },
                    suffix = { Text("%") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (fraction.isFinite())
                    Text(
                        "${fmt(fraction*calories)} kcal",
                        style = MaterialTheme.typography.titleLarge,
                    )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { v.run { v.recordPortion(id, fraction) } },
                enabled = fraction.isFinite() && fraction > 0 && fraction <= left + 1e-9,
            ) {
                Text("Log portion")
            }
        },
        dismissButton = { TextButton(onClick = { v.portionBatch = null }) { Text("Cancel") } },
    )
}

@Composable
fun TimestampDialog(initial: Long, zone: ZoneId, onDismiss: () -> Unit, onDone: (Long) -> Unit) {
    val local = Instant.ofEpochMilli(initial).atZone(zone)
    var clock by remember { mutableStateOf(false) }
    val date =
        rememberDatePickerState(
            initialSelectedDateMillis =
                local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    val time =
        rememberTimePickerState(
            initialHour = local.hour,
            initialMinute = local.minute,
            is24Hour = true,
        )
    if (!clock)
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { clock = true }, enabled = date.selectedDateMillis != null) {
                    Text("Next")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            DatePicker(date)
        }
    else
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Time · 24-hour") },
            text = { TimeInput(time) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val day =
                            Instant.ofEpochMilli(date.selectedDateMillis!!)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                        onDone(
                            day.atTime(time.hour, time.minute)
                                .atZone(zone)
                                .toInstant()
                                .toEpochMilli()
                        )
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { clock = false }) { Text("Back") } },
        )
}

@Composable
fun EventDialog(v: TrackerViewModel, id: String, index: Int) {
    val batch = remember(id, index) { v.record("batch", id) }
    val event = batch.getJSONArray("eatingEvents").getJSONObject(index)
    var percent by remember { mutableStateOf(fmt(event.getDouble("fraction") * 100)) }
    var time by remember { mutableLongStateOf(event.getLong("time")) }
    var datePicker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { v.eventEdit = null },
        title = { Text("Recorded portion") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    percent,
                    { percent = it },
                    label = { Text("Percent of whole dish") },
                    suffix = { Text("%") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                TextButton(onClick = { datePicker = true }) {
                    Icon(Icons.Default.Schedule, null)
                    Text(whenText(time, v.repo.zone))
                }
                TextButton(
                    onClick = {
                        v.run { v.updateEvent(id, index, event.getDouble("fraction"), time, true) }
                    }
                ) {
                    Icon(Icons.Default.Delete, null)
                    Text("Remove portion")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    v.run {
                        val fraction =
                            if (percent == fmt(event.getDouble("fraction") * 100))
                                event.getDouble("fraction")
                            else percent.toDouble() / 100
                        v.updateEvent(id, index, fraction, time, false)
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = { v.eventEdit = null }) { Text("Cancel") } },
    )
    if (datePicker)
        TimestampDialog(time, v.repo.zone, { datePicker = false }) {
            time = it
            datePicker = false
        }
}
