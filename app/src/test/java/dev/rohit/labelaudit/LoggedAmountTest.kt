package dev.rohit.labelaudit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LoggedAmountTest {
    private fun food(unit: String = "serving") = JSONObject().put("unit", unit).put("basis", 100)

    @Test
    fun servingCountIncludesVerifiedMassAndUsesQuantityNotNutritionBasis() {
        assertEquals("2 servings · 120 g", LoggedAmount.food(food().put("servingGrams", 60), 2.0))
        assertEquals("1 serving · 60 g", LoggedAmount.food(food().put("servingGrams", 60), 1.0))
    }

    @Test
    fun gramQuantityConvertsOnlyThroughExplicitServingSize() {
        assertEquals(
            "2 servings · 120 g",
            LoggedAmount.food(food("g").put("servingGrams", 60), 120.0),
        )
        assertEquals(
            "0.5 servings · 30 g",
            LoggedAmount.food(food("g").put("servingGrams", 60), 30.0),
        )
    }

    @Test
    fun volumeDoesNotUseGramConversionOrAssumeDensity() {
        val f = food("ml").put("servingGrams", 50)
        assertEquals("100 mL", LoggedAmount.food(f, 100.0))
        f.put("servingMl", 250)
        assertEquals("2 servings · 500 mL", LoggedAmount.food(f, 500.0))
    }

    @Test
    fun noConversionDoesNotTurnNutritionBasisOrLabelTextIntoServingCount() {
        assertEquals("200 g", LoggedAmount.food(food("g").put("servingLabel", "100 g"), 200.0))
        assertEquals("2 servings", LoggedAmount.food(food(), 2.0))
    }

    @Test
    fun directRecordShareScalesSavedQuantityRatherThanCalories() {
        val f = food("g").put("servingGrams", 60).put("kcal", 999)
        val record =
            JSONObject()
                .put(
                    "ingredients",
                    JSONArray().put(JSONObject().put("food", f).put("quantity", 120)),
                )
        assertEquals("1 serving · 60 g", LoggedAmount.single(record, .5))
        assertEquals("0 servings · 0 g", LoggedAmount.single(record, 0.0))
    }

    @Test
    fun multipleFoodsAndCalorieOnlyEntriesHaveNoInventedServingCount() {
        assertNull(LoggedAmount.food(food().put("calorieOnly", true), 1.0))
        assertNull(
            LoggedAmount.single(
                JSONObject().put("ingredients", JSONArray().put(JSONObject()).put(JSONObject())),
                1.0,
            )
        )
    }
}
