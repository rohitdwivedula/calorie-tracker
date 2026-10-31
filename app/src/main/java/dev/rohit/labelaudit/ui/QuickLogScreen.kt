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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.*
import org.json.*

@Composable
fun CaloriesScreen(v: TrackerViewModel) {
    val existing =
        remember(v.selectedId) {
            if (v.selectedId.isEmpty()) null else v.record("calorie", v.selectedId)
        }
    var showTime by remember { mutableStateOf(false) }
    val recordedTime =
        v.field("calories", "time", existing?.optLong("time")?.toString() ?: "").toLongOrNull()
    Page {
        Section("Just the calories", "Macros will remain unknown") {
            FormField(
                v,
                "calories",
                "kcal",
                "Calories (kcal)",
                existing?.optDouble("kcal")?.let(::fmt) ?: "",
                true,
            )
            FormField(v, "calories", "name", "Name (optional)", existing?.optString("name") ?: "")
            TextButton(
                onClick = { showTime = true },
                modifier = Modifier.semantics { contentDescription = "Edit eating time" },
            ) {
                Icon(Icons.Default.Schedule, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (recordedTime == null) "When · now"
                    else "When · " + whenText(recordedTime, v.repo.zone)
                )
            }
            Button(
                onClick = {
                    v.run {
                        v.saveCalories(
                            v.field("calories", "name", existing?.optString("name") ?: "").trim(),
                            v.field(
                                    "calories",
                                    "kcal",
                                    existing?.optDouble("kcal")?.let(::fmt) ?: "",
                                )
                                .toDouble(),
                            recordedTime,
                        )
                    }
                },
                Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
        if (existing != null)
            TextButton(onClick = { v.deleteRecord = "calorie" to existing.getString("id") }) {
                Icon(Icons.Default.Delete, null)
                Text("Delete log")
            }
    }
    if (showTime)
        TimestampDialog(
            recordedTime ?: System.currentTimeMillis(),
            v.repo.zone,
            { showTime = false },
        ) { time ->
            v.field("calories", "time", time.toString(), true)
            showTime = false
        }
}
