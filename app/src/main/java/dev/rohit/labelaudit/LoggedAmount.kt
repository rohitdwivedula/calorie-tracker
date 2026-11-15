package dev.rohit.labelaudit

import org.json.JSONObject

/** Amounts come from immutable ingredient snapshots, never current library nutrition or kcal. */
object LoggedAmount {
    fun single(record: JSONObject, share: Double): String? {
        val ingredients = record.getJSONArray("ingredients")
        if (ingredients.length() != 1) return null
        val item = ingredients.getJSONObject(0)
        return food(item.getJSONObject("food"), item.getDouble("quantity") * share)
    }

    fun food(food: JSONObject, quantity: Double): String? {
        if (food.optBoolean("calorieOnly")) return null
        val unit = food.getString("unit")
        val grams = food.optDouble("servingGrams", 0.0)
        val ml = food.optDouble("servingMl", 0.0)
        val servings =
            when {
                unit == "serving" -> quantity
                unit == "g" && grams > 0 -> quantity / grams
                unit == "ml" && ml > 0 -> quantity / ml
                else -> return "${fmt(quantity)} ${if (unit == "ml") "mL" else unit}"
            }
        val count = "${fmt(servings)} ${if (servings == 1.0) "serving" else "servings"}"
        return when {
            unit == "g" -> "$count · ${fmt(quantity)} g"
            unit == "ml" -> "$count · ${fmt(quantity)} mL"
            grams > 0 -> "$count · ${fmt(quantity * grams)} g"
            ml > 0 -> "$count · ${fmt(quantity * ml)} mL"
            else -> count
        }
    }
}
