package dev.rohit.labelaudit

import java.math.BigDecimal
import org.json.JSONObject
import org.json.JSONTokener

/** User-reviewed label transcription interchange, never a database import. */
object LabelJson {
    val prompt: String
        get() =
            """
            Transcribe the attached food nutrition label into JSON for my nutrition app.
            Return ONLY one JSON object, without commentary. Do not guess unreadable or absent values;
            use null or omit them. Use 0 only when explicitly printed as zero. Do not infer nutrients
            from ingredients, percentages, calorie totals, or another food. Do not round or fix values.
            Use the nutrition column for ONE label serving, not the whole package, when available.
            If multiple columns exist, use one consistently for all nutrients and the basis.

            Schema:
            {
              "schemaVersion": 1,
              "name": "Product name, or null if not visible",
              "basis": {"quantity": 1, "unit": "serving", "servingGrams": null, "servingMl": null},
              "nutrients": {
                "kcal": {"amount": 100, "unit": "kcal"},
                "protein": {"amount": 5, "unit": "g"},
                "carbs": {"amount": 10, "unit": "g"},
                "fat": {"amount": 4, "unit": "g"}
              }
            }
            The numbers above are examples ONLY; replace them using the picture.
            basis.unit must be serving, g, or ml. basis.quantity is the number of those units
            covered by the nutrition column. For per-serving nutrition use quantity 1, unit serving,
            and record grams or mL in ONE serving only if printed. For per-100-g nutrition use
            quantity 100, unit g. Never assume mL equals grams. Omit unknown serving conversions.
            Use these nutrient keys (usual display units in parentheses):
            ${Nutrients.KEYS.indices.joinToString(", ") { "${Nutrients.KEYS[it]} (${Nutrients.unit(it)})" }}.
            Amount units must be kcal for kcal, or g/mg/ug (micrograms) for other nutrients.
            Vitamin A amounts must be ug RAE, vitamin E mg alpha-tocopherol, folate ug DFE;
            do not convert IU or other forms yourself. Omit unsupported amount units.

            Prefer a printed absolute amount over a percentage. If a vitamin/mineral is ONLY
            printed as % Daily Value, use this object INSTEAD of amount/unit:
            {"percentDV": 10, "standard": "us-current", "form": "unknown"}.
            The percentage is 10 for 10%, not 0.1. Use us-current only for current general US
            adult/children-4+ labels; use us-legacy for the older US format (often includes Calories
            from Fat). If the reference/country/age group is unclear, omit the percentage and ask
            me to clarify. Never use my personal nutrient goals as a label reference.
            Leave form unknown unless explicitly identified. Supported older-US forms:
            vitamin-a: retinol, food-beta-carotene, other-food-carotenoids;
            vitamin-e: natural-alpha, synthetic-alpha;
            vitamin-b9: food-folate, folic-acid-with-food.
            Do not include any IDs, URLs, API credentials or other fields. I will review the form
            before saving; missing calories or core macros must be filled in by me.
        """
                .trimIndent()

    /** Complete nutrition-form replacement in display units; no writes or network requests. */
    fun parse(raw: String): JSONObject {
        require(raw.length <= 100_000) { "JSON is too large" }
        var text = raw.trim()
        if (text.startsWith("```")) {
            require(text.endsWith("```")) { "Finish the JSON code block" }
            require(text.substringBefore('\n').trim().lowercase() in listOf("```", "```json")) {
                "Paste a JSON object"
            }
            text = text.substringAfter('\n').dropLast(3).trim()
        }
        val root =
            try {
                val tokener = JSONTokener(text)
                val value = tokener.nextValue()
                require(value is JSONObject && tokener.nextClean() == '\u0000')
                value
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Invalid JSON. Paste the complete object from the label prompt.",
                    e,
                )
            }
        fun onlyKeys(obj: JSONObject, allowed: Set<String>, where: String) {
            for (key in obj.keys()) require(key in allowed) { "Unsupported $where field: $key" }
        }
        fun number(obj: JSONObject, key: String, where: String, positive: Boolean = false): Double {
            val rawValue = obj.opt(key)
            require(rawValue is Number) { "$where must be a number" }
            val value = rawValue.toDouble()
            require(value.isFinite() && value >= (if (positive) .001 else 0.0)) {
                "$where must be ${if (positive) "at least 0.001" else "nonnegative"}"
            }
            require(value <= 100_000) { "$where is too large" }
            return value
        }
        fun decimal(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        onlyKeys(root, setOf("schemaVersion", "name", "basis", "nutrients"), "top-level")
        require(root.opt("schemaVersion") is Number && root.getDouble("schemaVersion") == 1.0) {
            "Use schemaVersion 1 from the copied prompt"
        }
        val fields = JSONObject()
        if (root.has("name") && !root.isNull("name")) {
            require(root.get("name") is String && root.getString("name").trim().isNotEmpty()) {
                "Food name must be text or null"
            }
            fields.put("name", root.getString("name").trim())
        }
        val basis = root.optJSONObject("basis") ?: error("Include the nutrition basis")
        onlyKeys(basis, setOf("quantity", "unit", "servingGrams", "servingMl"), "basis")
        val unit = basis.optString("unit")
        require(unit in listOf("serving", "g", "ml")) { "Basis unit must be serving, g or ml" }
        fields
            .put("unit", unit)
            .put("basis", decimal(number(basis, "quantity", "Basis quantity", true)))
        for (key in listOf("servingGrams", "servingMl")) {
            fields.put(
                key,
                if (basis.has(key) && !basis.isNull(key)) decimal(number(basis, key, key, true))
                else "",
            )
        }
        val nutrients = root.optJSONObject("nutrients") ?: error("Include the nutrients object")
        onlyKeys(nutrients, Nutrients.KEYS.toSet(), "nutrient")
        val percentages = JSONObject()
        for (i in Nutrients.KEYS.indices) {
            val key = Nutrients.KEYS[i]
            fields.put(key, "") // Never mix an old label's values with unknown imported fields.
            if (!nutrients.has(key) || nutrients.isNull(key)) continue
            val entry = nutrients.optJSONObject(key) ?: error("$key needs amount/unit or percentDV")
            if (entry.has("percentDV")) {
                require(i >= 5) { "$key requires an absolute amount" }
                onlyKeys(entry, setOf("percentDV", "standard", "form"), key)
                val percent = number(entry, "percentDV", "$key percentDV")
                val standard = entry.optString("standard")
                val form = entry.optString("form", "unknown")
                percentages.put(key, LabelDailyValues.entry(key, percent, standard, form))
                LabelDailyValues.grams(key, percent, standard, form)?.let {
                    fields.put(key, decimal(it * Nutrients.displayFactor(i)))
                }
            } else {
                onlyKeys(entry, setOf("amount", "unit"), key)
                if (entry.isNull("amount")) continue
                val amount = number(entry, "amount", "$key amount")
                val amountUnit =
                    entry
                        .optString("unit")
                        .lowercase()
                        .replace("µ", "u")
                        .replace("μ", "u")
                        .replace("mcg", "ug")
                val normalized =
                    if (i == 0) {
                        require(amountUnit == "kcal") { "Calories must use kcal" }
                        amount
                    } else {
                        amount /
                            when (amountUnit) {
                                "g" -> 1.0
                                "mg" -> 1000.0
                                "ug" -> 1_000_000.0
                                else ->
                                    error(
                                        "$key unit must be g, mg or ug; IU needs a supported %DV entry"
                                    )
                            }
                    }
                fields.put(key, decimal(normalized * Nutrients.displayFactor(i)))
            }
        }
        fields.put("labelPercentages", percentages)
        return fields
    }
}
