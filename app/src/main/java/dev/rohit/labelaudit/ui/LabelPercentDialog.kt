package dev.rohit.labelaudit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LabelPercentDialog(
    key: String,
    existing: JSONObject?,
    previousStandard: String,
    dismiss: () -> Unit,
    apply: (JSONObject, Double?) -> Unit,
) {
    val i = Nutrients.KEYS.indexOf(key)
    var percent by
        rememberSaveable(key) { mutableStateOf(existing?.getDouble("percent")?.let(::fmt) ?: "") }
    var standard by
        rememberSaveable(key) {
            mutableStateOf(existing?.getString("standard") ?: previousStandard)
        }
    var form by rememberSaveable(key) { mutableStateOf(existing?.getString("form") ?: "unknown") }
    var showForms by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    val value = percent.trim().removeSuffix("%").trim().toDoubleOrNull()
    val valid = value != null && value.isFinite() && value >= 0 && value <= 100000
    val grams = if (valid) LabelDailyValues.grams(key, value!!, standard, form) else null
    fun formLabel(value: String) =
        when (value) {
            "retinol" -> "Retinol (preformed vitamin A)"
            "food-beta-carotene" -> "Beta-carotene in food"
            "other-food-carotenoids" -> "Other food provitamin A carotenoids"
            "natural-alpha" -> "Natural alpha-tocopherol"
            "synthetic-alpha" -> "Synthetic alpha-tocopherol"
            "food-folate" -> "Naturally occurring food folate"
            "folic-acid-with-food" -> "Folic acid in fortified food"
            else -> "Unknown or mixed"
        }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("${Nutrients.NAMES[i]} · % DV") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    percent,
                    {
                        percent = it
                        invalid = false
                    },
                    label = { Text("Percent Daily Value") },
                    suffix = { Text("%") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = invalid,
                    supportingText =
                        if (invalid) ({ Text("Enter a nonnegative percentage") }) else null,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                            LabelDailyValues.CURRENT to "Current US",
                            LabelDailyValues.LEGACY to "Older US",
                        )
                        .forEach { (id, label) ->
                            FilterChip(
                                selected = standard == id,
                                onClick = {
                                    standard = id
                                    form = "unknown"
                                },
                                label = { Text(label) },
                            )
                        }
                }
                Text(
                    "Older labels often list ‘Calories from Fat’. These references are label values, not your personal goals.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (LabelDailyValues.formKind(key, standard).isNotBlank()) {
                    Text("Nutrient form", style = MaterialTheme.typography.labelMedium)
                    Box {
                        TextButton(onClick = { showForms = true }) {
                            Text(formLabel(form))
                            Icon(Icons.Default.ExpandMore, null)
                        }
                        DropdownMenu(
                            expanded = showForms,
                            onDismissRequest = { showForms = false },
                        ) {
                            LabelDailyValues.forms(key, standard).forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(formLabel(option)) },
                                    onClick = {
                                        form = option
                                        showForms = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (valid)
                    Text(
                        if (grams != null)
                            "≈ ${fmt(grams * Nutrients.displayFactor(i))} ${Nutrients.unit(i)} per nutrition basis"
                        else
                            "The percentage will be saved; the amount stays unknown until the nutrient form is known.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!valid) invalid = true
                    else apply(LabelDailyValues.entry(key, value!!, standard, form), grams)
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}
