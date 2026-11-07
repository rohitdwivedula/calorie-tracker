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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.*
import org.json.*

private fun searchServing(food: JSONObject): String {
    val basis = food.optDouble("basis", 1.0)
    val unit = food.optString("unit")
    if (unit != "serving") return "${fmt(basis)} $unit"
    val label = food.optString("servingLabel").ifBlank { "1 serving" }
    val prefix = if (basis == 1.0) label else "${fmt(basis)} × $label"
    if (Portion.labelAmount(label, "g") > 0 || Portion.labelAmount(label, "ml") > 0) return prefix
    return prefix +
        when {
            food.optDouble("servingGrams", 0.0) > 0 ->
                " · ${fmt(basis * food.getDouble("servingGrams"))} g"
            food.optDouble("servingMl", 0.0) > 0 ->
                " · ${fmt(basis * food.getDouble("servingMl"))} mL"
            else -> ""
        }
}

@Composable
fun FoodsScreen(v: TrackerViewModel, scan: () -> Unit, manual: () -> Unit) {
    val search = v.nameSearch
    val foods = remember(v.revision) { v.repo.db.list("food") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val savedHits =
        foods
            .filter { FoodSearchParsing.savedMatches(it, search.query) }
            .map { FoodSearchHit(FoodSource.SAVED, "saved-${it.getString("id")}", it.toString()) }
    val online = listOf(FoodSource.FATSECRET, FoodSource.OFF).flatMap { search.state(it).hits }
    val hits = (savedHits + online).filter { filter == "All" || it.source.name == filter }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun submit() {
        keyboard?.hide()
        search.search()
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                search.query,
                search::updateQuery,
                Modifier.fillMaxWidth(),
                label = { Text("Search foods") },
                placeholder = { Text("e.g. red bell pepper") },
                trailingIcon = {
                    IconButton(onClick = ::submit, enabled = search.query.trim().length >= 2) {
                        Icon(Icons.Default.Search, "Search online")
                    }
                },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions =
                    androidx.compose.foundation.text.KeyboardActions(onSearch = { submit() }),
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All" to "All", "SAVED" to "Saved", "OFF" to "OFF", "FATSECRET" to "FS")
                    .forEach { (key, label) ->
                        FilterChip(
                            selected = filter == key,
                            onClick = { filter = key },
                            label = { Text(label) },
                        )
                    }
            }
        }
        if (search.query.isBlank()) {
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
        }
        if (search.query.isNotBlank()) {
            for (source in listOf(FoodSource.FATSECRET, FoodSource.OFF)) {
                if (filter != "All" && filter != source.name) continue
                val state = search.state(source)
                if (
                    state.phase in listOf(LookupPhase.IDLE, LookupPhase.NOT_FOUND) ||
                        state.phase == LookupPhase.READY && state.hits.isNotEmpty()
                )
                    continue
                item(key = "status-${source.name}") {
                    when (state.phase) {
                        LookupPhase.NEEDS_KEY ->
                            TextButton(onClick = v::openFatSecretSettings) {
                                Text("Connect FatSecret")
                            }
                        LookupPhase.LOADING ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Searching ${source.label}…",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        LookupPhase.ERROR ->
                            Column {
                                Text(state.note, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { search.retry(source) }) {
                                    Text("Retry ${source.label}")
                                }
                            }
                        LookupPhase.READY ->
                            if (state.hits.isEmpty())
                                Text(
                                    "No ${source.label} matches",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                        else -> {}
                    }
                }
            }
        }
        items(hits, key = { it.key }) { hit ->
            val f = JSONObject(hit.json)
            Card(
                onClick = {
                    keyboard?.hide()
                    v.chooseSearchFood(hit)
                },
                Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.optString("name"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                hit.source.label +
                                    if (f.optString("providerType") == "Generic")
                                        " · Generic ingredient"
                                    else if (f.optString("brand").isNotBlank())
                                        " · " + f.optString("brand")
                                    else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (hit.source == FoodSource.SAVED)
                            IconButton(
                                onClick = {
                                    v.mode = if (v.mode == "dish") "dish" else "library"
                                    v.setFood(JSONObject(f.toString()))
                                    v.editFood()
                                }
                            ) {
                                Icon(Icons.Default.Edit, "Edit ${f.optString("name")}")
                            }
                    }
                    Text(
                        searchServing(f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(if (f.has("kcal")) "${kcal(f.getDouble("kcal"))} kcal" else "— kcal")
                        Text(
                            "Protein " +
                                if (f.has("protein")) "${fmt(f.getDouble("protein"))} g" else "—"
                        )
                        Text(
                            "Fiber " + if (f.has("fiber")) "${fmt(f.getDouble("fiber"))} g" else "—"
                        )
                    }
                }
            }
        }
        if (search.query.isNotBlank() && search.submitted.isBlank())
            item {
                Text(
                    "Tap Search to find ingredients and packaged foods online",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        if (search.submitted.isNotBlank() && !search.loading && hits.isEmpty())
            item {
                EmptyState(
                    Icons.Default.Search,
                    "No matching foods",
                    "Try another name or enter nutrition manually.",
                )
                TextButton(onClick = v::manualFood) { Text("Create food manually") }
            }
        val remoteFilter =
            FoodSource.entries.firstOrNull { it.name == filter && it != FoodSource.SAVED }
        val hasMore =
            listOf(FoodSource.OFF, FoodSource.FATSECRET).any {
                (filter == "All" || it == remoteFilter) && search.state(it).hasMore
            }
        if (hasMore)
            item {
                TextButton(
                    onClick = { search.more(remoteFilter) },
                    enabled = !search.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("More results")
                }
            }
        if (foods.isEmpty() && search.query.isBlank())
            item {
                EmptyState(
                    Icons.Default.Search,
                    "Find an ingredient",
                    "Search by name or scan a barcode. Saved foods work offline.",
                )
            }
        item {
            Text(
                "Open Food Facts · ODbL · fatsecret",
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
