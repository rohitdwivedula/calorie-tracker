package dev.rohit.labelaudit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun NutrientTargetsScreen(v: TrackerViewModel) {
    var goalIndex by rememberSaveable { mutableIntStateOf(-1) }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items((1 until Nutrients.KEYS.size).toList()) { i ->
            val goal = remember(v.revision, i) { v.repo.goal(i) }
            TextButton(onClick = { goalIndex = i }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(Nutrients.NAMES[i]) },
                    supportingContent = { Text(if (goal > 0)
                        "${fmt(goal * Nutrients.displayFactor(i))} ${v.repo.goalUnit(i)} · ${v.repo.goalBasis(i)}"
                        else "No daily target") },
                )
            }
        }
    }
    if (goalIndex > 0) {
        val i = goalIndex
        var text by
            rememberSaveable(i) {
                mutableStateOf(
                    if (v.repo.goal(i) > 0) fmt(v.repo.goal(i) * Nutrients.displayFactor(i)) else ""
                )
            }
        AlertDialog(
            onDismissRequest = { goalIndex = -1 },
            title = { Text("${Nutrients.NAMES[i]} target") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        text,
                        { text = it },
                        label = { Text("Daily ${v.repo.goalUnit(i)}") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    TextButton(
                        onClick = {
                            v.run {
                                v.saveGoal(i, null)
                                goalIndex = -1
                            }
                        }
                    ) {
                        Text(
                            if (v.repo.settings.has("nutrientProfile")) "Use profile reference"
                            else "Clear custom target"
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = text.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true,
                    onClick = {
                        v.run {
                            v.saveGoal(i, text.toDouble() / Nutrients.displayFactor(i))
                            goalIndex = -1
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { goalIndex = -1 }) { Text("Cancel") } },
        )
    }
}
