package dev.rohit.labelaudit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FoodSearchParsingTest {
    private fun fsFood(id: String, type: String) =
        JSONObject()
            .put("food_id", id)
            .put("food_type", type)
            .put("food_name", "Synthetic red pepper")
            .put(
                "servings",
                JSONObject()
                    .put(
                        "serving",
                        JSONObject()
                            .put("serving_id", "medium")
                            .put("serving_description", "1 medium")
                            .put("metric_serving_amount", 120)
                            .put("metric_serving_unit", "g")
                            .put("calories", 40)
                            .put("protein", 1)
                            .put("carbohydrate", 8)
                            .put("fat", 0),
                    ),
            )

    @Test
    fun fatSecretV6SupportsSingleAndMultipleResultsWithGenericIngredientsFirst() {
        val root =
            JSONObject()
                .put(
                    "foods_search",
                    JSONObject()
                        .put("total_results", "22")
                        .put(
                            "results",
                            JSONObject()
                                .put(
                                    "food",
                                    JSONArray()
                                        .put(fsFood("brand", "Brand"))
                                        .put(fsFood("generic", "Generic")),
                                ),
                        ),
                )
        val first = FoodSearchParsing.fatSecret(root, 0)
        assertTrue(first.hasMore)
        assertEquals("fs-generic", first.hits.first().key)
        val f = JSONObject(first.hits.first().json)
        assertEquals("1 medium", f.getString("servingLabel"))
        assertEquals(120.0, f.getDouble("servingGrams"), 0.0)
        assertFalse(f.has("barcode"))
        assertFalse(f.has("fiber"))
        val single =
            JSONObject()
                .put(
                    "foods_search",
                    JSONObject()
                        .put("total_results", 1)
                        .put("results", JSONObject().put("food", fsFood("one", "Generic"))),
                )
        assertEquals(1, FoodSearchParsing.fatSecret(single, 0).hits.size)
        assertFalse(FoodSearchParsing.fatSecret(single, 0).hasMore)
    }

    @Test
    fun offPreviewSharesBarcodeServingAndNutrientProjectionWithoutInventingArchiveReferences() {
        val product =
            JSONObject()
                .put("code", "000000000001")
                .put("product_name", "Test peppers")
                .put("serving_size", "1 pepper (120 g)")
                .put("serving_quantity_unit", "ml")
                .put("serving_quantity", 120)
                .put(
                    "nutriments",
                    JSONObject().put("energy-kcal_serving", 40).put("proteins_serving", 1),
                )
        val result =
            FoodSearchParsing.off(
                JSONObject().put("count", 21).put("products", JSONArray().put(product)),
                0,
            )
        val f = JSONObject(result.hits.single().json)
        assertTrue(result.hasMore)
        assertEquals(120.0, f.getDouble("servingGrams"), 0.0)
        assertFalse(f.has("servingMl"))
        assertFalse(f.has("lookupId"))
        assertEquals(40.0, f.getDouble("kcal"), 0.0)
        assertFalse(f.has("fiber"))
        assertFalse(f.has("carbs"))
    }

    @Test
    fun offVolumeBasisIsNotConvertedToGramsAndMalformedEntriesDoNotHideValidResults() {
        val valid =
            JSONObject()
                .put("code", "000000000001")
                .put("product_name", "Test broth")
                .put("nutrition_data_per", "100ml")
                .put("nutriments", JSONObject().put("energy-kcal_100g", 12))
        val root =
            JSONObject()
                .put("count", 2)
                .put("products", JSONArray().put(JSONObject().put("code", "invalid")).put(valid))
        val result = FoodSearchParsing.off(root, 0)
        assertEquals(1, result.hits.size)
        assertFalse(result.hasMore)
        val f = JSONObject(result.hits.single().json)
        assertEquals("ml", f.getString("unit"))
        assertEquals(100.0, f.getDouble("basis"), 0.0)
        assertFalse(f.has("protein"))
    }

    @Test
    fun dedicatedOffSearchBatchesNutritionAndPreservesSearchOrder() {
        val calls = mutableListOf<String>()
        val a = JSONObject().put("code", "000000000001").put("product_name", "First pepper")
        val b = JSONObject().put("code", "000000000002").put("product_name", "Second pepper")
        val result =
            OffFoodSearch.search("red bell pepper", 0) { url, body ->
                calls.add(url)
                if (body != null) {
                    assertEquals("red bell pepper", JSONObject(body).getString("q"))
                    JSONObject().put("count", 2).put("hits", JSONArray().put(a).put(b))
                } else {
                    assertTrue(url.contains("code="))
                    JSONObject()
                        .put(
                            "products",
                            JSONArray()
                                .put(
                                    JSONObject(b.toString())
                                        .put("nutrition_data_per", "100ml")
                                        .put("nutriments", JSONObject().put("energy-kcal_100g", 20))
                                )
                                .put(
                                    JSONObject(a.toString())
                                        .put("serving_size", "1 pepper (120 g)")
                                        .put(
                                            "nutriments",
                                            JSONObject()
                                                .put("energy-kcal_serving", 40)
                                                .put("fiber_serving", 3),
                                        )
                                ),
                        )
                }
            }
        assertEquals(2, calls.size)
        assertEquals("First pepper", JSONObject(result.hits[0].json).getString("name"))
        assertEquals(3.0, JSONObject(result.hits[0].json).getDouble("fiber"), 0.0)
        assertEquals("ml", JSONObject(result.hits[1].json).getString("unit"))
        assertFalse(result.hasMore)
    }

    @Test
    fun unavailableOffDetailBatchKeepsResultsWithUnknownNutrition() {
        val result =
            OffFoodSearch.search("pepper", 0) { _, body ->
                if (body == null) throw java.io.IOException("Details unavailable")
                JSONObject()
                    .put("count", 1)
                    .put(
                        "hits",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("code", "000000000001")
                                    .put("product_name", "Test pepper")
                            ),
                    )
            }
        assertEquals(1, result.hits.size)
        val food = JSONObject(result.hits.single().json)
        assertFalse(food.has("kcal"))
        assertFalse(food.has("protein"))
        assertFalse(food.has("fiber"))
    }

    @Test
    fun offLastPageDoesNotRequestAnotherPageAfterDuplicateBarcodesAreCollapsed() {
        val product = JSONObject().put("code", "000000000001").put("product_name", "Test pepper")
        val last =
            OffFoodSearch.search("pepper", 2) { _, body ->
                if (body != null)
                    JSONObject()
                        .put("count", 23)
                        .put("page_count", 3)
                        .put(
                            "hits",
                            JSONArray()
                                .put(product)
                                .put(product)
                                .put(JSONObject(product.toString()).put("code", "000000000002")),
                        )
                else JSONObject().put("products", JSONArray())
            }
        assertEquals(2, last.hits.size)
        assertFalse(last.hasMore)
    }
}
