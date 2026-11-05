package dev.rohit.labelaudit

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real AES/GCM with an ephemeral test key; production uses the Android Keystore. */
class TestCredentialsCipher : CredentialsCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()

    override fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(bytes)
    }

    override fun decrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoodSourcesIntegrationTest {
    private lateinit var v: TrackerViewModel
    private lateinit var app: Application
    private val owner = ViewModelStore()
    private val gates = mutableListOf<CountDownLatch>()

    private fun gate() = CountDownLatch(1).also { gates.add(it) }

    private fun food(name: String = "Synthetic food", kcal: Double = 100.0, grams: Double = 50.0) =
        JSONObject()
            .put("id", "synthetic-food")
            .put("barcode", "000000000001")
            .put("name", name)
            .put("basis", 1)
            .put("unit", "serving")
            .put("servingGrams", grams)
            .put("kcal", kcal)
            .put("protein", 5)
            .put("carbs", 10)
            .put("fat", 2)

    private class Providers(
        val offCall: (String) -> JSONObject,
        val fsCall: (String) -> JSONObject,
    ) : FoodProviders {
        override fun off(code: String) = offCall(code)

        override fun fatSecret(code: String, keys: FatSecretKeys) = fsCall(code)
    }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("nutrition.db")
        FatSecretCredentials(app).clear()
        v = TrackerViewModel(app, SavedStateHandle())
        owner.put("test", v)
        v.credentialStore = FatSecretCredentials(app, TestCredentialsCipher())
    }

    @After
    fun cleanup() {
        gates.forEach { it.countDown() }
        owner.clear()
        FatSecretCredentials(app).clear()
    }

    private fun configure() {
        v.saveFatSecretCredentials("synthetic-client", "synthetic-secret")
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Lookup did not reach expected state", condition())
    }

    private fun waitGate(gate: CountDownLatch) {
        check(gate.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun savedBarcodeIsImmediateAndLateProvidersNeverOverwriteCorrections() {
        configure()
        val slow = gate()
        v.repo.db.save("food", food("My correction", 777.0))
        v.foodProviders =
            Providers(
                {
                    waitGate(slow)
                    food("OFF", 100.0)
                },
                {
                    waitGate(slow)
                    food("FS", 200.0)
                },
            )
        // UPC-A and its zero-prefixed EAN/GTIN representation identify the same saved food.
        v.lookup("0000000000001")
        assertEquals("portion", v.route)
        assertEquals(FoodSource.SAVED, v.selectedFoodSource)
        assertEquals(777.0, v.food.getDouble("kcal"), 0.0)
        slow.countDown()
        await { !v.lookingUpFood }
        assertEquals(FoodSource.SAVED, v.selectedFoodSource)
        assertEquals(777.0, v.repo.db.list("food").single().getDouble("kcal"), 0.0)
    }

    @Test
    fun fasterFatSecretWaitsForOffDefaultButExplicitSelectionWins() {
        configure()
        val slow = gate()
        v.foodProviders =
            Providers(
                {
                    waitGate(slow)
                    food("OFF")
                },
                { food("FS") },
            )
        v.lookup("000000000001")
        await { v.foodChoice(FoodSource.FATSECRET).phase == LookupPhase.READY }
        assertNull(v.selectedFoodSource)
        v.selectFoodSource(FoodSource.FATSECRET)
        assertEquals("FS", v.food.getString("name"))
        slow.countDown()
        await { !v.lookingUpFood }
        assertEquals(FoodSource.FATSECRET, v.selectedFoodSource)
    }

    @Test
    fun offIsDefaultAndFatSecretIsFallbackWhenOffCannotSupplyNutrition() {
        configure()
        v.foodProviders = Providers({ food("OFF") }, { food("FS") })
        v.lookup("000000000001")
        await { !v.lookingUpFood }
        assertEquals(FoodSource.OFF, v.selectedFoodSource)
        v.foodProviders = Providers({ throw FoodNotFound("Not found") }, { food("FS fallback") })
        v.lookup("000000000002")
        await { !v.lookingUpFood }
        assertEquals("FS fallback", v.food.getString("name"))
        v.foodProviders =
            Providers(
                { food("Incomplete OFF").apply { remove("protein") } },
                { food("Complete FS") },
            )
        v.lookup("000000000003")
        await { !v.lookingUpFood }
        assertEquals("Complete FS", v.food.getString("name"))
    }

    @Test
    fun missingKeysAndMissingMacrosStayExplicitAndFoodsStillWorkOffline() {
        v.foodProviders =
            Providers({ throw java.io.IOException() }, { error("FS must not be called") })
        v.repo.db.save("food", food())
        v.lookup("000000000001")
        await { !v.lookingUpFood }
        assertEquals(FoodSource.SAVED, v.selectedFoodSource)
        assertEquals(LookupPhase.NEEDS_KEY, v.foodChoice(FoodSource.FATSECRET).phase)
        v.selectFoodSource(FoodSource.FATSECRET)
        assertTrue(v.promptFatSecretSetup)
        v.foodProviders =
            Providers(
                { food("Incomplete").apply { remove("protein") } },
                { error("FS must not be called") },
            )
        v.lookup("000000000002")
        await { !v.lookingUpFood }
        assertFalse(v.completeFood)
        assertFalse(v.food.has("protein"))
    }

    @Test
    fun switchingPreservesPhysicalAmountAndLeavesEarlierBatchSnapshotsUnchanged() {
        configure()
        val original = food("Saved", 140.0, 38.0)
        v.repo.db.save("food", original)
        v.repo.db.save(
            "batch",
            JSONObject()
                .put("id", "earlier-batch")
                .put("name", "Earlier dish")
                .put("available", 1)
                .put("completed", JSONObject.NULL)
                .put("share", 1)
                .put("portionTracking", true)
                .put(
                    "ingredients",
                    JSONArray().put(JSONObject().put("food", original).put("quantity", 1)),
                ),
        )
        v.foodProviders = Providers({ food("OFF", 140.0, 38.0) }, { food("FS", 130.0, 26.0) })
        v.lookup("000000000001")
        await { !v.lookingUpFood }
        v.field("portion", "canonical", "2", true)
        v.selectFoodSource(FoodSource.FATSECRET)
        assertEquals("g", v.field("portion", "unit"))
        assertEquals(76.0 / 26, v.field("portion", "canonical").toDouble(), 1e-8)
        assertEquals(140.0, v.repo.db.list("food").single().getDouble("kcal"), 0.0)
        v.addFood(v.field("portion", "canonical").toDouble())
        assertEquals(1, v.repo.db.list("food").size)
        assertEquals(130.0, v.repo.db.list("food").single().getDouble("kcal"), 0.0)
        assertEquals(140.0, v.repo.nutrition(v.repo.db.list("batch").single())[0], 0.0)
    }

    @Test
    fun earlierScanAndNavigationCannotReplaceTheCurrentFood() {
        val slow = gate()
        v.foodProviders =
            Providers(
                { code ->
                    if (code.endsWith("1")) {
                        waitGate(slow)
                        food("Old scan")
                    } else food("New scan")
                },
                { error("No key") },
            )
        v.lookup("000000000001")
        v.lookup("000000000002")
        await { v.food.optString("name") == "New scan" }
        slow.countDown()
        await { !v.lookingUpFood }
        assertEquals("New scan", v.food.getString("name"))
        v.home()
        assertEquals("Today", v.route)
    }

    @Test
    fun correctionCreatesSavedAlternativeWithoutChangingProviderResults() {
        v.foodProviders = Providers({ food("OFF") }, { error("No key") })
        v.lookup("000000000001")
        await { !v.lookingUpFood }
        v.editFood()
        v.saveFood(JSONObject(v.food.toString()).put("kcal", 555))
        assertEquals(FoodSource.SAVED, v.selectedFoodSource)
        v.selectFoodSource(FoodSource.OFF)
        assertEquals(100.0, v.food.getDouble("kcal"), 0.0)
        v.selectFoodSource(FoodSource.SAVED)
        assertEquals(555.0, v.food.getDouble("kcal"), 0.0)
        assertEquals(555.0, v.repo.db.list("food").single().getDouble("kcal"), 0.0)
    }

    @Test
    fun backFromSettingsSelectsAResultThatArrivedWhileAway() {
        val slow = gate()
        v.foodProviders =
            Providers(
                {
                    waitGate(slow)
                    food("OFF")
                },
                { error("No key") },
            )
        v.lookup("000000000001")
        v.openFatSecretSettings()
        slow.countDown()
        await { !v.lookingUpFood }
        assertNull(v.selectedFoodSource)
        v.back()
        assertEquals("portion", v.route)
        assertEquals(FoodSource.OFF, v.selectedFoodSource)
    }

    @Test
    fun changingKeysRejectsAnOldInFlightFatSecretResult() {
        configure()
        val slow = gate()
        val count = java.util.concurrent.atomic.AtomicInteger()
        v.foodProviders =
            Providers(
                { food("OFF") },
                {
                    if (count.incrementAndGet() == 1) {
                        waitGate(slow)
                        food("Old key result")
                    } else food("New key result")
                },
            )
        v.lookup("000000000001")
        await { count.get() == 1 }
        v.openFatSecretSettings()
        v.clearFatSecretCredentials()
        v.saveFatSecretCredentials("new-test-client", "new-test-secret")
        await { v.foodChoice(FoodSource.FATSECRET).json?.contains("New key result") == true }
        v.selectFoodSource(FoodSource.FATSECRET)
        slow.countDown()
        await { !v.lookingUpFood }
        assertEquals("New key result", v.food.getString("name"))
    }

    @Test
    fun noSharedConversionResetsPortionInsteadOfAssumingMlEqualsGrams() {
        configure()
        v.repo.db.save("food", food("Saved", grams = 50.0))
        v.foodProviders =
            Providers(
                { food("OFF") },
                {
                    food("FS volume").apply {
                        remove("servingGrams")
                        put("servingMl", 15)
                    }
                },
            )
        v.lookup("000000000001")
        await { !v.lookingUpFood }
        v.field("portion", "canonical", "2", true)
        v.selectFoodSource(FoodSource.FATSECRET)
        assertEquals("", v.field("portion", "canonical"))
        assertTrue(v.message!!.contains("Portion reset"))
        assertFalse(v.food.has("servingGrams"))
    }

    @Test
    fun credentialsAreEncryptedExcludedFromBackupAndSurviveFoodRestore() {
        configure()
        val file = java.io.File(app.noBackupFilesDir, "fatsecret-credentials")
        assertTrue(file.isFile)
        assertFalse(String(file.readBytes()).contains("synthetic-secret"))
        assertEquals("synthetic-secret", v.credentialStore.read()!!.clientSecret)
        val backup = v.repo.db.backup()
        assertFalse(backup.contains("synthetic-secret"))
        assertFalse(backup.contains("synthetic-client"))
        v.repo.db.restore(backup)
        assertTrue(v.credentialStore.configured)
        assertEquals("synthetic-secret", v.credentialStore.read()!!.clientSecret)
        v.clearFatSecretCredentials()
        assertFalse(file.exists())
        assertNull(v.credentialStore.read())
    }
}
