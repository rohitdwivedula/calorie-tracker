package dev.rohit.labelaudit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

@Composable
fun FatSecretSettingsScreen(v: TrackerViewModel) {
    // Intentionally not Saveable/SavedStateHandle: secrets never enter form/navigation snapshots.
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Page {
        Section("FatSecret") {
            Text(
                if (v.fatSecretConfigured) "Connected on this device"
                else "Add your API credentials"
            )
            OutlinedTextField(
                clientId,
                { clientId = it },
                Modifier.fillMaxWidth(),
                label = { Text("Client ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            OutlinedTextField(
                secret,
                { secret = it },
                Modifier.fillMaxWidth(),
                label = { Text("Client secret") },
                singleLine = true,
                visualTransformation =
                    if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            if (visible) "Hide secret" else "Show secret",
                        )
                    }
                },
            )
            Text(
                "Encrypted on this device. Never included in exports or backups.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { v.run { v.saveFatSecretCredentials(clientId.trim(), secret.trim()) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = clientId.isNotBlank() && secret.isNotBlank(),
            ) {
                Text("Save credentials")
            }
            if (v.fatSecretConfigured)
                TextButton(
                    onClick = {
                        v.run {
                            v.clearFatSecretCredentials()
                            clientId = ""
                            secret = ""
                        }
                    }
                ) {
                    Text("Remove credentials")
                }
        }
    }
}
