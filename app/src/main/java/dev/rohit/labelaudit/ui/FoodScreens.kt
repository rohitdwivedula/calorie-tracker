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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.*
import org.json.*

@Composable
fun FoodsScreen(v: TrackerViewModel, scan: () -> Unit, manual: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val foods = remember(v.revision) { v.repo.db.list("food") }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                placeholder = { Text("Search saved foods") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = scan, Modifier.weight(1f)) {
                    Icon(Icons.Default.QrCodeScanner, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan")
                }
                OutlinedButton(onClick = { v.manualFood() }, Modifier.weight(1f)) {
                    Text("New food")
                }
            }
        }
        item { TextButton(onClick = manual) { Text("Enter barcode manually") } }
        items(
            foods.filter { it.optString("name").contains(query, true) },
            key = { it.getString("id") },
        ) { f ->
            Card(
                onClick = { v.chooseFood(f, if (v.mode == "dish") "dish" else "quick") },
                Modifier.fillMaxWidth(),
            ) {
                ListItem(
                    headlineContent = { Text(f.optString("name")) },
                    supportingContent = {
                        Text(
                            "${fmt(f.getDouble("kcal"))} kcal / ${fmt(f.getDouble("basis"))} ${f.optString("unit")}"
                        )
                    },
                    trailingContent = {
                        IconButton(
                            onClick = {
                                v.mode = if (v.mode == "dish") "dish" else "library"
                                v.setFood(JSONObject(f.toString()))
                                v.editFood()
                            }
                        ) {
                            Icon(Icons.Default.Edit, "Edit food")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        if (foods.isEmpty())
            item {
                EmptyState(
                    Icons.Default.Kitchen,
                    "Your food library",
                    "Foods you scan and save will be available offline.",
                )
            }
        item {
            Text(
                "Open Food Facts · community data · ODbL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun FoodEditor(v: TrackerViewModel) {
    val f = v.food
    val scope = "food"
    val id = f.getString("id")
    var extras by rememberSaveable(id) { mutableStateOf(false) }
    fun initial(key: String, index: Int = -1): String =
        if (!f.has(key)) ""
        else if (index >= 0) fmt(f.getDouble(key) * Nutrients.displayFactor(index))
        else if (
            key in listOf("basis", "kcal", "protein", "carbs", "fat", "servingGrams", "servingMl")
        )
            fmt(f.getDouble(key))
        else f.optString(key)
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FormField(v, scope, "name", "Food name", initial("name"))
            Text(
                "Enter the values printed on the label",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    v.field(scope, "basis", initial("basis").ifBlank { "1" }),
                    { v.field(scope, "basis", it, true) },
                    Modifier.weight(1f),
                    label = { Text("Nutrition per") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                val unit = v.field(scope, "unit", f.optString("unit", "serving"))
                Column {
                    listOf("serving", "g", "ml").forEach { u ->
                        FilterChip(
                            selected = unit == u,
                            onClick = { v.field(scope, "unit", u, true) },
                            label = { Text(u) },
                        )
                    }
                }
            }
            listOf(
                    "kcal" to "Calories (kcal)",
                    "protein" to "Protein (g)",
                    "carbs" to "Carbs (g)",
                    "fat" to "Fat (g)",
                    "fiber" to "Fiber (g, optional)",
                )
                .forEach { (key, label) ->
                    FormField(
                        v,
                        scope,
                        key,
                        label,
                        initial(key, if (key == "fiber") 4 else -1),
                        true,
                    )
                }
            TextButton(onClick = { extras = !extras }) {
                Icon(Icons.Default.Tune, null)
                Spacer(Modifier.width(8.dp))
                Text(if (extras) "Hide extra nutrition" else "Serving size, vitamins & minerals")
            }
            if (extras) {
                FormField(
                    v,
                    scope,
                    "servingGrams",
                    "Grams in one serving (optional)",
                    initial("servingGrams"),
                    true,
                )
                FormField(
                    v,
                    scope,
                    "servingMl",
                    "mL in one serving (optional)",
                    initial("servingMl"),
                    true,
                )
                for (i in 5 until Nutrients.KEYS.size) FormField(
                    v,
                    scope,
                    Nutrients.KEYS[i],
                    "${Nutrients.NAMES[i]} (${Nutrients.unit(i)}, optional)",
                    initial(Nutrients.KEYS[i], i),
                    true,
                )
            }
        }
        Surface(tonalElevation = 3.dp) {
            Button(
                onClick = {
                    v.run {
                        val j = JSONObject(f.toString())
                        j.put("name", v.field(scope, "name", initial("name")).trim())
                            .put("unit", v.field(scope, "unit", f.optString("unit", "serving")))
                        for (key in listOf("basis", "kcal", "protein", "carbs", "fat")) {
                            val input =
                                v.field(
                                    scope,
                                    key,
                                    initial(key).ifBlank { if (key == "basis") "1" else "" },
                                )
                            if (!f.has(key) || input != initial(key)) j.put(key, input.toDouble())
                        }
                        for (i in 4 until Nutrients.KEYS.size) {
                            val key = Nutrients.KEYS[i]
                            val input = v.field(scope, key, initial(key, i))
                            if (input.isBlank()) j.remove(key)
                            else if (!f.has(key) || input != initial(key, i))
                                j.put(key, input.toDouble() / Nutrients.displayFactor(i))
                        }
                        for (key in listOf("servingGrams", "servingMl")) {
                            val input = v.field(scope, key, initial(key))
                            if (input.isBlank()) j.remove(key)
                            else if (!f.has(key) || input != initial(key))
                                j.put(key, input.toDouble())
                        }
                        if (
                            j.optString("unit") != f.optString("unit") ||
                                j.optDouble("servingGrams", 0.0) !=
                                    f.optDouble("servingGrams", 0.0) ||
                                j.optDouble("servingMl", 0.0) != f.optDouble("servingMl", 0.0)
                        )
                            j.remove("servingLabel")
                        v.saveFood(j)
                    }
                },
                Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 52.dp),
            ) {
                Text(if (v.mode == "library") "Save food" else "Continue")
            }
        }
    }
}
