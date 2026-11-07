package dev.rohit.labelaudit

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LabelJsonTest {
    private fun example() =
        JSONObject(
            """{"schemaVersion":1,"name":"Synthetic beans","basis":{"quantity":1,"unit":"serving","servingGrams":125},"nutrients":{"kcal":{"amount":101,"unit":"kcal"},"protein":{"amount":7,"unit":"g"},"carbs":{"amount":15,"unit":"g"},"fat":{"amount":2,"unit":"g"},"sodium":{"amount":0.25,"unit":"g"}}}"""
        )

    @Test
    fun convertsExplicitMassUnitsAndKeepsServingConversion() {
        val fields = LabelJson.parse(example().toString())
        assertEquals("125", fields.getString("servingGrams"))
        assertEquals("250", fields.getString("sodium"))
        assertEquals("", fields.getString("servingMl"))
        assertEquals("", fields.getString("fiber"))
        val food = FoodEntry.build(JSONObject().put("id", "synthetic-json"), fields).food!!
        assertEquals(.25, food.getDouble("sodium"), 0.0)
        assertFalse(food.has("fiber"))
    }

    @Test
    fun absentAndNullNutrientsStayUnknownAndExplicitZeroIsZero() {
        val json = example()
        json
            .getJSONObject("nutrients")
            .put("carbs", JSONObject.NULL)
            .put("fat", JSONObject().put("amount", 0).put("unit", "g"))
        val fields = LabelJson.parse(json.toString())
        assertEquals("", fields.getString("carbs"))
        assertEquals("0", fields.getString("fat"))
        val result = FoodEntry.build(JSONObject().put("id", "synthetic-json"), fields)
        assertTrue(result.errors.containsKey("carbs"))
    }

    @Test
    fun convertsPercentagesAndRetainsUnresolvedLegacyVitaminA() {
        val json = example()
        json
            .getJSONObject("nutrients")
            .put("vitamin-a", JSONObject().put("percentDV", 2).put("standard", "us-legacy"))
            .put("calcium", JSONObject().put("percentDV", 10).put("standard", "us-current"))
        val fields = LabelJson.parse(json.toString())
        assertEquals("", fields.getString("vitamin-a"))
        assertEquals("130", fields.getString("calcium"))
        assertEquals(
            2,
            fields.getJSONObject("labelPercentages").getJSONObject("vitamin-a").getInt("percent"),
        )
    }

    @Test
    fun acceptsCodeFencesButRejectsExtraTextOrMultipleObjects() {
        assertEquals(
            "Synthetic beans",
            LabelJson.parse("```json\n${example()}\n```").getString("name"),
        )
        for (text in
            listOf(
                "Here you go: ${example()}",
                "${example()} {}",
                "[]",
                "{",
                "```json\n{}",
                "x".repeat(100001),
            )) assertThrows(Exception::class.java) { LabelJson.parse(text) }
    }

    @Test
    fun rejectsUnknownFieldsUnitsVersionsNegativeAndStringNumbers() {
        for (mutate in
            listOf<(JSONObject) -> Unit>(
                { it.put("schemaVersion", 2) },
                { it.put("id", "must-not-import") },
                { it.getJSONObject("basis").put("quantity", 0) },
                { it.getJSONObject("basis").put("unit", "cup") },
                { it.getJSONObject("nutrients").put("typo", JSONObject()) },
                { it.getJSONObject("nutrients").getJSONObject("protein").put("amount", -1) },
                { it.getJSONObject("nutrients").getJSONObject("protein").put("amount", "7") },
                { it.getJSONObject("nutrients").getJSONObject("protein").put("unit", "IU") },
                { it.getJSONObject("nutrients").getJSONObject("protein").put("percentDV", 10) },
                { it.getJSONObject("nutrients").put("iron", JSONObject().put("percentDV", 5)) },
            )) {
            val json = example()
            mutate(json)
            assertThrows(Exception::class.java) { LabelJson.parse(json.toString()) }
        }
    }

    @Test
    fun per100mlBasisDoesNotInventGramConversionOrScaleValuesAgain() {
        val json = example().put("name", JSONObject.NULL)
        json.put("basis", JSONObject().put("quantity", 100).put("unit", "ml"))
        val fields = LabelJson.parse(json.toString())
        assertFalse(fields.has("name"))
        assertEquals("100", fields.getString("basis"))
        assertEquals("101", fields.getString("kcal"))
        assertEquals("", fields.getString("servingGrams"))
    }
}
