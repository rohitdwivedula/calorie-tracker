package dev.rohit.labelaudit

import java.math.BigDecimal
import org.json.JSONObject
import org.json.JSONTokener

/** User-reviewed label transcription interchange, never a database import. */
object LabelJson {
    val prompt: String
        get() =
            """
            Inspect the food image, including any label, menu or order text. Use earlier photos
            and messages about this same food, even if this prompt came later. If the image
            comes next, use it then. Return one JSON object I can paste into my food log.

            First identify the food. Use the printed name or a descriptive name from the
            conversation. For an order, combine the restaurant, dish and listed components.
            Then read every nutrient row in one serving column, including vitamins and minerals.
            Before returning JSON, check each supported key against the image and any explicit
            amounts for that same food and serving in the chat, including menu nutrition already
            looked up and reported in an earlier reply. Prefer the matching label if values conflict.
            Include every supported value
            you found, including a stated zero. Only omit a value or use null after checking both.

            Never estimate nutrition from appearance, ingredients, typical menu values or the
            example below. Keep order count separate from the nutrition basis for one item.
            If only the food is identifiable, return its name with an empty nutrients object.
            If neither the food nor any nutrition is identifiable, ask for a clearer image or text.

            Example format (replace the example values):
            {"schemaVersion":1,"name":"Example food","basis":{"quantity":1,"unit":"serving"},
             "nutrients":{"kcal":{"amount":120,"unit":"kcal"},"protein":{"amount":6,"unit":"g"}}}

            Use one consistent label column. For a per-100-g label, use quantity 100 and unit "g".
            Basis units: serving, g, ml. For a serving, you can include servingGrams or servingMl
            in basis when given. Keep mass and volume separate. Use null for an unknown name.

            Supported nutrient keys and typical units:
            ${Nutrients.KEYS.indices.joinToString(", ") { "${Nutrients.KEYS[it]} (${Nutrients.unit(it)})" }}.
            Use kcal for energy and g, mg or ug for mass. For vitamin A use RAE, vitamin E
            alpha-tocopherol and folate DFE. Omit unsupported units such as IU.

            Prefer a printed amount. If a vitamin or mineral gives only a US % Daily Value,
            its entry may instead look like {"percentDV":10,"standard":"us-current","form":"unknown"}.
            Use 10 for 10%. Use "us-legacy" for older US labels. If the standard is unclear,
            skip that nutrient and return the rest of the JSON.
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
        require(
            fields.has("name") ||
                percentages.length() > 0 ||
                Nutrients.KEYS.any { fields.optString(it).isNotBlank() }
        ) {
            "The JSON contains no food name or nutrient values. Check the image or conversation."
        }
        fields.put("labelPercentages", percentages)
        return fields
    }
}
