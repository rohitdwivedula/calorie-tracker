package dev.rohit.labelaudit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
fun FoodSourceButtons(v: TrackerViewModel) {
    FoodSource.entries.forEach { source ->
        val choice = v.foodChoice(source)
        val selected = v.selectedFoodSource == source
        val available = choice.phase == LookupPhase.READY
        val color =
            if (available) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
        IconButton(
            onClick = { v.selectFoodSource(source) },
            modifier =
                Modifier.semantics {
                    contentDescription = "${source.label} source"
                    stateDescription =
                        if (selected) "Selected"
                        else
                            when (choice.phase) {
                                LookupPhase.READY -> "Available"
                                LookupPhase.LOADING -> "Loading"
                                LookupPhase.NEEDS_KEY -> "Credentials required"
                                LookupPhase.NOT_FOUND -> "Not found"
                                LookupPhase.ERROR -> "Unavailable"
                                LookupPhase.IDLE -> "Not loaded"
                            }
                },
        ) {
            Box(
                Modifier.size(36.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                        CircleShape,
                    ),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                if (choice.phase == LookupPhase.LOADING)
                    CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 1.5.dp)
                if (source == FoodSource.SAVED) Icon(Icons.Default.Bookmark, null, tint = color)
                else
                    Text(
                        if (source == FoodSource.OFF) "OFF" else "FS",
                        color = color,
                        style = MaterialTheme.typography.labelMedium,
                    )
            }
        }
    }
}

@Composable
fun FoodLookupPlaceholder(v: TrackerViewModel) {
    Page {
        if (v.food.optString("name").isNotBlank()) {
            Section(v.food.optString("name")) {
                Text("Some nutrition is missing")
                TextButton(onClick = v::editFood) { Text("Complete nutrition") }
            }
        } else
            Section(if (v.lookingUpFood) "Finding your food…" else "No nutrition found") {
                if (v.lookingUpFood) LinearProgressIndicator(Modifier.fillMaxWidth())
                FoodSource.entries
                    .filter { it != FoodSource.SAVED }
                    .forEach { source ->
                        val choice = v.foodChoice(source)
                        Text(
                            "${source.label} · " +
                                when (choice.phase) {
                                    LookupPhase.LOADING -> "Looking up"
                                    LookupPhase.NEEDS_KEY -> "Not connected"
                                    LookupPhase.ERROR -> choice.note
                                    LookupPhase.NOT_FOUND -> "Product not found"
                                    LookupPhase.READY -> "Available"
                                    LookupPhase.IDLE -> "Not loaded"
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                TextButton(onClick = { v.fallbackFood() }) { Text("Enter nutrition manually") }
                TextButton(onClick = { v.lookup(v.lookupBarcode) }) { Text("Try again") }
            }
    }
}
