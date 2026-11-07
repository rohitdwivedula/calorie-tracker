package dev.rohit.labelaudit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun LabelJsonDialog(dismiss: () -> Unit, fill: (JSONObject) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var copied by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Import label JSON") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (copied)
                        "Prompt copied. Send it with your label photo to another app, then paste its JSON here."
                    else "Paste the JSON returned with your label photo."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(LabelJson.prompt))
                            copied = true
                        }
                    ) {
                        Text("Copy prompt")
                    }
                    TextButton(
                        onClick = {
                            text = clipboard.getText()?.text ?: ""
                            copied = false
                            error = null
                        }
                    ) {
                        Text("Paste JSON")
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        copied = false
                        error = null
                    },
                    label = { Text("Label JSON") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 240.dp),
                    isError = error != null,
                    supportingText = error?.let { message -> ({ Text(message) }) },
                )
                Text(
                    "Replaces nutrition fields in this form. Review before saving; nothing is sent by this app.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    try {
                        fill(LabelJson.parse(text))
                    } catch (e: Exception) {
                        error = e.message ?: "Check the JSON format"
                    }
                },
            ) {
                Text("Fill form")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}
