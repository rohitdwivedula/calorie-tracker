package dev.rohit.labelaudit

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.*

@Composable
internal fun WeightImportScreen(v: TrackerViewModel) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<WeightCandidate>?>(null) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    fun load(read: () -> String) {
        scope.launch {
            busy = true
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        v.repo.db.weightCandidates(WeightImport.parse(read(), v.repo.zone))
                    }
                chosen =
                    result
                        .filter { !it.duplicate && it.sameDay.isEmpty() }
                        .mapTo(mutableSetOf()) { it.row.id }
                preview = result
                error = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "Could not read weight JSON"
            } finally {
                busy = false
            }
        }
    }
    val open =
        rememberLauncherForActivityResult(remember { ActivityResultContracts.OpenDocument() }) { uri
            ->
            if (uri != null)
                load {
                    val stream =
                        requireNotNull(context.contentResolver.openInputStream(uri)) {
                            "Cannot open this file"
                        }
                    stream.bufferedReader().use { reader ->
                        val buffer = CharArray(8192)
                        buildString {
                            while (true) {
                                val n = reader.read(buffer)
                                if (n < 0) break
                                require(length + n <= 1024 * 1024) { "Weight JSON exceeds 1 MB" }
                                append(buffer, 0, n)
                            }
                        }
                    }
                }
        }
    Column(
        Modifier.fillMaxSize().padding(20.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val rows = preview
            if (rows == null) {
                Button(
                    enabled = !busy,
                    onClick = {
                        open.launch(
                            arrayOf("application/json", "text/*", "application/octet-stream")
                        )
                    },
                ) {
                    Text("Choose JSON file")
                }
                Row {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            clipboard.setText(AnnotatedString(WeightImport.prompt))
                            Toast.makeText(context, "Prompt copied", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("Copy prompt")
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            text = clipboard.getText()?.text.orEmpty()
                            error = null
                        },
                    ) {
                        Text("Paste JSON")
                    }
                }
                OutlinedTextField(
                    text,
                    {
                        text = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text("Weight JSON") },
                    maxLines = 5,
                )
            } else {
                Text("Review weigh-ins", style = MaterialTheme.typography.titleMedium)
                Text("${chosen.size} of ${rows.size} selected")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(rows, key = { it.row.id }) { item ->
                        val row = item.row
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = row.id in chosen,
                                enabled = !item.duplicate && !busy,
                                onCheckedChange = {
                                    chosen = if (it) chosen + row.id else chosen - row.id
                                },
                            )
                            Column {
                                Text("${row.date} ${row.time ?: ""} · ${row.weight} ${row.unit}")
                                if (item.duplicate)
                                    Text(
                                        "Already recorded",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                else
                                    for (hc in item.sameDay) {
                                        val time =
                                            Instant.ofEpochMilli(hc.time)
                                                .atZone(row.zone)
                                                .toLocalTime()
                                                .withSecond(0)
                                                .withNano(0)
                                        val weight =
                                            if (row.unit == "lb") hc.kg / 0.45359237 else hc.kg
                                        Text(
                                            "Health Connect · $time · ${fmt(weight)} ${row.unit}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        val rows = preview
        if (rows == null)
            TextButton(
                enabled = text.isNotBlank() && !busy,
                onClick = {
                    val input = text
                    load { input }
                },
            ) {
                Text("Preview")
            }
        else
            TextButton(
                enabled = chosen.isNotEmpty() && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val selected = rows.filter { it.row.id in chosen }
                            val approved =
                                selected
                                    .filter { it.sameDay.isNotEmpty() }
                                    .mapTo(mutableSetOf()) { it.row.id }
                            val count =
                                withContext(Dispatchers.IO) {
                                    v.repo.db.importWeights(selected.map { it.row }, approved)
                                }
                            v.refresh()
                            Toast.makeText(context, "Imported $count weigh-ins", Toast.LENGTH_SHORT)
                                .show()
                            v.back()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            error = e.message ?: "Import failed"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                Text("Import")
            }
    }
}
