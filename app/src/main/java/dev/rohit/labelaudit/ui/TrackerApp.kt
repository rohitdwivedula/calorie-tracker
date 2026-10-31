@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package dev.rohit.labelaudit

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.*
import org.json.*

private val MainRoutes = listOf("Today", "history", "stats", "foods")

@Composable
fun TrackerApp(v: TrackerViewModel = viewModel()) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        v.refresh()
        v.refreshSteps()
    }
    val context = LocalContext.current
    val healthPermission =
        rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) {
            v.refreshSteps()
        }
    fun connectSteps() {
        v.run {
            when (HealthConnectClient.getSdkStatus(context)) {
                HealthConnectClient.SDK_AVAILABLE ->
                    healthPermission.launch(setOf(AndroidStepSource.READ_STEPS))
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"
                            ),
                        )
                    )
                else ->
                    v.message =
                        "Health Connect is unavailable on this device. Food logging still works."
            }
        }
    }
    val resolver = context.contentResolver
    var manualBarcode by rememberSaveable { mutableStateOf(false) }
    var barcode by rememberSaveable { mutableStateOf("") }
    val scanner =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK)
                when (result.data?.getStringExtra("action")) {
                    "calories" -> v.beginCalories()
                    "manual_barcode" -> manualBarcode = true
                    "manual_food" -> v.manualFood()
                    "saved_foods" -> v.go("foods")
                    else -> result.data?.getStringExtra("raw_code")?.let(v::lookup)
                }
        }
    fun scan(ingredient: Boolean) {
        v.mode = if (ingredient) "dish" else "quick"
        if (ingredient) v.ingredientIndex = -1
        scanner.launch(
            Intent(context, CaptureActivity::class.java)
                .putExtra("ingredient", ingredient)
                .putExtra("quick_log", !ingredient)
        )
    }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            uri?.let { v.export(resolver, it) }
        }
    val restore =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { v.readRestore(resolver, it) }
        }
    BackHandler(v.canBack) { v.back() }
    val holder = rememberSaveableStateHolder()
    val title =
        when (v.route) {
            "Today" -> "Today"
            "history" -> "Your food log"
            "stats" -> "Nutrition trends"
            "foods" -> if (v.mode == "dish") "Choose an ingredient" else "Saved foods"
            "cook" -> "Cooking"
            "portion" -> "Set portion size"
            "food-edit" -> "Food nutrition"
            "settings" -> "Settings"
            "summary" -> "Energy balance"
            "energy" -> "Expenditure"
            "activity" -> "Activity calories"
            "details" -> "Dish details"
            "calories" -> "Quick log"
            else -> "Nutrition"
        }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(v.message) {
        v.message?.let {
            snack.showSnackbar(it)
            v.message = null
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    if (v.canBack)
                        IconButton(onClick = v::back) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    if (v.route == "Today")
                        IconButton(onClick = { v.go("summary") }) {
                            Icon(Icons.Default.Insights, "Energy balance")
                        }
                    if (v.route in MainRoutes)
                        IconButton(onClick = v::openSettings) {
                            Icon(Icons.Default.Settings, "Settings")
                        }
                },
            )
        },
        bottomBar = {
            if (v.route in MainRoutes) {
                NavigationBar {
                    listOf(
                            Icons.Default.Home,
                            Icons.Default.History,
                            Icons.Default.BarChart,
                            Icons.Default.Kitchen,
                        )
                        .forEachIndexed { i, icon ->
                            NavigationBarItem(
                                selected = v.route == MainRoutes[i],
                                onClick = {
                                    if (i == 3) v.mode = "library"
                                    v.go(MainRoutes[i])
                                },
                                icon = { Icon(icon, null) },
                                label = { Text(listOf("Today", "History", "Stats", "Foods")[i]) },
                            )
                        }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            holder.SaveableStateProvider(v.route) {
                when (v.route) {
                    "Today" -> TodayScreen(v, { scan(false) }, ::connectSteps)
                    "cook" -> CookScreen(v, { scan(true) })
                    "foods" ->
                        FoodsScreen(
                            v,
                            {
                                if (v.mode != "dish") v.mode = "quick"
                                scanner.launch(
                                    Intent(context, CaptureActivity::class.java)
                                        .putExtra("ingredient", v.mode == "dish")
                                        .putExtra("quick_log", v.mode != "dish")
                                )
                            },
                            { manualBarcode = true },
                        )
                    "portion" -> PortionScreen(v)
                    "food-edit" -> FoodEditor(v)
                    "history" -> HistoryScreen(v)
                    "stats" -> StatsScreen(v)
                    "settings" ->
                        SettingsScreen(
                            v,
                            { export.launch("batch-nutrition-backup.json") },
                            {
                                restore.launch(
                                    arrayOf("application/json", "application/octet-stream")
                                )
                            },
                        )
                    "summary" -> SummaryScreen(v)
                    "energy" -> ExpenditureScreen(v, ::connectSteps)
                    "activity" -> ActivityCaloriesScreen(v)
                    "details" -> DetailsScreen(v)
                    "calories" -> CaloriesScreen(v)
                }
            }
            if (v.busy)
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = .85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("One moment…")
                    }
                }
        }
    }
    if (manualBarcode)
        AlertDialog(
            onDismissRequest = { manualBarcode = false },
            title = { Text("Enter barcode") },
            text = {
                OutlinedTextField(
                    barcode,
                    { barcode = it },
                    label = { Text("Barcode or product QR") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        manualBarcode = false
                        v.lookup(barcode)
                    }
                ) {
                    Text("Look up")
                }
            },
            dismissButton = { TextButton(onClick = { manualBarcode = false }) { Text("Cancel") } },
        )
    v.error?.let { error ->
        AlertDialog(
            onDismissRequest = { v.error = null },
            title = { Text("Couldn't complete that") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { v.error = null }) { Text("OK") } },
            dismissButton = {
                if (v.lookupFallback != null)
                    TextButton(onClick = v::fallbackFood) { Text("Enter food manually") }
            },
        )
    }
    if (v.pendingRestore != null)
        AlertDialog(
            onDismissRequest = { v.pendingRestore = null },
            title = { Text("Replace local data?") },
            text = {
                Text(
                    "Restore replaces your foods, dishes, logs and settings with this backup. Export first if you want to keep the current data."
                )
            },
            confirmButton = { TextButton(onClick = v::restore) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { v.pendingRestore = null }) { Text("Cancel") } },
        )
    v.deleteRecord?.let {
        AlertDialog(
            onDismissRequest = { v.deleteRecord = null },
            title = { Text("Delete this record?") },
            text = {
                Text(
                    if (v.deleteRecord?.first == "activity")
                        "Its extra expenditure will be removed."
                    else "Its recorded intake will also be removed."
                )
            },
            confirmButton = { TextButton(onClick = { v.run { v.delete() } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { v.deleteRecord = null }) { Text("Cancel") } },
        )
    }
    v.portionBatch?.let { id -> PortionDialog(v, id) }
    v.finishBatch?.let { id ->
        val b = remember(id, v.revision) { v.record("batch", id) }
        val left = BatchOperations.remaining(b)
        AlertDialog(
            onDismissRequest = { v.finishBatch = null },
            title = { Text("Log remaining share now?") },
            text = {
                Text(
                    "${fmt(left*100)}% of ${b.optString("name")} · ${fmt(v.repo.nutrition(b)[0]*left)} kcal"
                )
            },
            confirmButton = {
                TextButton(onClick = { v.run { v.finish(id) } }) { Text("Log & finish") }
            },
            dismissButton = { TextButton(onClick = { v.finishBatch = null }) { Text("Not now") } },
        )
    }
    v.eventEdit?.let { EventDialog(v, it.first, it.second) }
}
