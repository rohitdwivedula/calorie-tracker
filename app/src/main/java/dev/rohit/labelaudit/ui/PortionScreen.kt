@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.rohit.labelaudit

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.time.*
import org.json.*

@Composable
fun PortionScreen(v: TrackerViewModel) {
    val code = v.lookupBarcode
    var editing by rememberSaveable(code) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (code.isNotBlank())
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                SuggestionChip(
                    onClick = { editing = true },
                    label = { Text(code, fontFamily = FontFamily.Monospace) },
                    icon = { Icon(Icons.Default.Edit, "Edit barcode", Modifier.size(16.dp)) },
                )
            }
        Box(Modifier.weight(1f)) { PortionContent(v) }
    }
    if (editing) {
        var input by
            rememberSaveable(code, stateSaver = TextFieldValue.Saver) {
                mutableStateOf(TextFieldValue(code, TextRange(0, code.length)))
            }
        var invalid by rememberSaveable(code) { mutableStateOf(false) }
        fun submit() {
            val corrected = input.text.trim()
            if (!corrected.matches(Regex("[0-9]{8}|[0-9]{12,14}"))) invalid = true
            else {
                editing = false
                v.lookup(corrected)
            }
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit barcode") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        invalid = false
                    },
                    label = { Text("Barcode number") },
                    singleLine = true,
                    isError = invalid,
                    supportingText =
                        if (invalid) ({ Text("Enter 8, 12, 13 or 14 digits") }) else null,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Search,
                        ),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                )
            },
            confirmButton = { TextButton(onClick = { submit() }) { Text("Look up") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PortionContent(v: TrackerViewModel) {
    if (!v.completeFood) {
        FoodLookupPlaceholder(v)
        return
    }
    val f = v.food
    val grams = f.optDouble("servingGrams", 0.0)
    val ml = f.optDouble("servingMl", 0.0)
    val base = f.getString("unit")
    val units = buildList {
        if (base == "serving" || base == "g" && grams > 0 || base == "ml" && ml > 0) add("serving")
        if (base == "g" || base == "serving" && grams > 0) add("g")
        if (base == "ml" || base == "serving" && ml > 0) add("ml")
    }
    val unit = v.field("portion", "unit", units.first())
    val defaultCanonical =
        if (units.first() == "serving") Portion.convert(1.0, "serving", base, grams, ml)
        else f.getDouble("basis")
    val canonical = v.field("portion", "canonical", defaultCanonical.toString()).toDoubleOrNull()
    val initialAmount = Portion.convert(canonical ?: defaultCanonical, base, unit, grams, ml)
    val amount = v.field("portion", "amount", fmt(initialAmount))
    val factor = (canonical ?: 0.0) / f.getDouble("basis")
    var more by rememberSaveable(f.getString("id")) { mutableStateOf(false) }
    fun change(value: Double) {
        v.field("portion", "amount", fmt(value), true)
        v.field(
            "portion",
            "canonical",
            Portion.convert(value, unit, base, grams, ml).toString(),
            true,
        )
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (v.selectedFoodSource != null)
                Text(
                    v.selectedFoodSource!!.label +
                        if (f.optString("brand").isNotBlank()) " · " + f.optString("brand") else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Section(
                f.optString("name"),
                f.optString("servingLabel").ifBlank {
                    if (base == "serving") "Label serving"
                    else "Nutrition per ${fmt(f.getDouble("basis"))} $base"
                },
            ) {
                Text(
                    if (canonical == null) "— kcal" else "${fmt(f.getDouble("kcal")*factor)} kcal",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    OutlinedTextField(
                        amount,
                        { text ->
                            v.field("portion", "amount", text, true)
                            val value = text.toDoubleOrNull()
                            v.field(
                                "portion",
                                "canonical",
                                if (value != null && value > 0)
                                    Portion.convert(value, unit, base, grams, ml).toString()
                                else "",
                                true,
                            )
                        },
                        Modifier.width(160.dp),
                        label = { Text("Quantity") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                Slider(
                    value =
                        (amount.toFloatOrNull() ?: 1f).coerceIn(
                            1f,
                            if (unit == "serving") 8f else 2000f,
                        ),
                    onValueChange = {
                        change(
                            if (unit == "serving") 1 + kotlin.math.round((it - 1) * 4) / 4.0
                            else kotlin.math.round(it).toDouble()
                        )
                    },
                    valueRange = 1f..if (unit == "serving") 8f else 2000f,
                    steps = if (unit == "serving") 27 else 0,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    units.forEach { u ->
                        FilterChip(
                            selected = unit == u,
                            onClick = {
                                if (canonical != null) {
                                    v.field(
                                        "portion",
                                        "amount",
                                        java.math.BigDecimal.valueOf(
                                                Portion.convert(canonical, base, u, grams, ml)
                                            )
                                            .setScale(6, java.math.RoundingMode.HALF_UP)
                                            .stripTrailingZeros()
                                            .toPlainString(),
                                        true,
                                    )
                                }
                                v.field("portion", "unit", u, true)
                            },
                            label = {
                                Text(
                                    if (u == "serving")
                                        "Serving" +
                                            (if (grams > 0) " (${fmt(grams)} g)"
                                            else if (ml > 0) " (${fmt(ml)} mL)" else "")
                                    else if (u == "g") "Grams" else "Milliliters"
                                )
                            },
                        )
                    }
                }
                if (unit == "serving" && (grams > 0 || ml > 0) && amount.toDoubleOrNull() != null)
                    Text(
                        "${fmt(amount.toDouble()*(if(grams>0)grams else ml))} ${if(grams>0)"g"else "mL"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            Section("Nutrition for this amount") {
                for (i in 1..4) {
                    val key = Nutrients.KEYS[i]
                    InfoRow(
                        Nutrients.NAMES[i],
                        if (f.has(key) && canonical != null) "${fmt(f.getDouble(key)*factor)} g"
                        else "—",
                    )
                }
                TextButton(onClick = { more = !more }) {
                    Text(if (more) "Less nutrition" else "Vitamins & minerals")
                }
                if (more)
                    for (i in 5 until Nutrients.KEYS.size) {
                        val key = Nutrients.KEYS[i]
                        InfoRow(
                            Nutrients.NAMES[i],
                            if (f.has(key) && canonical != null)
                                "${fmt(f.getDouble(key)*factor*Nutrients.displayFactor(i))} ${Nutrients.unit(i)}"
                            else "—",
                        )
                    }
            }
            TextButton(onClick = v::editFood) {
                Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Correct nutrition or serving size")
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { v.run { v.addFood(canonical!!) } },
                    Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    enabled = canonical != null && canonical > 0 && canonical <= 1_000_000,
                ) {
                    Text(if (v.mode == "dish") "Add to dish" else "Log food")
                }
                if (v.mode != "dish")
                    TextButton(
                        onClick = {
                            v.run {
                                v.repo.db.save("food", f)
                                v.refresh()
                                v.go("foods")
                            }
                        },
                        Modifier.fillMaxWidth(),
                    ) {
                        Text("Save food without logging")
                    }
            }
        }
    }
}
