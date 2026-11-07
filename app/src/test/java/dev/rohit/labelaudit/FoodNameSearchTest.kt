package dev.rohit.labelaudit

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoodNameSearchTest {
    private lateinit var app: Application
    private lateinit var v: TrackerViewModel
    private val owner = ViewModelStore()
    private val gates = mutableListOf<CountDownLatch>()

    private fun food(name: String, id: String = "generic-pepper") =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("provider", "fatsecret")
            .put("providerType", "Generic")
            .put("providerFoodId", id)
            .put("providerServingId", "medium")
            .put("basis", 1)
            .put("unit", "serving")
            .put("servingLabel", "1 medium")
            .put("servingGrams", 120)
            .put("kcal", 40)
            .put("protein", 1)
            .put("carbs", 8)
            .put("fat", 0)

    private fun hit(name: String, id: String = "generic-pepper") =
        FoodSearchHit(FoodSource.FATSECRET, "fs-$id", food(name, id).toString())

    private open class Providers : FoodProviders {
        override fun off(code: String): JSONObject = throw FoodNotFound("No barcode")

        override fun fatSecret(code: String, keys: FatSecretKeys): JSONObject =
            throw FoodNotFound("No barcode")
    }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("nutrition.db")
        FatSecretCredentials(app).clear()
        v = TrackerViewModel(app, SavedStateHandle())
        v.credentialStore = FatSecretCredentials(app, TestCredentialsCipher())
        owner.put("search", v)
    }

    @After
    fun cleanup() {
        gates.forEach { it.countDown() }
        owner.clear()
        FatSecretCredentials(app).clear()
    }

    private fun configure() {
        v.saveFatSecretCredentials("synthetic-search-client", "synthetic-search-secret")
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Search did not reach expected state", condition())
    }

    @Test
    fun inputFiltersSavedFoodsButOnlySubmitQueriesProvidersAndResultsAreIndependent() {
        configure()
        val slow = CountDownLatch(1).also { gates.add(it) }
        val offCalls = java.util.concurrent.atomic.AtomicInteger()
        val fsCalls = java.util.concurrent.atomic.AtomicInteger()
        v.foodProviders =
            object : Providers() {
                override fun searchOff(query: String, page: Int): FoodSearchPage {
                    offCalls.incrementAndGet()
                    check(slow.await(5, TimeUnit.SECONDS))
                    throw java.io.IOException("Offline")
                }

                override fun searchFatSecret(
                    query: String,
                    page: Int,
                    keys: FatSecretKeys,
                ): FoodSearchPage {
                    fsCalls.incrementAndGet()
                    return FoodSearchPage(listOf(hit("Sweet red peppers")), false)
                }
            }
        v.nameSearch.updateQuery("red bell pepper")
        assertEquals(0, offCalls.get())
        assertEquals(0, fsCalls.get())
        v.nameSearch.search()
        v.nameSearch.search() // No duplicate submission while the same search is running.
        await { v.nameSearch.state(FoodSource.FATSECRET).hits.isNotEmpty() }
        assertEquals(LookupPhase.LOADING, v.nameSearch.state(FoodSource.OFF).phase)
        assertTrue(v.repo.db.list("food").isEmpty())
        slow.countDown()
        await { !v.nameSearch.loading }
        assertEquals(1, offCalls.get())
        assertEquals(1, fsCalls.get())
        assertEquals(LookupPhase.ERROR, v.nameSearch.state(FoodSource.OFF).phase)
        assertEquals(1, v.nameSearch.state(FoodSource.FATSECRET).hits.size)
    }

    @Test
    fun changedQueryDiscardsSlowResponsesAndMissingKeysKeepOffSearchAvailable() {
        val slow = CountDownLatch(1).also { gates.add(it) }
        v.foodProviders =
            object : Providers() {
                override fun searchOff(query: String, page: Int): FoodSearchPage {
                    if (query == "peppers") check(slow.await(5, TimeUnit.SECONDS))
                    return FoodSearchPage(listOf(hit(query)), false)
                }

                override fun searchFatSecret(
                    query: String,
                    page: Int,
                    keys: FatSecretKeys,
                ): FoodSearchPage = error("No key: FS must not be called")
            }
        v.nameSearch.updateQuery("peppers")
        v.nameSearch.search()
        v.nameSearch.updateQuery("carrots")
        v.nameSearch.search()
        await { !v.nameSearch.loading }
        slow.countDown()
        assertTrue(v.nameSearch.state(FoodSource.OFF).hits.single().json.contains("carrots"))
        assertEquals(LookupPhase.NEEDS_KEY, v.nameSearch.state(FoodSource.FATSECRET).phase)
        assertEquals("carrots", v.nameSearch.query)
    }

    @Test
    fun paginationDeduplicatesAndRetriesTheFailedPageWithoutDroppingResults() {
        var fail = true
        val calls = mutableListOf<Int>()
        v.foodProviders =
            object : Providers() {
                override fun searchOff(query: String, page: Int): FoodSearchPage {
                    synchronized(calls) { calls.add(page) }
                    if (page == 1 && fail) {
                        fail = false
                        throw java.io.IOException()
                    }
                    return if (page == 0) FoodSearchPage(listOf(hit("First", "first")), true)
                    else
                        FoodSearchPage(
                            listOf(hit("First", "first"), hit("Second", "second")),
                            false,
                        )
                }
            }
        v.nameSearch.updateQuery("peppers")
        v.nameSearch.search()
        await { !v.nameSearch.loading }
        v.nameSearch.more()
        await { !v.nameSearch.loading }
        assertEquals(1, v.nameSearch.state(FoodSource.OFF).hits.size)
        assertEquals(LookupPhase.ERROR, v.nameSearch.state(FoodSource.OFF).phase)
        v.nameSearch.retry(FoodSource.OFF)
        await { !v.nameSearch.loading }
        assertEquals(listOf(0, 1, 1), calls)
        assertEquals(2, v.nameSearch.state(FoodSource.OFF).hits.size)
        assertFalse(v.nameSearch.state(FoodSource.OFF).hasMore)
    }

    @Test
    fun genericIngredientWorksInDishOfflineSavedReuseAndQuickLogWithoutDuplicatingFoods() {
        v.beginCook()
        v.draftName("Test dish")
        v.mode = "dish"
        v.nameSearch.updateQuery("red bell pepper")
        v.chooseSearchFood(hit("Sweet red peppers"))
        assertEquals("portion", v.route)
        assertEquals(FoodSource.FATSECRET, v.selectedFoodSource)
        assertFalse(v.food.has("barcode"))
        assertFalse(v.food.has("fiber"))
        v.addFood(2.0)
        assertEquals("cook", v.route)
        assertEquals(80.0, v.repo.nutrition(v.draft)[0], 0.0)
        val local = v.repo.db.list("food").single()
        assertTrue(FoodSearchParsing.savedMatches(local, "red bell pepper"))
        assertTrue(FoodSearchParsing.savedMatches(local, "RED peppers"))
        v.saveCook()
        // Provider ID is stable across name searches; this is an explicit new selection,
        // while the earlier dish keeps its original ingredient snapshot.
        v.mode = "quick"
        v.chooseSearchFood(hit("Sweet red peppers"))
        v.addFood(1.0)
        assertEquals(1, v.repo.db.list("food").size)
        assertEquals(1, v.repo.db.list("direct").size)
        assertEquals(80.0, v.repo.nutrition(v.repo.db.list("batch").single())[0], 0.0)
        val exported = v.repo.db.backup()
        v.repo.db.restore(exported)
        assertTrue(FoodSearchParsing.savedMatches(v.repo.db.list("food").single(), "bell pepper"))
    }

    @Test
    fun explicitOffSearchResultIsNotOverriddenBySavedDefaultAndArchivesFullLookup() {
        val local = food("My saved correction").put("barcode", "000000000001").put("kcal", 900)
        v.repo.db.save("food", local)
        v.foodProviders =
            object : Providers() {
                override fun off(code: String) =
                    food("Full OFF details").put("barcode", code).put("provider", "openfoodfacts")
            }
        v.mode = "quick"
        val preview = food("OFF preview").put("barcode", "000000000001")
        v.nameSearch.updateQuery("pepper")
        v.chooseSearchFood(FoodSearchHit(FoodSource.OFF, "off-000000000001", preview.toString()))
        await { !v.lookingUpFood }
        assertEquals("Full OFF details", v.food.getString("name"))
        assertEquals(FoodSource.OFF, v.selectedFoodSource)
        assertEquals("pepper", v.food.getString("searchAlias"))
        assertEquals(900.0, v.repo.db.list("food").single().getDouble("kcal"), 0.0)
    }

    @Test
    fun removingCredentialsClearsFatSecretResultsAndRetainsOtherProviderResults() {
        configure()
        v.foodProviders =
            object : Providers() {
                override fun searchOff(query: String, page: Int) =
                    FoodSearchPage(listOf(hit("OFF")), false)

                override fun searchFatSecret(query: String, page: Int, keys: FatSecretKeys) =
                    FoodSearchPage(listOf(hit("FS")), false)
            }
        v.nameSearch.updateQuery("pepper")
        v.nameSearch.search()
        await { !v.nameSearch.loading }
        v.clearFatSecretCredentials()
        assertEquals(LookupPhase.NEEDS_KEY, v.nameSearch.state(FoodSource.FATSECRET).phase)
        assertTrue(v.nameSearch.state(FoodSource.FATSECRET).hits.isEmpty())
        assertEquals(1, v.nameSearch.state(FoodSource.OFF).hits.size)
    }
}
