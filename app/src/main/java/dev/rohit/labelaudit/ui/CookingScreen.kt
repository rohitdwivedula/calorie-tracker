@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.rohit.labelaudit

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import java.time.*
import org.json.*

@Composable
fun CookScreen(v: TrackerViewModel, scan: () -> Unit) {
    val d = v.draft
    val ingredients = d.getJSONArray("ingredients")
    var showTime by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                d.optString("name"),
                { v.draftName(it) },
                label = { Text("Dish name") },
                placeholder = { Text("e.g. Sunday pasta") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (ingredients.length() == 0)
                EmptyState(
                    Icons.Default.Restaurant,
                    "Start with an ingredient",
                    "Scan a barcode or use a saved food.",
                )
            for (i in 0 until ingredients.length()) {
                val item = ingredients.getJSONObject(i)
                val f = item.getJSONObject("food")
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { v.chooseFood(f, "dish", i) }) {
                            Text(f.optString("name"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${fmt(item.getDouble("quantity"))} ${f.optString("unit")}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { v.removeIngredient(i) }) {
                            Icon(Icons.Default.Close, "Remove ${f.optString("name")}")
                        }
                    }
                }
            }
            FilledTonalButton(onClick = scan, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Default.QrCodeScanner, null)
                Spacer(Modifier.width(8.dp))
                Text("Add ingredient")
            }
            if (ingredients.length() > 0) {
                val n = v.repo.nutrition(d)
                InfoRow("Whole dish", "${fmt(n[0])} kcal")
            }
            TextButton(onClick = { showTime = true }) {
                Icon(Icons.Default.Schedule, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (d.has("available")) whenText(d.getLong("available"), v.repo.zone)
                    else "Start time · set automatically"
                )
            }
        }
        Surface(tonalElevation = 3.dp) {
            Button(
                onClick = { v.run { v.saveCook() } },
                Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 52.dp),
                enabled = ingredients.length() > 0 && d.optString("name").isNotBlank(),
            ) {
                Text("Done cooking")
            }
        }
    }
    if (showTime)
        TimestampDialog(
            d.optLong("available", System.currentTimeMillis()),
            v.repo.zone,
            { showTime = false },
        ) { time ->
            v.setDraft(v.draft.put("available", time))
            showTime = false
        }
}
