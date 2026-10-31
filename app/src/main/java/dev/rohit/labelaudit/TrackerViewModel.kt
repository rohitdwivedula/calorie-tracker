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

    fun removeIngredient(index: Int) {
        val d = draft
        d.getJSONArray("ingredients").remove(index)
        setDraft(d)
    }

    fun saveCook() {
        val d = draft
        if (!d.has("available")) d.put("available", System.currentTimeMillis())
        repo.db.save("batch", d)
        home()
    }

    fun chooseFood(f: JSONObject, newMode: String = mode, editIndex: Int = -1) {
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
        setFood(JSONObject())
        clearForm("food")
        go("food-edit")
    }

    fun editFood() {
        clearForm("food")
        go("food-edit")
    }

    fun lookup(raw: String) {
        if (busy) return
        val origin = route
        val requestedMode = mode
        lookupFallback = raw
        busy = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { FoodLookup.fetch(repo.db, raw) }
                lookupFallback = null
                if (route == origin && mode == requestedMode) chooseFood(result)
            } catch (e: Exception) {
                if (route == origin) error = e.message ?: "Lookup failed. Saved foods work offline."
            } finally {
                busy = false
            }
        }
    }

    fun fallbackFood() {
        val code = BarcodeValue.productCode(lookupFallback)
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
                withContext(Dispatchers.IO) { repo.db.restore(text) }
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

    override fun onCleared() {
        super.onCleared()
        repo.db.close()
    }
}
