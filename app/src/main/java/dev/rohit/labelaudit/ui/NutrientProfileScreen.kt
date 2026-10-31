package dev.rohit.labelaudit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.YearMonth

@Composable
fun NutrientProfileScreen(v: TrackerViewModel) {
    val profile = v.repo.settings.optJSONObject("nutrientProfile")
    val initialBirth =
        profile
            ?.takeIf { it.has("birthMonth") && it.has("birthYear") }
            ?.let { "%02d/%04d".format(it.getInt("birthMonth"), it.getInt("birthYear")) } ?: ""
    val birthText = v.field("nutrient-profile", "birth", initialBirth)
    val currentAge =
        runCatching {
                val birth = NutrientReferences.parseBirthMonth(birthText)
                YearMonth.now(v.repo.zone).let {
                    it.year - birth.year - if (it.monthValue < birth.monthValue) 1 else 0
                }
            }
            .getOrNull()
    val sex = v.field("nutrient-profile", "sex", profile?.getString("sex") ?: "")
    val stage = v.field("nutrient-profile", "stage", profile?.getString("stage") ?: "general")
    val uri = LocalUriHandler.current
    var about by rememberSaveable { mutableStateOf(false) }
    Page {
        Section("Reference profile", "US adult RDA/AI tables · ages 19+") {
            OutlinedTextField(
                value = birthText,
                onValueChange = {
                    val digits = it.filter(Char::isDigit).take(6)
                    val formatted =
                        if (digits.length > 2) digits.take(2) + "/" + digits.drop(2) else digits
                    v.field("nutrient-profile", "birth", formatted, true)
                },
                label = { Text("Birth month/year (MM/YYYY)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Age updates at the start of your birth month. No birth day is stored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (currentAge != null && currentAge >= 0)
                Text(
                    "Current reference age: $currentAge",
                    style = MaterialTheme.typography.labelLarge,
                )
            if (profile?.has("age") == true)
                Text(
                    "Previously entered age: ${profile.getInt("age")}. Add birth month/year for automatic updates.",
                    style = MaterialTheme.typography.bodySmall,
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
                enabled = currentAge != null && sex.isNotBlank(),
                onClick = { v.run { v.saveNutrientProfile(birthText, sex, stage) } },
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
