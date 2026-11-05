package dev.rohit.labelaudit

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.time.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class TrackerViewModel(app: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(app) {
    val repo = TrackerRepository(app)
    internal var credentialStore = FatSecretCredentials(app)
    internal var foodProviders: FoodProviders = AndroidFoodProviders(repo.db)
    var fatSecretConfigured by mutableStateOf(credentialStore.configured)
        private set

    private var lookupGeneration = 0
    private val foodJobs = mutableListOf<Job>()
    private var sourcePinned = false
    private var requestedFoodSource: FoodSource? = null
    private var lookupFoodId = ""
    private val sourceRevisions = mutableMapOf<FoodSource, Int>()
    private val stepsSync = StepsSync(repo, AndroidStepSource(app))
    private var stepsJob: Job? = null
    private val pendingStepDates = linkedSetOf<LocalDate>()
    var syncingSteps by mutableStateOf(false)
        private set

    var stepAccess by mutableStateOf("unknown")
        private set

    fun refreshSteps(date: LocalDate? = null) {
        val today = LocalDate.now(repo.zone)
        val start =
            maxOf(
                LocalDate.parse(repo.settings.getString("start")),
                LocalDate.parse(repo.settings.getString("energyStart")),
            )
        if (date != null && date >= start && date <= today) pendingStepDates.add(date)
        if (date == null)
            (0L..30L)
                .map { today.minusDays(it) }
                .filter { it >= start }
                .forEach { pendingStepDates.add(it) }
        if (stepsJob?.isActive == true || pendingStepDates.isEmpty()) return
        syncingSteps = true
        stepsJob =
            viewModelScope.launch {
                try {
                    while (pendingStepDates.isNotEmpty()) {
                        val dates = pendingStepDates.toList()
                        pendingStepDates.clear()
                        stepAccess = withContext(Dispatchers.IO) { stepsSync.refresh(dates) }
                        refresh()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stepAccess = "error"
                    message = "Couldn't refresh steps; saved values kept"
                } finally {
                    syncingSteps = false
                }
            }
    }

    fun openEnergy(date: LocalDate = LocalDate.now(repo.zone)) {
        clearForm("energy")
        field("energy", "date", date.toString(), true)
        go("energy")
        refreshSteps(date)
    }

    fun beginActivity(date: LocalDate, existing: JSONObject? = null) {
        clearForm("activity")
        field("activity", "id", existing?.getString("id") ?: UUID.randomUUID().toString(), true)
        field("activity", "date", existing?.getString("date") ?: date.toString(), true)
        field("activity", "kcal", existing?.getDouble("kcal")?.let(::fmt) ?: "", true)
        field("activity", "description", existing?.optString("description") ?: "", true)
        go("activity")
    }

    fun saveActivity() {
        val date = LocalDate.parse(field("activity", "date"))
        require(date <= LocalDate.now(repo.zone)) { "Activity date cannot be in the future" }
        require(date >= LocalDate.parse(repo.settings.getString("energyStart"))) {
            "Activity must be on or after the new model's start date"
        }
        repo.db.save(
            "activity",
            JSONObject()
                .put("id", field("activity", "id"))
                .put("date", date.toString())
                .put("kcal", field("activity", "kcal").toDouble())
                .put("description", field("activity", "description")),
        )
        back()
        refresh()
    }

    private val history =
        Navigation<String>().apply {
            restore(saved.get<ArrayList<String>>("backstack") ?: arrayListOf()) { it }
        }
    var route by mutableStateOf(saved.get<String>("route") ?: "Today")
        private set

    var revision by mutableIntStateOf(0)
        private set

    var formJson by mutableStateOf(saved.get<String>("forms") ?: "{}")
        private set

    var draftJson by mutableStateOf(saved.get<String>("draft") ?: "{}")
        private set

    var foodJson by mutableStateOf(saved.get<String>("food") ?: "{}")
        private set

    var lookupBarcode by
        mutableStateOf(
            saved.get<String>("lookupBarcode") ?: JSONObject(foodJson).optString("barcode")
        )
        private set

    var selectedFoodSource by
        mutableStateOf(
            saved.get<String>("foodSource")?.let { FoodSource.valueOf(it) }
                ?: if (JSONObject(foodJson).optString("name").isNotBlank()) FoodSource.SAVED
                else null
        )
        private set

    var foodChoices by
        mutableStateOf(
            selectedFoodSource?.let { mapOf(it to FoodChoice(LookupPhase.READY, foodJson)) }
                ?: emptyMap()
        )
        private set

    var foodSourceNotice by mutableStateOf<String?>(null)
    var promptFatSecretSetup by mutableStateOf(false)
    val lookingUpFood
        get() = foodChoices.values.any { it.phase == LookupPhase.LOADING }

    val completeFood
        get() = foodComplete(foodJson)

    private fun foodComplete(json: String): Boolean =
        try {
            Data.validate("food", JSONObject(json))
            true
        } catch (_: Exception) {
            false
        }

    var mode: String
        get() = saved["mode"] ?: "quick"
        set(v) {
            saved["mode"] = v
        }

    var ingredientIndex: Int
        get() = saved["ingredientIndex"] ?: -1
        set(v) {
            saved["ingredientIndex"] = v
        }

    var selectedId: String
        get() = saved["selectedId"] ?: ""
        set(v) {
            saved["selectedId"] = v
        }

    var selectedKind: String
        get() = saved["selectedKind"] ?: "batch"
        set(v) {
            saved["selectedKind"] = v
        }

    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var pendingRestore by mutableStateOf<String?>(null)
    var portionBatch by mutableStateOf<String?>(null)
    var finishBatch by mutableStateOf<String?>(null)
    var deleteRecord by mutableStateOf<Pair<String, String>?>(null)
    var eventEdit by mutableStateOf<Pair<String, Int>?>(null)
    var lookupFallback by mutableStateOf<String?>(null)
    val canBack
        get() = history.canGoBack()

    val draft
        get() = JSONObject(draftJson)

    val food
        get() = JSONObject(foodJson)

    fun run(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            error = e.message ?: "Could not complete that action"
        }
    }

    fun go(next: String) {
        history.show(route, next, route)
        route = next
        saveNavigation()
    }

    fun back() {
        history.back()?.let {
            route = it.route
            if (route == "Today") refresh()
            if (route == "portion") selectDefaultFood()
            saveNavigation()
        }
    }

    fun home() {
        go("Today")
        refresh()
    }

    private fun saveNavigation() {
        saved["route"] = route
        saved["backstack"] = history.routes()
    }

    fun refresh() {
        repo.reload()
        revision++
    }

    fun field(scope: String, key: String, default: String = ""): String =
        JSONObject(formJson).optJSONObject(scope)?.optString(key, default) ?: default

    fun field(scope: String, key: String, value: String, update: Boolean) {
        val all = JSONObject(formJson)
        val section = all.optJSONObject(scope) ?: JSONObject()
        section.put(key, value)
        all.put(scope, section)
        formJson = all.toString()
        saved["forms"] = formJson
    }

    fun clearForm(scope: String) {
        val all = JSONObject(formJson)
        all.remove(scope)
        formJson = all.toString()
        saved["forms"] = formJson
    }

    fun setDraft(value: JSONObject) {
        if (value.has("id")) {
            val row = JSONObject().put("id", "draft-" + value.getString("id"))
            if (
                value.optString("name").isNotBlank() ||
                    value.getJSONArray("ingredients").length() > 0
            ) {
                repo.db.save(
                    "draft",
                    row.put("batch", value).put("updatedAt", System.currentTimeMillis()),
                )
            } else repo.db.delete(row)
        }
        draftJson = value.toString()
        saved["draft"] = draftJson
    }

    fun draftName(value: String) {
        setDraft(draft.put("name", value))
    }

    fun setFood(value: JSONObject) {
        if (!value.has("id")) value.put("id", UUID.randomUUID().toString())
        foodJson = value.toString()
        saved["food"] = foodJson
    }

    fun record(kind: String, id: String): JSONObject =
        repo.db.list(kind).firstOrNull { it.optString("id") == id }
            ?: error("Record no longer exists")

    fun beginCook(source: JSONObject? = null, duplicate: Boolean = false) {
        if (source != null && !duplicate) {
            repo.db
                .list("draft")
                .firstOrNull { it.getJSONObject("batch").getString("id") == source.getString("id") }
                ?.let {
                    resumeDraft(it.getString("id"))
                    return
                }
        }
        setDraft(
            when {
                source == null ->
                    JSONObject()
                        .put("id", UUID.randomUUID().toString())
                        .put("ingredients", JSONArray())
                        .put("share", 1)
                        .put("completed", JSONObject.NULL)
                        .put("portionTracking", true)
                duplicate -> BatchOperations.duplicate(source, System.currentTimeMillis())
                else -> JSONObject(source.toString())
            }
        )
        go("cook")
    }

    fun resumeDraft(id: String) {
        setDraft(record("draft", id).getJSONObject("batch"))
        go("cook")
    }

    fun discardDraft(id: String) {
        repo.db.delete(record("draft", id))
        if (draft.optString("id").let { "draft-$it" } == id) {
            draftJson = "{}"
            saved["draft"] = draftJson
        }
        refresh()
    }

    fun removeIngredient(index: Int) {
        val d = draft
        d.getJSONArray("ingredients").remove(index)
        setDraft(d)
    }

    fun saveCook() {
        val d = draft
        if (!d.has("available")) d.put("available", System.currentTimeMillis())
        val db = repo.db.writableDatabase
        db.beginTransaction()
        try {
            repo.db.save("batch", d)
            repo.db.delete(JSONObject().put("id", "draft-" + d.getString("id")))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        draftJson = "{}"
        saved["draft"] = draftJson
        home()
    }

    fun chooseFood(f: JSONObject, newMode: String = mode, editIndex: Int = -1) {
        cancelFoodLookups()
        lookupBarcode = f.optString("barcode")
        saved["lookupBarcode"] = lookupBarcode
        selectedFoodSource = FoodSource.SAVED
        saved["foodSource"] = FoodSource.SAVED.name
        foodChoices = mapOf(FoodSource.SAVED to FoodChoice(LookupPhase.READY, f.toString()))
        mode = newMode
        ingredientIndex = editIndex
        setFood(JSONObject(f.toString()))
        clearForm("portion")
        val index = editIndex
        if (index >= 0)
            field(
                "portion",
                "canonical",
                draft
                    .getJSONArray("ingredients")
                    .getJSONObject(index)
                    .getDouble("quantity")
                    .toString(),
                true,
            )
        try {
            Data.validate("food", food)
            go("portion")
        } catch (e: Exception) {
            clearForm("food")
            go("food-edit")
        }
    }

    fun manualFood() {
        cancelFoodLookups()
        lookupBarcode = ""
        saved["lookupBarcode"] = ""
        foodChoices = emptyMap()
        selectedFoodSource = null
        saved.remove<String>("foodSource")
        setFood(JSONObject())
        clearForm("food")
        go("food-edit")
    }

    fun editFood() {
        sourcePinned = true
        requestedFoodSource = selectedFoodSource
        clearForm("food")
        go("food-edit")
    }

    private fun cancelFoodLookups() {
        lookupGeneration++
        foodJobs.forEach { it.cancel() }
        // Retain canceled-but-running I/O so restore can join it before replacing the DB.
        foodJobs.removeAll { it.isCompleted }
    }

    fun lookup(raw: String) {
        val code = BarcodeValue.productCode(raw)
        if (code == null) {
            error = "Unsupported barcode or QR code"
            return
        }
        cancelFoodLookups()
        val local =
            repo.db.list("food").firstOrNull {
                it.optString("barcode").isNotEmpty() &&
                    it.optString("barcode").padStart(14, '0') == code.padStart(14, '0')
            }
        lookupBarcode = code
        saved["lookupBarcode"] = code
        lookupFallback = code
        lookupFoodId = local?.getString("id") ?: UUID.randomUUID().toString()
        sourcePinned = false
        requestedFoodSource = null
        selectedFoodSource = null
        saved.remove<String>("foodSource")
        foodChoices =
            mapOf(
                FoodSource.SAVED to
                    if (local != null) FoodChoice(LookupPhase.READY, local.toString())
                    else FoodChoice(LookupPhase.NOT_FOUND)
            )
        foodJson = "{}"
        saved["food"] = foodJson
        clearForm("portion")
        go("portion")
        // Start both before choosing a remote default. A fast FatSecret result waits
        // for OFF, while an existing saved food is usable immediately.
        foodChoices =
            foodChoices +
                (FoodSource.OFF to FoodChoice(LookupPhase.LOADING)) +
                (FoodSource.FATSECRET to
                    FoodChoice(
                        if (fatSecretConfigured) LookupPhase.LOADING else LookupPhase.NEEDS_KEY
                    ))
        selectDefaultFood()
        fetchSource(FoodSource.OFF)
        if (fatSecretConfigured) fetchSource(FoodSource.FATSECRET)
    }

    private fun fetchSource(source: FoodSource) {
        val generation = lookupGeneration
        val code = lookupBarcode
        val sourceRevision = (sourceRevisions[source] ?: 0) + 1
        sourceRevisions[source] = sourceRevision
        val id =
            lookupFoodId.ifBlank { food.optString("id").ifBlank { UUID.randomUUID().toString() } }
        foodChoices = foodChoices + (source to FoodChoice(LookupPhase.LOADING))
        foodJobs.removeAll { it.isCompleted }
        foodJobs +=
            viewModelScope.launch {
                val choice =
                    try {
                        val result =
                            withContext(Dispatchers.IO) {
                                if (source == FoodSource.OFF) foodProviders.off(code)
                                else
                                    foodProviders.fatSecret(
                                        code,
                                        credentialStore.read()
                                            ?: throw IllegalStateException(
                                                "Add FatSecret credentials in Settings"
                                            ),
                                    )
                            }
                        result.put("id", id)
                        if (source == FoodSource.OFF) result.put("provider", "openfoodfacts")
                        FoodChoice(LookupPhase.READY, result.toString())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: FoodNotFound) {
                        FoodChoice(LookupPhase.NOT_FOUND, note = e.message ?: "Product not found")
                    } catch (e: Exception) {
                        FoodChoice(
                            LookupPhase.ERROR,
                            note =
                                if (e is IllegalStateException) e.message ?: "Lookup failed"
                                else
                                    "Couldn't reach ${source.label}. Saved foods still work offline.",
                        )
                    }
                if (
                    generation != lookupGeneration ||
                        sourceRevisions[source] != sourceRevision ||
                        (source == FoodSource.FATSECRET && !fatSecretConfigured)
                )
                    return@launch
                foodChoices = foodChoices + (source to choice)
                selectDefaultFood()
            }
    }

    private fun selectDefaultFood() {
        if (route != "portion") return
        if (sourcePinned) {
            requestedFoodSource?.let {
                if (foodChoices[it]?.phase == LookupPhase.READY && selectedFoodSource != it)
                    applyFoodSource(it)
            }
            return
        }
        val savedFood = foodChoices[FoodSource.SAVED]
        val off = foodChoices[FoodSource.OFF]
        val fs = foodChoices[FoodSource.FATSECRET]
        val selected =
            when {
                savedFood?.json?.let(::foodComplete) == true -> FoodSource.SAVED
                off?.phase == LookupPhase.LOADING -> null
                off?.json?.let(::foodComplete) == true -> FoodSource.OFF
                fs?.phase == LookupPhase.LOADING -> null
                fs?.json?.let(::foodComplete) == true -> FoodSource.FATSECRET
                off?.phase == LookupPhase.READY -> FoodSource.OFF
                fs?.phase == LookupPhase.READY -> FoodSource.FATSECRET
                else -> null
            }
        if (selected != null && selectedFoodSource != selected) applyFoodSource(selected)
    }

    fun foodChoice(source: FoodSource): FoodChoice =
        foodChoices[source]
            ?: FoodChoice(
                if (source == FoodSource.FATSECRET && !fatSecretConfigured) LookupPhase.NEEDS_KEY
                else LookupPhase.IDLE
            )

    fun selectFoodSource(source: FoodSource) {
        when (foodChoice(source).phase) {
            LookupPhase.NEEDS_KEY -> promptFatSecretSetup = true
            LookupPhase.NOT_FOUND ->
                foodSourceNotice =
                    if (source == FoodSource.SAVED) "No saved food for this barcode yet"
                    else "${source.label} didn't find this barcode"
            LookupPhase.ERROR -> foodSourceNotice = foodChoice(source).note
            LookupPhase.IDLE -> {
                if (lookupBarcode.isBlank()) {
                    foodSourceNotice = "Scan a barcode first"
                    return
                }
                sourcePinned = true
                requestedFoodSource = source
                lookupFoodId = food.optString("id").ifBlank { UUID.randomUUID().toString() }
                if (source == FoodSource.SAVED)
                    foodSourceNotice = "No saved food for this barcode yet"
                else fetchSource(source)
            }
            LookupPhase.LOADING -> {
                sourcePinned = true
                requestedFoodSource = source
                message = "${source.label} will open when ready"
            }
            LookupPhase.READY -> {
                sourcePinned = true
                requestedFoodSource = source
                applyFoodSource(source)
            }
        }
    }

    private fun applyFoodSource(source: FoodSource) {
        val next = JSONObject(foodChoices[source]?.json ?: return)
        val old = food
        val canonical = field("portion", "canonical").toDoubleOrNull()
        var converted: Double? = null
        var sharedUnit: String? = null
        if (canonical != null && canonical > 0 && old.has("unit") && next.has("unit")) {
            for (unit in listOf("g", "ml")) {
                try {
                    val physical =
                        Portion.convert(
                            canonical,
                            old.getString("unit"),
                            unit,
                            old.optDouble("servingGrams", 0.0),
                            old.optDouble("servingMl", 0.0),
                        )
                    converted =
                        Portion.convert(
                            physical,
                            unit,
                            next.getString("unit"),
                            next.optDouble("servingGrams", 0.0),
                            next.optDouble("servingMl", 0.0),
                        )
                    sharedUnit = unit
                    break
                } catch (_: IllegalArgumentException) {}
            }
        }
        setFood(next)
        clearForm("portion")
        if (converted != null) {
            field("portion", "canonical", converted.toString(), true)
            // Display the same physical amount, even when label serving sizes differ.
            field("portion", "unit", sharedUnit!!, true)
        } else if (canonical != null) {
            message = "Portion reset: these sources have no shared weight or volume conversion"
        }
        selectedFoodSource = source
        saved["foodSource"] = source.name
    }

    fun openFatSecretSettings() {
        go("fatsecret-settings")
    }

    fun saveFatSecretCredentials(clientId: String, secret: String) {
        credentialStore.save(FatSecretKeys(clientId, secret))
        fatSecretConfigured = true
        foodProviders.clearToken()
        back()
        if (lookupBarcode.isNotBlank() && route == "portion") fetchSource(FoodSource.FATSECRET)
    }

    fun clearFatSecretCredentials() {
        credentialStore.clear()
        sourceRevisions[FoodSource.FATSECRET] = (sourceRevisions[FoodSource.FATSECRET] ?: 0) + 1
        fatSecretConfigured = false
        foodProviders.clearToken()
        foodChoices = foodChoices + (FoodSource.FATSECRET to FoodChoice(LookupPhase.NEEDS_KEY))
        message = "FatSecret credentials removed"
    }

    fun fallbackFood() {
        val code = BarcodeValue.productCode(lookupFallback ?: lookupBarcode)
        setFood(JSONObject().apply { if (code != null) put("barcode", code) })
        clearForm("food")
        lookupFallback = null
        error = null
        go("food-edit")
    }

    fun saveFood(f: JSONObject) {
        Data.validate("food", f)
        repo.db.save("food", f)
        setFood(f)
        foodChoices =
            foodChoices + (FoodSource.SAVED to FoodChoice(LookupPhase.READY, f.toString()))
        // Keep the prefetched provider values intact for comparison with this correction.
        selectedFoodSource = FoodSource.SAVED
        requestedFoodSource = FoodSource.SAVED
        saved["foodSource"] = FoodSource.SAVED.name
        sourcePinned = true
        refresh()
        if (mode == "library") go("foods") else go("portion")
    }

    fun addFood(quantity: Double) {
        require(quantity.isFinite() && quantity > 0 && quantity <= 1_000_000) {
            "Enter a positive quantity"
        }
        val f = food
        repo.db.save("food", f)
        if (mode == "dish") {
            val d = draft
            val ingredients = d.getJSONArray("ingredients")
            val item = JSONObject().put("food", JSONObject(f.toString())).put("quantity", quantity)
            if (ingredientIndex >= 0) ingredients.put(ingredientIndex, item)
            else ingredients.put(item)
            setDraft(d)
            go("cook")
            refresh()
        } else {
            val now = System.currentTimeMillis()
            repo.db.save(
                "direct",
                JSONObject()
                    .put("name", f.getString("name"))
                    .put("available", now)
                    .put("completed", now)
                    .put("share", 1)
                    .put(
                        "ingredients",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("food", JSONObject(f.toString()))
                                    .put("quantity", quantity)
                            ),
                    ),
            )
            home()
        }
    }

    fun recordPortion(id: String, fraction: Double) {
        repo.db.save(
            "batch",
            BatchOperations.record(record("batch", id), fraction, System.currentTimeMillis(), "ate"),
        )
        portionBatch = null
        home()
    }

    fun finish(id: String) {
        repo.db.save(
            "batch",
            BatchOperations.finish(record("batch", id), System.currentTimeMillis()),
        )
        finishBatch = null
        home()
    }

    fun undo(id: String) {
        repo.db.save("batch", BatchOperations.undo(record("batch", id)))
        home()
    }

    fun detail(kind: String, id: String, portionId: String = "") {
        selectedKind = kind
        selectedId = id
        clearForm("details")
        field("details", "portionId", portionId, true)
        go("details")
    }

    fun saveDetails(start: Long, completion: Long?, share: Double) {
        val d = record(selectedKind, selectedId)
        d.put("available", if (selectedKind == "direct") completion ?: start else start)
            .put("completed", completion ?: JSONObject.NULL)
            .put("share", share)
        repo.db.save(selectedKind, d)
        home()
    }

    fun delete() {
        val kind = deleteRecord?.first
        deleteRecord?.let { repo.db.delete(record(it.first, it.second)) }
        deleteRecord = null
        if (kind == "activity") {
            back()
            refresh()
        } else home()
    }

    fun updateEvent(id: String, index: Int, fraction: Double, time: Long, remove: Boolean) {
        val b = record("batch", id)
        val events = b.getJSONArray("eatingEvents")
        val event = events.getJSONObject(index)
        if (remove) {
            if (b.optString("finishEventId") == event.getString("id")) b.remove("finishEventId")
            events.remove(index)
        } else event.put("fraction", fraction).put("time", time)
        repo.db.save("batch", b)
        eventEdit = null
        home()
    }

    fun beginCalories(existing: JSONObject? = null) {
        selectedId = existing?.getString("id") ?: ""
        clearForm("calories")
        go("calories")
    }

    fun saveCalories(name: String, kcal: Double, time: Long? = null) {
        val d =
            if (selectedId.isEmpty()) JSONObject().put("time", System.currentTimeMillis())
            else record("calorie", selectedId)
        if (time != null) {
            require(time <= System.currentTimeMillis()) { "Eating time cannot be in the future" }
            d.put("time", time)
        }
        d.put("name", name).put("kcal", kcal)
        repo.db.save("calorie", d)
        home()
    }

    fun openSettings() {
        clearForm("settings")
        go("settings")
    }

    fun saveSettings(
        start: LocalDate,
        effective: LocalDate,
        rate: Double,
        goal: Double,
        week: Int,
        duration: Double,
    ) {
        require(!start.isAfter(LocalDate.now(repo.zone))) {
            "Tracking start cannot be in the future"
        }
        require(repo.db.list("rate").any { it.getString("date") <= start.toString() }) {
            "Tracking start must be covered by a recorded expenditure rate"
        }
        require(goal < 7 * rate) { "Goal must leave a positive resting-only intake target" }
        val s =
            JSONObject(repo.settings.toString())
                .put("start", start.toString())
                .put("week", week)
                .put("duration", duration)
                .put("goal", goal)
        val r =
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("date", effective.toString())
                .put("value", rate)
        Data.validate("settings", s)
        Data.validate("resting", r)
        require(effective >= LocalDate.parse(repo.settings.getString("energyStart"))) {
            "Resting changes start on or after ${repo.settings.getString("energyStart")}; earlier dates retain legacy expenditure"
        }
        val db = repo.db.writableDatabase
        db.beginTransaction()
        try {
            repo.db
                .list("resting")
                .filter { it.getString("date") == effective.toString() }
                .forEach { repo.db.delete(it) }
            repo.db.save("resting", r)
            repo.db.save("settings", s)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        home()
    }

    fun openNutrientProfile() {
        clearForm("nutrient-profile")
        go("nutrient-profile")
    }

    fun saveNutrientProfile(birthMonthYear: String, sex: String, stage: String) {
        val birth = NutrientReferences.parseBirthMonth(birthMonthYear)
        val profile =
            JSONObject()
                .put("policy", NutrientReferences.POLICY)
                .put("birthMonth", birth.monthValue)
                .put("birthYear", birth.year)
                .put("sex", sex)
                .put("stage", stage)
        NutrientReferences.validate(profile)
        val settings = JSONObject(repo.settings.toString()).put("nutrientProfile", profile)
        repo.db.save("settings", settings)
        back()
        refresh()
    }

    fun clearNutrientProfile() {
        val settings = JSONObject(repo.settings.toString()).apply { remove("nutrientProfile") }
        repo.db.save("settings", settings)
        back()
        refresh()
    }

    fun saveGoal(index: Int, value: Double?) {
        val s = JSONObject(repo.settings.toString())
        val g = s.optJSONObject("nutrientGoals") ?: JSONObject()
        if (value == null) g.remove(Nutrients.KEYS[index]) else g.put(Nutrients.KEYS[index], value)
        s.put("nutrientGoals", g)
        repo.db.save("settings", s)
        refresh()
    }

    fun export(resolver: ContentResolver, uri: Uri) {
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri)!!.use {
                        it.write(repo.db.backup().toByteArray(Charsets.UTF_8))
                    }
                }
                message = "Backup saved"
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    fun readRestore(resolver: ContentResolver, uri: Uri) {
        busy = true
        viewModelScope.launch {
            try {
                pendingRestore =
                    withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)!!.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val b = ByteArray(8192)
                            while (true) {
                                val n = input.read(b)
                                if (n < 0) break
                                require(out.size() + n <= Data.MAX_BACKUP_BYTES) {
                                    "Backup exceeds 64 MB"
                                }
                                out.write(b, 0, n)
                            }
                            out.toString("UTF-8")
                        }
                    }
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    fun restore() {
        val text = pendingRestore ?: return
        pendingRestore = null
        busy = true
        viewModelScope.launch {
            try {
                stepsJob?.cancelAndJoin()
                pendingStepDates.clear()
                val activeFoodJobs = foodJobs.toList()
                cancelFoodLookups()
                activeFoodJobs.forEach { it.join() }
                foodChoices = emptyMap()
                lookupBarcode = ""
                saved["lookupBarcode"] = ""
                selectedFoodSource = null
                saved.remove<String>("foodSource")
                withContext(Dispatchers.IO) { repo.db.restore(text) }
                draftJson = "{}"
                saved["draft"] = draftJson
                home()
                refreshSteps()
                message = "Backup restored"
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    init {
        if (route == "portion" && food.optString("name").isBlank() && lookupBarcode.isNotBlank()) {
            lookup(lookupBarcode)
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelFoodLookups()
        repo.db.close()
    }
}
