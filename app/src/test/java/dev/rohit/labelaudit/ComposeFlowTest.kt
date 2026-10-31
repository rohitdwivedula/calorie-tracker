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
    fun settingsAndStatsNavigateBackWithoutExiting() {
        ui.onNodeWithContentDescription("Settings").performClick()
        ui.onNodeWithText("Energy assumptions").assertIsDisplayed()
        screenshot("settings")
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithText("Stats").performClick()
        ui.onNodeWithText("Nutrition trends").assertIsDisplayed()
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
            v.go("summary")
        }
        ui.onNodeWithText("Days").assertIsDisplayed()
        ui.onNodeWithText("Average daily intake").assertExists()
        screenshot("balance-days")
        val day = java.time.LocalDate.now().toString()
        ui.onNodeWithContentDescription("$day:", substring = true).performClick()
        ui.onNodeWithText("1/1 days logged · today so far").assertExists()
        ui.onNode(hasText("Intake") and hasClickAction()).performClick()
        ui.onNodeWithText("450 kcal").assertExists()
        ui.onNodeWithText("Deficit").performClick()
        ui.onNodeWithText("Weeks").performClick()
        screenshot("balance-weeks")
        ui.onNodeWithText("Months").performClick()
        screenshot("balance-months")
        ui.onNodeWithText("View food log").performScrollTo().performClick()
        ui.onNodeWithText("Chart meal").assertIsDisplayed()
        ui.onNodeWithText("Show all history").assertIsDisplayed()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithText("Energy balance").assertIsDisplayed()
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
        ui.onNodeWithText("180 kcal").assertExists()
        ui.onNodeWithText("Synthetic seitan").assertIsDisplayed()
        ui.onNodeWithText("90 g · in this portion").assertIsDisplayed()
        screenshot("dish-portion-details")
        ui.onNodeWithText("Whole dish").performClick()
        ui.onNodeWithText("600 kcal").assertExists()
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
