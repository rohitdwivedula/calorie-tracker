package dev.rohit.labelaudit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FatSecretLookupTest {
    private fun serving() =
        JSONObject()
            .put("serving_id", "synthetic-serving")
            .put("serving_description", "1 egg")
            .put("metric_serving_amount", "50")
            .put("metric_serving_unit", "g")
            .put("calories", "70")
            .put("protein", "6")
            .put("carbohydrate", "0")
            .put("fat", "5")
            .put("sodium", "60")
            .put("vitamin_d", "1.5")

    private fun product(s: Any) =
        JSONObject()
            .put("food_id", "synthetic-product")
            .put("food_name", "Test eggs")
            .put("servings", JSONObject().put("serving", s))

    @Test
    fun eggsHaveNamedServingAndMicronutrientUnitsAreNormalized() {
        val f = FatSecretLookup.parse("000000000001", product(serving()))
        assertEquals("1 egg", f.getString("servingLabel"))
        assertEquals(50.0, f.getDouble("servingGrams"), 0.0)
        assertEquals(70.0, f.getDouble("kcal"), 0.0)
        assertEquals(.06, f.getDouble("sodium"), 0.0)
        assertEquals(.0000015, f.getDouble("vitamin-d"), 1e-12)
        assertFalse(f.has("fiber"))
        assertFalse(f.has("iron"))
    }

    @Test
    fun defaultServingIsPreferredAndAmbiguousOuncesNeverBecomeGramsOrMl() {
        val chosen = serving().put("is_default", 1).put("metric_serving_unit", "oz")
        val f =
            FatSecretLookup.parse(
                "000000000001",
                product(JSONArray().put(serving().put("calories", 900)).put(chosen)),
            )
        assertEquals(70.0, f.getDouble("kcal"), 0.0)
        assertFalse(f.has("servingGrams"))
        assertFalse(f.has("servingMl"))
    }

    @Test
    fun missingMacrosAreNotFilledWithZerosAndGtinConversionIsExplicit() {
        val s = serving().apply { remove("protein") }
        val f = FatSecretLookup.parse("000000000001", product(s))
        assertFalse(f.has("protein"))
        assertEquals("0000000000001", FatSecretLookup.gtin13("000000000001"))
        assertEquals("0000000000001", FatSecretLookup.gtin13("00000000000001"))
        assertThrows(FoodNotFound::class.java) { FatSecretLookup.gtin13("10000000000001") }
    }

    @Test
    fun tokenIsReusedAndRemovingCredentialsInvalidatesIt() {
        var tokenCalls = 0
        var barcodeCalls = 0
        val client = FatSecretLookup { _, authorization, body ->
            if (body != null) {
                tokenCalls++
                assertTrue(authorization.startsWith("Basic "))
                JSONObject()
                    .put("access_token", "synthetic-token-$tokenCalls")
                    .put("expires_in", 3600)
            } else {
                barcodeCalls++
                assertEquals("Bearer synthetic-token-$tokenCalls", authorization)
                JSONObject().put("food", product(serving()))
            }
        }
        val keys = FatSecretKeys("synthetic-client", "synthetic-secret")
        client.fetch("000000000001", keys)
        client.fetch("000000000002", keys)
        assertEquals(1, tokenCalls)
        assertEquals(2, barcodeCalls)
        client.clearToken()
        client.fetch("000000000003", keys)
        assertEquals(2, tokenCalls)
        assertFalse(keys.toString().contains("synthetic-secret"))
    }

    @Test
    fun clearingCredentialsDoesNotWaitForNetworkOrAcceptAnOldToken() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val client = FatSecretLookup { _, _, _ ->
            started.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            JSONObject().put("access_token", "synthetic-old-token").put("expires_in", 3600)
        }
        val pending =
            Thread {
                    try {
                        client.fetch("000000000001", FatSecretKeys("old-client", "old-secret"))
                    } catch (e: Throwable) {
                        error.set(e)
                    }
                }
                .apply { start() }
        try {
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            client.clearToken()
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
            pending.join(3000)
        }
        assertTrue(error.get() is IllegalStateException)
        assertFalse(pending.isAlive)
    }
}
