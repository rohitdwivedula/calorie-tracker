package dev.rohit.labelaudit

import org.json.JSONObject

data class FoodEntryResult(val food: JSONObject?, val errors: Map<String, String>)

/** Validates user input before parsing; untouched API precision and unknowns are preserved. */
object FoodEntry {
    fun initial(original: JSONObject, key: String): String {
        if (!original.has(key)) return ""
        val index = Nutrients.KEYS.indexOf(key)
        return when {
            index >= 0 -> fmt(original.getDouble(key) * Nutrients.displayFactor(index))
            key in listOf("basis", "servingGrams", "servingMl") -> fmt(original.getDouble(key))
            else -> original.optString(key)
        }
    }

    fun build(original: JSONObject, input: JSONObject): FoodEntryResult {
        val result = JSONObject(original.toString())
        val errors = linkedMapOf<String, String>()
        fun text(key: String) = input.optString(key, initial(original, key))
        val name = text("name").trim()
        if (name.isBlank()) errors["name"] = "Enter a food name"
        result.put("name", name)
        val unit = input.optString("unit", original.optString("unit", "serving"))
        if (unit !in listOf("serving", "g", "ml")) errors["unit"] = "Choose serving, g or ml"
        result.put("unit", unit)
        fun numeric(
            key: String,
            required: Boolean,
            positive: Boolean,
            factor: Double = 1.0,
            default: String = text(key),
        ) {
            val value = default.trim()
            if (value.isBlank()) {
                if (required)
                    errors[key] =
                        if (key == "basis") "Enter a nutrition quantity"
                        else "Required · enter 0 if the label says 0"
                else result.remove(key)
                return
            }
            val number = value.toDoubleOrNull()
            if (
                number == null || !number.isFinite() || if (positive) number < 0.001 else number < 0
            ) {
                errors[key] =
                    if (positive) "Enter a number of at least 0.001"
                    else "Enter a nonnegative number"
                return
            }
            val normalized = number / factor
            if (normalized > 100000.0) {
                errors[key] = "Value is too large"
                return
            }
            if (
                !original.has(key) ||
                    value != initial(original, key) ||
                    input.optBoolean("percentApplied:$key", false) ||
                    input.optBoolean("edited:$key", false)
            )
                result.put(key, normalized)
        }
        numeric(
            "basis",
            true,
            true,
            default = input.optString("basis", initial(original, "basis").ifBlank { "1" }),
        )
        for (key in listOf("kcal", "protein", "carbs", "fat")) numeric(key, true, false)
        for (i in 4 until Nutrients.KEYS.size) numeric(
            Nutrients.KEYS[i],
            false,
            false,
            Nutrients.displayFactor(i),
        )
        for (key in listOf("servingGrams", "servingMl")) numeric(key, false, true)
        val percentages =
            input.optJSONObject("labelPercentages") ?: original.optJSONObject("labelPercentages")
        if (percentages != null && percentages.length() > 0) {
            try {
                LabelDailyValues.validate(percentages)
                result.put("labelPercentages", JSONObject(percentages.toString()))
            } catch (_: Exception) {
                errors["labelPercentages"] = "Check the label percentages"
            }
        } else result.remove("labelPercentages")
        if (
            result.optString("unit") != original.optString("unit") ||
                result.optDouble("servingGrams", 0.0) != original.optDouble("servingGrams", 0.0) ||
                result.optDouble("servingMl", 0.0) != original.optDouble("servingMl", 0.0)
        )
            result.remove("servingLabel")
        if (errors.isEmpty())
            result.remove("calorieOnly") // Full manual nutrition replaces an energy-only estimate.
        return FoodEntryResult(if (errors.isEmpty()) result else null, errors)
    }
}
