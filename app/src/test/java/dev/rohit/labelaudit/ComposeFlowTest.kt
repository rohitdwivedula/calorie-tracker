package dev.rohit.labelaudit

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        ui.waitForIdle()
        ui.runOnIdle {
            val root =
                android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull()
                    ?: ui.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            val folder = File("build/outputs/compose-screenshots")
            folder.mkdirs()
            File(folder, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun homeUsesComposeAndCookingRetainsDraftAcrossBack() {
        ui.onNodeWithText("New dish").assertIsDisplayed()
        screenshot("today")
        ui.onNodeWithText("New dish").performClick()
        ui.onNodeWithText("Dish name").performTextInput("Test pasta")
        ui.onNodeWithText("Done cooking").assertIsNotEnabled()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Test pasta").assertExists()
        screenshot("cooking")
        ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("New dish").assertIsDisplayed()
    }

    @Test
    fun systemBackKeepsTwoIngredientDishAsResumableDraft() {
        ui.onNodeWithText("New dish").performClick()
        ui.onNodeWithText("Dish name").performTextInput("Draft pasta")
        for (name in listOf("Test noodles", "Test peppers")) {
            ui.runOnIdle {
                val v =
                    androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
                v.chooseFood(
                    org.json
                        .JSONObject()
                        .put("id", name)
                        .put("name", name)
                        .put("basis", 100)
                        .put("unit", "g")
                        .put("kcal", 100)
                        .put("protein", 5)
                        .put("carbs", 10)
                        .put("fat", 2),
                    "dish",
                )
            }
            ui.onNodeWithText("Add to dish").performClick()
        }
        ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("Drafts").performScrollTo().assertIsDisplayed()
        screenshot("today-draft")
        ui.onNodeWithText("Draft pasta").performClick()
        ui.onNodeWithText("Test noodles").assertExists()
        ui.onNodeWithText("Test peppers").assertExists()
        ui.onNodeWithText("Done cooking").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals(1, v.repo.db.list("batch").size)
            org.junit.Assert.assertTrue(v.repo.db.list("draft").isEmpty())
        }
        ui.onNodeWithText("Drafts").assertDoesNotExist()
    }

    @Test
    fun sourceIconsAndDeviceCredentialsWorkForQuickLogAndDishIngredients() {
        fun sourceFood(name: String, calories: Int) =
            org.json
                .JSONObject()
                .put("id", "source-ui-food")
                .put("barcode", "000000000001")
                .put("name", name)
                .put("basis", 1)
                .put("unit", "serving")
                .put("servingGrams", 50)
                .put("kcal", calories)
                .put("protein", 5)
                .put("carbs", 10)
                .put("fat", 2)
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            v.credentialStore = FatSecretCredentials(ui.activity, TestCredentialsCipher())
            v.foodProviders =
                object : FoodProviders {
                    override fun off(code: String) = sourceFood("OFF test food", 100)

                    override fun fatSecret(code: String, keys: FatSecretKeys) =
                        sourceFood("FatSecret test food", 120)
                }
            v.repo.db.save("food", sourceFood("My saved correction", 140))
            v.mode = "quick"
            v.lookup("000000000001")
        }
        ui.onNodeWithText("My saved correction").assertIsDisplayed()
        ui.onNodeWithContentDescription("Saved source").assertIsDisplayed()
        ui.onNodeWithContentDescription("Open Food Facts source").assertIsDisplayed()
        ui.onNodeWithContentDescription("FatSecret source").performClick()
        ui.onNodeWithText("Connect FatSecret").assertIsDisplayed()
        ui.onNodeWithText("Open settings").performClick()
        ui.onNodeWithText("Client ID").performTextInput("ui-synthetic-client")
        ui.onNodeWithText("Client secret").performTextInput("ui-synthetic-secret")
        ui.onNodeWithText("Save credentials").performClick()
        ui.waitUntil(5000) {
            androidx.lifecycle
                .ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
                .foodChoice(FoodSource.FATSECRET)
                .phase == LookupPhase.READY
        }
        ui.onNodeWithText("My saved correction").assertExists()
        screenshot("food-source-picker")
        ui.onNodeWithContentDescription("FatSecret source").performClick()
        ui.onNodeWithText("FatSecret test food").assertExists()
        ui.onNodeWithText("Quantity").performTextReplacement("2")
        ui.onNodeWithText("Log food").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals(
                240.0,
                v.repo.nutrition(v.repo.db.list("direct").single())[0],
                0.0,
            )
            org.junit.Assert.assertFalse(v.repo.db.backup().contains("ui-synthetic-secret"))
            org.junit.Assert.assertFalse(v.formJson.contains("ui-synthetic-secret"))
        }
        ui.onNodeWithText("New dish").performClick()
        ui.onNodeWithText("Dish name").performTextInput("Source test dish")
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            v.mode = "dish"
            v.lookup("000000000001")
        }
        ui.waitUntil(5000) {
            androidx.lifecycle
                .ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
                .foodChoice(FoodSource.OFF)
                .phase == LookupPhase.READY
        }
        ui.onNodeWithText("FatSecret test food").assertExists()
        ui.onNodeWithContentDescription("Open Food Facts source").performClick()
        ui.onNodeWithText("OFF test food").assertExists()
        ui.onNodeWithText("Add to dish").performClick()
        ui.onNodeWithText("OFF test food").assertExists()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            v.credentialStore.clear()
        }
    }

    @Test
    fun nameSearchTilesShowBothProvidersAndOpenTheSharedPortionFlow() {
        fun food(name: String, calories: Int, fiber: Double? = null) =
            org.json
                .JSONObject()
                .put("id", "search-ui-$name")
                .put("name", name)
                .put("basis", 1)
                .put("unit", "serving")
                .put("servingLabel", "1 medium")
                .put("servingGrams", 120)
                .put("kcal", calories)
                .put("protein", 1)
                .put("carbs", 8)
                .put("fat", 0)
                .apply { if (fiber != null) put("fiber", fiber) }
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            v.credentialStore = FatSecretCredentials(ui.activity, TestCredentialsCipher())
            v.saveFatSecretCredentials("synthetic-search-client", "synthetic-search-secret")
            v.repo.db.save("food", food("Saved peppers", 45))
            v.foodProviders =
                object : FoodProviders {
                    override fun off(code: String) =
                        food("Packaged red peppers", 31, 2.0).put("barcode", code)

                    override fun fatSecret(code: String, keys: FatSecretKeys) =
                        food("Unused barcode result", 40)

                    override fun searchOff(query: String, page: Int) =
                        FoodSearchPage(
                            listOf(
                                FoodSearchHit(
                                    FoodSource.OFF,
                                    "off-search-peppers",
                                    food("Packaged red peppers", 31, 2.0)
                                        .put("barcode", "000000000001")
                                        .toString(),
                                )
                            ),
                            false,
                        )

                    override fun searchFatSecret(query: String, page: Int, keys: FatSecretKeys) =
                        FoodSearchPage(
                            listOf(
                                FoodSearchHit(
                                    FoodSource.FATSECRET,
                                    "fs-search-peppers",
                                    food("Red bell pepper, raw", 40, 3.0)
                                        .put("provider", "fatsecret")
                                        .put("providerType", "Generic")
                                        .put("providerFoodId", "synthetic-pepper")
                                        .toString(),
                                )
                            ),
                            false,
                        )
                }
            v.refresh()
            v.mode = "quick"
            v.go("foods")
        }
        ui.onNode(hasSetTextAction()).performTextInput("pepper")
        ui.onNodeWithText("Saved peppers").assertExists()
        ui.onNodeWithText("Fiber —").assertExists()
        ui.onNodeWithContentDescription("Search online").performClick()
        ui.waitUntil(5000) {
            val search =
                androidx.lifecycle
                    .ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
                    .nameSearch
            search.submitted.isNotBlank() && !search.loading
        }
        ui.onNodeWithText("Red bell pepper, raw").assertExists()
        ui.onNodeWithText("Packaged red peppers").assertExists()
        ui.onNodeWithText("40 kcal").assertExists()
        ui.onNodeWithText("Fiber 3 g").assertExists()
        ui.onAllNodesWithText("1 medium · 120 g").assertCountEquals(3)
        ui.onAllNodesWithText("Carbs 8 g").assertCountEquals(3)
        ui.onAllNodesWithText("Fat 0 g").assertCountEquals(3)
        screenshot("food-name-search")
        ui.onNodeWithText("Red bell pepper, raw").performClick()
        ui.onNodeWithText("Quantity").performTextReplacement("2")
        ui.onNodeWithText("Log food").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals(
                80.0,
                v.repo.nutrition(v.repo.db.list("direct").single())[0],
                0.0,
            )
            org.junit.Assert.assertEquals(2, v.repo.db.list("food").size)
            v.credentialStore.clear()
        }
    }

    @Config(qualifiers = "w320dp-h891dp-xhdpi")
    @Test
    fun barcodeCanBeCorrectedOnMissingAndSuccessfulResultsWithoutLosingDish() {
        ui.onNodeWithText("New dish").performClick()
        ui.onNodeWithText("Dish name").performTextInput("Barcode correction dish")
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            fun food(code: String, name: String) =
                org.json
                    .JSONObject()
                    .put("id", "synthetic-$code")
                    .put("barcode", code)
                    .put("name", name)
                    .put("basis", 1)
                    .put("unit", "serving")
                    .put("kcal", 90)
                    .put("protein", 5)
                    .put("carbs", 10)
                    .put("fat", 2)
            v.repo.db.save("food", food("000000000002", "First matched food"))
            v.repo.db.save("food", food("000000000003", "Corrected matched food"))
            v.foodProviders =
                object : FoodProviders {
                    override fun off(code: String): org.json.JSONObject =
                        throw FoodNotFound("missing")

                    override fun fatSecret(code: String, keys: FatSecretKeys): org.json.JSONObject =
                        throw FoodNotFound("missing")
                }
            v.mode = "dish"
            v.lookup("000000000001")
        }
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("No nutrition found").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("000000000001").assertIsDisplayed()
        screenshot("barcode-not-found")
        ui.onNodeWithContentDescription("Edit barcode").performClick()
        ui.onNodeWithText("Barcode number").assertTextContains("000000000001")
        ui.onNodeWithText("Barcode number").performTextReplacement("123")
        ui.onNodeWithText("Look up").performClick()
        ui.onNodeWithText("Enter 8, 12, 13 or 14 digits").assertExists()
        ui.onNodeWithText("Barcode number").performTextReplacement("000000000002")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Barcode number").assertTextContains("000000000002")
        ui.onNodeWithText("Look up").performClick()
        ui.onNodeWithText("First matched food").assertExists()
        ui.onNodeWithText("000000000002").assertIsDisplayed()
        ui.onNodeWithText("Quantity").performTextReplacement("3")
        ui.onNodeWithContentDescription("Edit barcode").performClick()
        ui.onNodeWithText("Barcode number").performTextReplacement("000000000003")
        ui.onNodeWithText("Cancel").performClick()
        ui.onNodeWithText("Quantity").assertTextContains("3")
        ui.onNodeWithText("000000000002").assertIsDisplayed()
        ui.onNodeWithContentDescription("Edit barcode").performClick()
        ui.onNodeWithText("Barcode number").performTextReplacement("000000000003")
        ui.onNodeWithText("Look up").performClick()
        ui.onNodeWithText("Corrected matched food").assertExists()
        ui.onNodeWithText("Quantity").assertTextContains("1")
        screenshot("barcode-portion")
        ui.onNodeWithText("Add to dish").performClick()
        ui.onNodeWithText("Barcode correction dish").assertExists()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val ingredients = v.draft.getJSONArray("ingredients")
            org.junit.Assert.assertEquals(1, ingredients.length())
            org.junit.Assert.assertEquals(
                "000000000003",
                ingredients.getJSONObject(0).getJSONObject("food").getString("barcode"),
            )
            org.junit.Assert.assertEquals(
                "First matched food",
                v.repo.db
                    .list("food")
                    .first { it.getString("barcode") == "000000000002" }
                    .getString("name"),
            )
        }
    }

    @Test
    fun manualIngredientAcceptsUnlistedCarbs() {
        ui.onNodeWithText("New dish").performClick()
        ui.onNodeWithText("Dish name").performTextInput("Manual entry dish")
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            v.mode = "dish"
            v.manualFood()
        }
        ui.onNodeWithText("Food name").performTextInput("Synthetic ingredient")
        ui.onNodeWithText("Calories (kcal)").performTextInput("99")
        ui.onNodeWithText("Protein (g)").performTextInput("9")
        ui.onNodeWithText("Fat (g)").performScrollTo().performTextInput("2.5")
        ui.onNodeWithText("Continue").performClick()
        ui.onNodeWithContentDescription("Edit barcode").assertDoesNotExist()
        ui.onNodeWithText("Quantity").performTextReplacement("2")
        ui.onNodeWithText("Add to dish").performClick()
        ui.onNodeWithText("Synthetic ingredient").assertExists()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals(198.0, v.repo.nutrition(v.draft)[0], 0.0)
            val food = v.draft.getJSONArray("ingredients").getJSONObject(0).getJSONObject("food")
            org.junit.Assert.assertFalse(food.has("carbs"))
            org.junit.Assert.assertFalse(food.has("fiber"))
        }
    }

    @Config(qualifiers = "w320dp-h891dp-xhdpi")
    @Test
    fun manualPercentEntrySupportsOlderLabelsAndStoresSourceAlongsideAmounts() {
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            v.mode = "quick"
            v.manualFood()
        }
        ui.onNodeWithText("Food name").performTextInput("Percent entry example")
        ui.onNodeWithText("Calories (kcal)").performTextInput("99")
        ui.onNodeWithText("Protein (g)").performTextInput("9")
        ui.onNodeWithText("Carbs (g)").performTextInput("0")
        ui.onNodeWithText("Fat (g)").performScrollTo().performTextInput("2.5")
        ui.onNodeWithText("Serving size, vitamins & minerals").performScrollTo().performClick()
        ui.onNodeWithText("Vitamin A (µg, optional)").performScrollTo()
        ui.onAllNodesWithText("% DV")[0].performClick()
        ui.onNodeWithText("Percent Daily Value").performTextInput("10")
        ui.onNodeWithText("Older US").performClick()
        ui.onNodeWithText("Apply").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals("", v.field("food", "vitamin-a"))
        }
        ui.onAllNodesWithText("% DV")[0].performClick()
        ui.onNodeWithText("Unknown or mixed").performClick()
        ui.onNodeWithText("Retinol (preformed vitamin A)").performClick()
        ui.onNodeWithText("≈ 150 µg per nutrition basis").assertExists()
        screenshot("label-percent-entry")
        ui.onNodeWithText("Apply").performClick()
        ui.onNodeWithText("Calcium (mg, optional)").performScrollTo()
        ui.onAllNodesWithText("% DV")[11].performClick()
        ui.onNodeWithText("Percent Daily Value").performTextInput("10")
        ui.onNodeWithText("≈ 100 mg per nutrition basis").assertExists()
        ui.onNodeWithText("Apply").performClick()
        ui.onNodeWithText("Continue").performClick()
        ui.onNodeWithText("Log food").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val food = v.repo.db.list("food").single()
            org.junit.Assert.assertEquals(.00015, food.getDouble("vitamin-a"), 1e-12)
            org.junit.Assert.assertEquals(.1, food.getDouble("calcium"), 1e-12)
            org.junit.Assert.assertEquals(
                "retinol",
                food.getJSONObject("labelPercentages").getJSONObject("vitamin-a").getString("form"),
            )
        }
    }

    @Config(qualifiers = "w320dp-h891dp-xhdpi")
    @Test
    fun openingJsonPreservesClipboardAndCopyPromptIsExplicit() {
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            v.mode = "quick"
            v.manualFood()
        }
        ui.onNodeWithText("Food name").performTextInput("Keep my name")
        val clip = ui.activity.getSystemService(android.content.ClipboardManager::class.java)
        ui.runOnIdle {
            clip.setPrimaryClip(android.content.ClipData.newPlainText("label", "existing JSON"))
        }
        ui.onNodeWithText("JSON").performClick()
        ui.onNodeWithText("Import label JSON").assertExists()
        ui.runOnIdle {
            org.junit.Assert.assertEquals(
                "existing JSON",
                clip.primaryClip!!.getItemAt(0).text.toString(),
            )
        }
        ui.onNodeWithText("Copy prompt").performClick()
        ui.runOnIdle {
            val clipboard =
                ui.activity.getSystemService(android.content.ClipboardManager::class.java)
            org.junit.Assert.assertTrue(
                clipboard.primaryClip!!.getItemAt(0).text.toString().contains("schemaVersion")
            )
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("invalid example", "{}"))
        }
        ui.onNodeWithText("Paste JSON").performClick()
        ui.onNodeWithText("Fill form").performClick()
        ui.onNodeWithText("Use schemaVersion 1 from the copied prompt").assertExists()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertTrue(v.repo.db.list("food").isEmpty())
            ui.activity
                .getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "synthetic label",
                        """{"schemaVersion":1,"name":null,"basis":{"quantity":1,"unit":"serving","servingGrams":80},"nutrients":{"kcal":{"amount":99,"unit":"kcal"},"protein":{"amount":9,"unit":"g"},"carbs":{"amount":0,"unit":"g"},"fat":{"amount":2.5,"unit":"g"},"iron":{"percentDV":10,"standard":"us-current"}}}""",
                    )
                )
        }
        ui.onNodeWithText("Paste JSON").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Import label JSON").assertExists()
        screenshot("label-json-import")
        ui.onNodeWithText("Fill form").performClick()
        ui.onNodeWithText("Import label JSON").assertDoesNotExist()
        ui.onNodeWithText("Keep my name").assertExists()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertTrue(v.repo.db.list("food").isEmpty())
        }
        ui.onNodeWithText("Continue").performClick()
        ui.onNodeWithText("Log food").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val food = v.repo.db.list("food").single()
            org.junit.Assert.assertEquals("Keep my name", food.getString("name"))
            org.junit.Assert.assertEquals(.0018, food.getDouble("iron"), 1e-12)
            org.junit.Assert.assertFalse(food.has("fiber"))
            org.junit.Assert.assertEquals(80.0, food.getDouble("servingGrams"), 0.0)
        }
    }

    @Test
    fun caloriesCanBeAddedAndEditedInsideDishWithoutLoggingImmediateIntake() {
        ui.onNodeWithText("New dish").performClick()
        ui.onNodeWithText("Dish name").performTextInput("Synthetic seasoning dish")
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            v.mode = "dish"
            v.ingredientIndex = -1
            v.beginCalories()
        }
        ui.onNodeWithText("When · now").assertDoesNotExist()
        ui.onNodeWithText("Calories (kcal)").performTextInput("100")
        ui.onNodeWithText("Name (optional)").performTextInput("Seasoning estimate")
        ui.onNodeWithText("Add to dish").performClick()
        ui.onNodeWithText("100 kcal · macros unknown").assertExists()
        ui.onNodeWithText("Seasoning estimate").performClick()
        ui.onNodeWithText("Calories (kcal)").assertTextContains("100")
        ui.onNodeWithText("Calories (kcal)").performTextReplacement("120")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Calories (kcal)").assertTextContains("120")
        ui.onNodeWithText("Add to dish").performClick()
        ui.onNodeWithText("120 kcal · macros unknown").assertExists()
        screenshot("dish-calorie-ingredient")
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertTrue(v.repo.db.list("calorie").isEmpty())
            org.junit.Assert.assertTrue(v.repo.db.list("direct").isEmpty())
            org.junit.Assert.assertTrue(v.repo.db.list("food").isEmpty())
            org.junit.Assert.assertEquals(1, v.draft.getJSONArray("ingredients").length())
            val food = v.draft.getJSONArray("ingredients").getJSONObject(0).getJSONObject("food")
            org.junit.Assert.assertFalse(food.has("protein"))
            org.junit.Assert.assertTrue(food.getBoolean("calorieOnly"))
            org.junit.Assert.assertEquals(120.0, v.repo.nutrition(v.draft)[0], 0.0)
            org.junit.Assert.assertTrue(v.repo.batchNutrients(v.draft, 1.0).missing[1])
        }
        ui.onNodeWithText("Done cooking").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val backup = v.repo.db.backup()
            v.repo.db.restore(backup)
            val batch = v.repo.db.list("batch").single()
            org.junit.Assert.assertEquals(120.0, v.repo.nutrition(batch)[0], 0.0)
            org.junit.Assert.assertTrue(v.repo.batchNutrients(batch, 1.0).missing[1])
        }
    }

    @Test
    fun searchStartsBlankOnEachVisitButKeepsTypingThroughRecreation() {
        ui.onNodeWithText("Foods").performClick()
        ui.onNode(hasSetTextAction()).performTextInput("old search")
        ui.onNodeWithText("Saved").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNode(hasSetTextAction()).assertTextContains("old search")
        ui.onNodeWithText("Today", useUnmergedTree = true).performClick()
        ui.onNodeWithText("Foods").performClick()
        ui.onNode(hasSetTextAction()).assertTextEquals("", "Search foods")
        ui.onNodeWithText("All").assertIsSelected()
        ui.onNode(hasSetTextAction()).performTextInput("another search")
        ui.onNodeWithContentDescription("Settings").performClick()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNode(hasSetTextAction()).assertTextEquals("", "Search foods")
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals("", v.nameSearch.query)
            org.junit.Assert.assertEquals("", v.nameSearch.submitted)
            org.junit.Assert.assertTrue(v.nameSearch.states.isEmpty())
        }
    }

    @Test
    fun historyAndDirectFoodDetailsShowServingsFromSavedSnapshotAndShare() {
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val f =
                org.json
                    .JSONObject()
                    .put("id", "synthetic-portions")
                    .put("name", "Synthetic patties")
                    .put("unit", "g")
                    .put("basis", 60)
                    .put("servingGrams", 60)
                    .put("kcal", 120)
                    .put("protein", 1)
                    .put("carbs", 20)
                    .put("fat", 4)
            v.mode = "quick"
            v.chooseFood(f)
            v.addFood(120.0)
            // A later library correction must not rewrite the logged portion conversion.
            v.repo.db.save("food", org.json.JSONObject(f.toString()).put("servingGrams", 30))
            v.go("history")
        }
        ui.onNodeWithText("2 servings · 120 g").assertIsDisplayed()
        screenshot("history-quantities")
        ui.onNodeWithText("Synthetic patties").performClick()
        ui.onNodeWithText("2 servings · 120 g").assertIsDisplayed()
        screenshot("direct-food-quantity")
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val direct = v.repo.db.list("direct").single().put("share", .5)
            v.repo.db.save("direct", direct)
            v.refresh()
        }
        ui.onNodeWithText("1 serving · 60 g").assertExists()
    }

    @Test
    fun cookingTotalsShowAllFourMacrosAndTrackUnknownsAndPortionsAcrossScreens() {
        ui.onNodeWithText("New dish").performClick()
        ui.onNodeWithText("Dish name").performTextInput("Synthetic macro dish")
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val f =
                org.json
                    .JSONObject()
                    .put("id", "synthetic-macros")
                    .put("name", "Synthetic ingredient")
                    .put("unit", "g")
                    .put("basis", 100)
                    .put("kcal", 150)
                    .put("protein", 10)
                    .put("carbs", 20)
                    .put("fat", 5)
                    .put("fiber", 2)
            v.chooseFood(f, "dish")
            v.addFood(200.0)
            v.beginCalories()
            v.saveCalories("Approximate addition", 100.0)
        }
        for (value in listOf("≥20 g", "≥40 g", "≥10 g", "≥4 g")) ui.onNodeWithText(value)
            .performScrollTo()
            .assertIsDisplayed()
        ui.onNodeWithText("400 kcal").performScrollTo().assertIsDisplayed()
        screenshot("cooking-macro-totals")
        ui.onNodeWithContentDescription("Remove Approximate addition")
            .performScrollTo()
            .performClick()
        for (value in listOf("20 g", "40 g", "10 g", "4 g")) ui.onNodeWithText(value)
            .performScrollTo()
            .assertIsDisplayed()
        ui.onNodeWithText("Done cooking").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            v.recordPortion(v.repo.db.list("batch").single().getString("id"), .5)
        }
        for (label in listOf("Protein", "Fiber", "Carbs", "Fat")) ui.onNodeWithText(label)
            .assertExists()
        screenshot("today-all-macros")
        ui.onNodeWithText("History").performClick()
        for (label in listOf("Protein 10 g", "Fiber 2 g", "Carbs 20 g", "Fat 5 g")) ui
            .onNodeWithText(label)
            .assertIsDisplayed()
        screenshot("history-all-macros")
        ui.onNodeWithText("Synthetic macro dish").performClick()
        for (label in listOf("Protein", "Fiber", "Carbs", "Fat")) ui.onNodeWithText(label)
            .assertExists()
    }

    @Test
    fun calorieLogAndHistoryUseMaterialScreens() {
        ui.runOnIdle {
            val owner =
                androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            owner.beginCalories()
        }
        ui.onNodeWithText("Calories (kcal)").performTextInput("120")
        ui.onNodeWithText("Name (optional)").performTextInput("Test snack")
        ui.onNodeWithText("Save").performClick()
        ui.onNodeWithText("120 kcal").assertExists()
        ui.onNodeWithText("History").performClick()
        ui.onNodeWithText("Test snack").assertIsDisplayed()
        screenshot("history")
    }

    @Test
    fun importPastWeightWithPreview() {
        ui.onNodeWithText("Stats").performClick()
        ui.onNodeWithText("Weight").performClick()
        ui.onNodeWithText("Import past weigh-ins").performScrollTo().performClick()
        ui.runOnIdle {
            val clip = ui.activity.getSystemService(android.content.ClipboardManager::class.java)
            clip.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    "synthetic weight",
                    """{"schemaVersion":1,"unit":"lb","weighIns":[{"date":"2024-03-04","weight":150}]}""",
                )
            )
        }
        ui.onNodeWithText("Paste JSON").performClick()
        ui.onNodeWithText("Preview").performClick()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("1 of 1 selected").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("Import").performClick()
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Review weigh-ins").fetchSemanticsNodes().isEmpty()
        }
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals(1, v.repo.db.list(WeightImport.kind).size)
        }
    }

    @Test
    fun weightTabShowsCachedScaleChangeAndReadings() {
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val today = java.time.LocalDate.now(v.repo.zone)
            val early = WeighIn("scale-start", v.repo.midnight(today.minusDays(4)), 80.0, "scale")
            val late = WeighIn("scale-end", v.repo.midnight(today), 79.0, "scale")
            v.repo.db.applyWeightSync(
                mapOf(early.id to early, late.id to late),
                null,
                System.currentTimeMillis(),
                0,
                "synthetic",
            )
        }
        ui.onNodeWithText("Stats").performClick()
        ui.onNodeWithText("Weight").performClick()
        ui.onNodeWithText("Week").assertIsSelected()
        ui.onNodeWithText("8 wk").performClick()
        ui.onNodeWithText("↓ 2.2 lb").assertExists()
        ui.onNodeWithText("Week").performClick()
        val date = java.time.LocalDate.now().toString()
        ui.onNodeWithContentDescription("$date: 174.2 lb").performScrollTo().performClick()
        ui.onNodeWithText("174.2 lb").assertExists()
        ui.onNodeWithText("Year").performScrollTo().performClick()
        ui.onNodeWithText("Year").assertIsSelected()
    }

    @Test
    fun statsTabsKeepTheSelectedDateAndRange() {
        ui.onNodeWithText("Stats").performClick()
        ui.onNodeWithText("Day").performClick()
        ui.onNodeWithContentDescription("Previous period").performClick()
        val yesterday =
            java.time.LocalDate.now()
                .minusDays(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
        for (tab in listOf("Nutrients", "Weight", "Energy")) {
            ui.onNodeWithText(tab).performClick()
            ui.onNodeWithText("Day").assertIsSelected()
            ui.onNode(hasText(yesterday) and hasClickAction()).assertIsDisplayed()
        }
    }

    @Test
    fun settingsAndStatsNavigateBackWithoutExiting() {
        ui.onNodeWithContentDescription("Settings").performClick()
        ui.onNodeWithText("Energy assumptions").assertIsDisplayed()
        screenshot("settings")
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithText("Stats").performClick()
        ui.onNodeWithText("This week so far").assertIsDisplayed()
        screenshot("stats")
        ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("New dish").assertIsDisplayed()
    }

    @Test
    fun balancePeriodsAreSelectableAndOpenTheirFoodLog() {
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val time = System.currentTimeMillis() - 60000
            v.repo.db.save(
                "calorie",
                org.json
                    .JSONObject()
                    .put("id", "chart-meal")
                    .put("name", "Chart meal")
                    .put("time", time)
                    .put("kcal", 450),
            )
            v.refresh()
        }
        ui.onNodeWithContentDescription("Energy balance").performClick()
        ui.onNodeWithText("Week").assertIsDisplayed()
        ui.onNodeWithText("Daily averages").assertExists()
        screenshot("balance-days")
        val day = java.time.LocalDate.now().toString()
        ui.onNodeWithContentDescription("$day:", substring = true).performClick()
        ui.onNodeWithText("1/1 days logged · today so far").assertExists()
        ui.onNodeWithText("That day").assertExists()
        ui.onNode(hasText("Intake") and hasClickAction()).performClick()
        ui.onAllNodesWithText("450 kcal").onFirst().assertExists()
        ui.onNodeWithText("Deficit").performClick()
        ui.onNodeWithText("8 wk").performClick()
        screenshot("balance-weeks")
        ui.onNodeWithText("Year").performClick()
        screenshot("balance-months")
        ui.onNodeWithText("View food log").performScrollTo().performClick()
        ui.onNodeWithText("Chart meal").assertIsDisplayed()
        ui.onNodeWithText("Show all history").assertIsDisplayed()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithText("Energy").assertIsDisplayed()
    }

    @Test
    fun historyPortionShowsScaledIngredientContributionsBeforeEditing() {
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val time = System.currentTimeMillis() - 60000
            val food =
                org.json
                    .JSONObject()
                    .put("id", "detail-food")
                    .put("name", "Synthetic seitan")
                    .put("basis", 100)
                    .put("unit", "g")
                    .put("kcal", 200)
                    .put("protein", 25)
                    .put("fiber", 6)
                    .put("carbs", 10)
                    .put("fat", 2)
            val b =
                org.json
                    .JSONObject()
                    .put("id", "detail-dish")
                    .put("name", "Seitan pasta")
                    .put("available", time - 60000)
                    .put("completed", org.json.JSONObject.NULL)
                    .put("share", 1)
                    .put("portionTracking", true)
                    .put(
                        "ingredients",
                        org.json
                            .JSONArray()
                            .put(org.json.JSONObject().put("food", food).put("quantity", 300)),
                    )
            v.repo.db.save("batch", BatchOperations.record(b, .3, time, "ate"))
            v.refresh()
            v.go("history")
        }
        ui.onNodeWithText("Seitan pasta").performClick()
        ui.onNodeWithText("Edit portion").assertIsDisplayed()
        ui.onAllNodesWithText("180 kcal").assertCountEquals(2)
        ui.onNodeWithText("Synthetic seitan").assertIsDisplayed()
        ui.onNodeWithText("90 g · in this portion").assertIsDisplayed()
        screenshot("dish-portion-details")
        ui.onNodeWithText("Whole dish").performClick()
        ui.onAllNodesWithText("600 kcal").assertCountEquals(2)
        ui.onNodeWithText("300 g · whole dish").assertIsDisplayed()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithText("Seitan pasta").assertIsDisplayed()
    }

    @Config(qualifiers = "w320dp-h891dp-xhdpi")
    @Test
    fun expenditureActivityCanBeAddedEditedAndDeletedWithMaterialControls() {
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            v.repo.db.save(
                "calorie",
                org.json
                    .JSONObject()
                    .put("id", "quick-tef")
                    .put("time", System.currentTimeMillis() - 60000)
                    .put("kcal", 1100),
            )
            v.refresh()
            v.openEnergy()
        }
        ui.onNodeWithText("Resting so far").assertIsDisplayed()
        ui.onNodeWithText("Includes 88 kcal using 8% for calorie-only logs").assertIsDisplayed()
        ui.onNodeWithText("Add activity calories").performScrollTo().performClick()
        ui.onNodeWithText("Additional kcal").performTextInput("100")
        ui.onNodeWithText("Description (optional)").performTextInput("Lifting")
        screenshot("activity-calories")
        ui.onNodeWithText("Save activity").performClick()
        ui.onNodeWithText("Lifting").performScrollTo().assertIsDisplayed()
        screenshot("expenditure")
        ui.onNodeWithText("Edit").performClick()
        ui.onNodeWithText("Additional kcal").performTextReplacement("150")
        ui.onNodeWithText("Save activity").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals(1, v.repo.db.list("activity").size)
            org.junit.Assert.assertEquals(
                150.0,
                v.repo.extraActivity(java.time.LocalDate.now(v.repo.zone)),
                0.0,
            )
        }
        ui.onNodeWithText("Edit").performScrollTo().performClick()
        ui.onNodeWithText("Delete activity").performClick()
        ui.onNodeWithText("Its extra expenditure will be removed.").assertIsDisplayed()
        ui.onNodeWithText("Delete").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals(0, v.repo.db.list("activity").size)
        }
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithText("New dish").assertIsDisplayed()
    }

    @Test
    fun statsTodayShowsCurrentTotalsAndPartialVitaminsInsteadOfAverages() {
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val today = java.time.LocalDate.now(v.repo.zone)
            v.repo.db.save("settings", v.repo.settings.put("start", today.minusDays(1).toString()))
            v.repo.db.save(
                "rate",
                org.json
                    .JSONObject()
                    .put("id", "history-rate")
                    .put("date", today.minusDays(1).toString())
                    .put("value", 2000),
            )
            fun log(id: String, time: Long, vitamin: Double?) {
                val food =
                    org.json
                        .JSONObject()
                        .put("id", "food-$id")
                        .put("name", "Synthetic food")
                        .put("basis", 100)
                        .put("unit", "g")
                        .put("kcal", 100)
                        .put("protein", 10)
                        .put("carbs", 5)
                        .put("fat", 2)
                if (vitamin != null) food.put("vitamin-c", vitamin)
                v.repo.db.save(
                    "direct",
                    org.json
                        .JSONObject()
                        .put("id", id)
                        .put("name", "Synthetic food")
                        .put("available", time)
                        .put("completed", time)
                        .put("share", 1)
                        .put(
                            "ingredients",
                            org.json
                                .JSONArray()
                                .put(org.json.JSONObject().put("food", food).put("quantity", 100)),
                        ),
                )
            }
            log("yesterday", v.repo.midnight(today.minusDays(1)) + 12 * 3600000, .1)
            log("today-known", System.currentTimeMillis() - 120000, .025)
            log("today-unknown", System.currentTimeMillis() - 60000, null)
            v.refresh()
            v.go("stats")
        }
        ui.onNodeWithText("Nutrients").performClick()
        ui.onNodeWithText("Day").performClick()
        ui.onNodeWithText("Today · so far").assertIsDisplayed()
        ui.onNodeWithText("200 kcal").assertExists()
        ui.onNodeWithText("Vitamins").performClick()
        ui.onNodeWithText("≥25 mg").assertExists()
        ui.onAllNodesWithText("Not provided by logged foods").onFirst().assertExists()
        screenshot("stats-today-vitamins")
        ui.onNodeWithText("8 wk").performClick()
        ui.onNodeWithText("≥62.5 mg / day").assertExists()
        ui.onNodeWithText("Day").performClick()
        ui.onNodeWithText("≥25 mg").assertExists()
    }

    @Config(qualifiers = "w320dp-h891dp-xhdpi")
    @Test
    fun oneReferenceProfileFillsNutrientTargetsAndKeepsCustomProtein() {
        var birth = ""
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val current = java.time.YearMonth.now(v.repo.zone)
            birth = "%02d/%04d".format(current.monthValue, current.year - 30)
            v.saveGoal(1, 125.0)
            v.openSettings()
        }
        ui.onNodeWithText("Set reference profile").performScrollTo().performClick()
        ui.onNodeWithText("Birth month/year (MM/YYYY)").performTextInput(birth)
        ui.onNodeWithText("Current reference age: 30").assertIsDisplayed()
        ui.onNodeWithText("Male").performClick()
        screenshot("nutrient-profile")
        ui.onNodeWithText("Use reference targets").performClick()
        ui.onNodeWithText("Edit reference profile").assertExists()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals(125.0, v.repo.goal(1), 0.0)
            org.junit.Assert.assertEquals(38.0, v.repo.goal(4), 0.0)
            org.junit.Assert.assertEquals(.000015, v.repo.goal(7), 1e-12)
        }
        ui.onNodeWithText("Edit reference profile").performScrollTo().performClick()
        ui.onNodeWithText("Birth month/year (MM/YYYY)").assertTextContains(birth)
        ui.onNodeWithText("Female").performClick()
        ui.onNodeWithText("Use reference targets").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals(125.0, v.repo.goal(1), 0.0)
            org.junit.Assert.assertEquals(25.0, v.repo.goal(4), 0.0)
        }
    }

    // Robolectric #8460: dialogs with text input loop with width qualifiers above 320dp.
    // Keep the interaction test on its supported profile; other screenshots use 411dp.
    @Config(qualifiers = "w320dp-h891dp-xhdpi")
    @Test
    fun cookingPortionAndFinishUseActualMaterialControls() {
        val food =
            org.json
                .JSONObject()
                .put("id", "portion-food")
                .put("name", "Test seitan")
                .put("basis", 1)
                .put("unit", "serving")
                .put("servingGrams", 57)
                .put("servingLabel", "1 serving (57 g)")
                .put("kcal", 100)
                .put("protein", 7)
                .put("carbs", 10)
                .put("fat", 2)
                .put("fiber", 3)
        ui.onNodeWithText("New dish").performClick()
        ui.onNodeWithText("Dish name").performTextInput("Test pasta")
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            v.chooseFood(food, "dish")
        }
        ui.onNodeWithText("Quantity").performTextReplacement("2")
        ui.onNodeWithText("Serving (57 g)").assertIsDisplayed()
        screenshot("portion")
        ui.onNodeWithText("Add to dish").performClick()
        ui.onNodeWithText("Test pasta").assertExists()
        ui.onNodeWithText("Test seitan").performClick()
        ui.onNodeWithText("Quantity").assertTextContains("2")
        ui.onNodeWithText("Add to dish").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals(1, v.draft.getJSONArray("ingredients").length())
            org.junit.Assert.assertEquals(
                2.0,
                v.draft.getJSONArray("ingredients").getJSONObject(0).getDouble("quantity"),
                0.0,
            )
        }
        ui.onNodeWithText("Done cooking").performClick()
        screenshot("today-with-dish")
        ui.onNodeWithText("Ate").performClick()
        ui.onNodeWithText("Percent of whole dish").performTextReplacement("30")
        screenshot("ate-dialog")
        ui.onNodeWithText("Log portion").performClick()
        ui.onNodeWithText("30% logged").assertExists()
        ui.onNodeWithText("Finished").performClick()
        ui.onNodeWithText("Log & finish").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            val batch = v.repo.db.list("batch").single()
            org.junit.Assert.assertEquals(2, batch.getJSONArray("eatingEvents").length())
            org.junit.Assert.assertEquals(
                .3,
                batch.getJSONArray("eatingEvents").getJSONObject(0).getDouble("fraction"),
                1e-9,
            )
            org.junit.Assert.assertEquals(
                .7,
                batch.getJSONArray("eatingEvents").getJSONObject(1).getDouble("fraction"),
                1e-9,
            )
        }
    }

    @Test
    fun calorieLogHasEditableEatingTime() {
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            v.beginCalories()
        }
        ui.onNodeWithContentDescription("Edit eating time").assertIsDisplayed().performClick()
        ui.onNodeWithText("Next").assertIsDisplayed()
        ui.onNodeWithText("Cancel").performClick()
        ui.onNodeWithText("Calories (kcal)").performTextInput("1100")
        ui.onNodeWithText("Name (optional)").performTextInput("Test meal")
        var expected = 0L
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            expected =
                java.time.LocalDate.now(v.repo.zone)
                    .minusDays(1)
                    .atTime(21, 30)
                    .atZone(v.repo.zone)
                    .toInstant()
                    .toEpochMilli()
            v.field("calories", "time", expected.toString(), true)
        }
        screenshot("calorie-time")
        ui.onNodeWithText("Save").performClick()
        ui.runOnIdle {
            val v = androidx.lifecycle.ViewModelProvider(ui.activity)[TrackerViewModel::class.java]
            org.junit.Assert.assertEquals(
                expected,
                v.repo.db.list("calorie").single().getLong("time"),
            )
        }
    }
}
