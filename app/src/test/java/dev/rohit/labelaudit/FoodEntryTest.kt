package dev.rohit.labelaudit

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FoodEntryTest {
    private fun original() = JSONObject().put("id", "synthetic-manual-food")

    private fun input() =
        JSONObject()
            .put("name", "Test ingredient")
            .put("basis", "1")
            .put("unit", "serving")
            .put("kcal", "99")
            .put("protein", "9")
            .put("carbs", "0")
            .put("fat", "2.5")

    @Test
    fun blankRequiredMacroHasFieldErrorInsteadOfParsingEmptyStringOrAssumingZero() {
        val result = FoodEntry.build(original(), input().put("carbs", ""))
        assertNull(result.food)
        assertTrue(result.errors.getValue("carbs").contains("0"))
        assertEquals(setOf("carbs"), result.errors.keys)
    }

    @Test
    fun explicitZeroIsValidButBlankOptionalNutrientsRemainUnknown() {
        val result = FoodEntry.build(original(), input())
        assertTrue(result.errors.isEmpty())
        assertEquals(0.0, result.food!!.getDouble("carbs"), 0.0)
        assertFalse(result.food.has("fiber"))
        assertFalse(result.food.has("vitamin-a"))
    }

    @Test
    fun invalidNumbersAreNamedAndZeroBasisIsRejected() {
        for (bad in listOf("-1", "NaN", "Infinity", "hello", ".")) {
            assertTrue(
                FoodEntry.build(original(), input().put("protein", bad))
                    .errors
                    .containsKey("protein")
            )
        }
        assertTrue(
            FoodEntry.build(original(), input().put("basis", "0")).errors.containsKey("basis")
        )
        assertTrue(
            FoodEntry.build(original(), input().put("servingGrams", ".0001"))
                .errors
                .containsKey("servingGrams")
        )
    }

    @Test
    fun openingAndSavingAnApiFoodDoesNotRoundItsUntouchedNutrition() {
        val food =
            input()
                .put("id", "synthetic-api-food")
                .put("protein", 9.123456789)
                .put("vitamin-a", .000018001)
                .put("fiber", 2.12345678)
        val result = FoodEntry.build(food, JSONObject()).food!!
        assertEquals(9.123456789, result.getDouble("protein"), 0.0)
        assertEquals(.000018001, result.getDouble("vitamin-a"), 0.0)
        assertEquals(2.12345678, result.getDouble("fiber"), 0.0)
    }

    @Test
    fun explicitPercentApplyReplacesNearIdenticalRoundedApiAmountAndKeepsRawInput() {
        val food = input().put("id", "synthetic-api-food").put("vitamin-a", .000018001)
        val entry = LabelDailyValues.entry("vitamin-a", 2.0, LabelDailyValues.CURRENT, "unknown")
        val changed =
            JSONObject()
                .put("vitamin-a", "18")
                .put("percentApplied:vitamin-a", true)
                .put("labelPercentages", JSONObject().put("vitamin-a", entry))
        val result = FoodEntry.build(food, changed).food!!
        assertEquals(.000018, result.getDouble("vitamin-a"), 1e-12)
        assertEquals(
            2.0,
            result
                .getJSONObject("labelPercentages")
                .getJSONObject("vitamin-a")
                .getDouble("percent"),
            0.0,
        )
        assertFalse(result.has("percentApplied:vitamin-a"))
    }

    @Test
    fun unknownLegacyFormCanBeSavedAsRecordedPercentWithoutFabricatingAmount() {
        val edited =
            input()
                .put("vitamin-a", "")
                .put(
                    "labelPercentages",
                    JSONObject()
                        .put(
                            "vitamin-a",
                            LabelDailyValues.entry(
                                "vitamin-a",
                                10.0,
                                LabelDailyValues.LEGACY,
                                "unknown",
                            ),
                        ),
                )
        val result = FoodEntry.build(original(), edited).food!!
        assertFalse(result.has("vitamin-a"))
        assertTrue(result.getJSONObject("labelPercentages").has("vitamin-a"))
    }

    @Test
    fun explicitlyTypedAmountReplacesHiddenApiPrecision() {
        val food = input().put("id", "synthetic-api-food").put("protein", 9.123456)
        val result =
            FoodEntry.build(food, JSONObject().put("protein", "9.12").put("edited:protein", true))
                .food!!
        assertEquals(9.12, result.getDouble("protein"), 0.0)
        assertFalse(result.has("edited:protein"))
    }
}
