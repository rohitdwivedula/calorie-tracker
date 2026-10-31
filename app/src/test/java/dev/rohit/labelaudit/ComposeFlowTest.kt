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
}
