package dev.rohit.labelaudit

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
fun NutrientProfileScreen(v: TrackerViewModel) {
    val profile = v.repo.settings.optJSONObject("nutrientProfile")
    val age = v.field("nutrient-profile", "age", profile?.getInt("age")?.toString() ?: "")
    val sex = v.field("nutrient-profile", "sex", profile?.getString("sex") ?: "")
    val stage = v.field("nutrient-profile", "stage", profile?.getString("stage") ?: "general")
    val uri = LocalUriHandler.current
    var about by rememberSaveable { mutableStateOf(false) }
    Page {
        Section("Reference profile", "US adult RDA/AI tables · ages 19+") {
            FormField(
                v,
                "nutrient-profile",
                "age",
                "Age (years)",
                profile?.getInt("age")?.toString() ?: "",
                true,
            )
            Text("Reference sex", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("male", "female").forEach { value ->
                    FilterChip(
                        sex == value,
                        onClick = {
                            v.field("nutrient-profile", "sex", value, true)
                            if (value == "male")
                                v.field("nutrient-profile", "stage", "general", true)
                        },
                        label = { Text(value.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Text(
                "Categories follow the published DRI tables.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sex == "female") {
                Text("Life stage", style = MaterialTheme.typography.labelLarge)
                Column {
                    listOf(
                            "general" to "General",
                            "pregnant" to "Pregnancy",
                            "lactating" to "Lactation",
                        )
                        .forEach { (value, label) ->
                            FilterChip(
                                stage == value,
                                { v.field("nutrient-profile", "stage", value, true) },
                                label = { Text(label) },
                            )
                        }
                }
            }
            Button(
                enabled = age.isNotBlank() && sex.isNotBlank(),
                onClick = { v.run { v.saveNutrientProfile(age.toInt(), sex, stage) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use reference targets")
            }
            if (profile != null)
                TextButton(onClick = { v.run { v.clearNutrientProfile() } }) {
                    Text("Remove reference profile")
                }
        }
        TextButton(onClick = { about = true }) { Text("About reference targets") }
    }
    if (about)
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Reference targets") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "RDA and AI are published age/sex references, not safety upper limits. Sodium uses the 2,300 mg CDRR threshold."
                    )
                    Text(
                        "Protein uses reference-body-weight values; total fat has no single RDA in grams. Custom targets take precedence."
                    )
                    Text(
                        "All dates are compared with your current reference profile. Vitamin A, folate and niacin references use RAE, DFE and NE units."
                    )
                    TextButton(
                        onClick = {
                            uri.openUri(
                                "https://ods.od.nih.gov/HealthInformation/nutrientrecommendations.aspx"
                            )
                        }
                    ) {
                        Text("NIH reference tables")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text("Done") } },
        )
}
