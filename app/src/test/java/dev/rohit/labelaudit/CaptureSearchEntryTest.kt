package dev.rohit.labelaudit

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class CaptureSearchEntryTest {
    @get:Rule val ui = createAndroidComposeRule<CaptureActivity>()

    @Test
    fun cameraOffersSearchEvenWithoutCameraPermission() {
        ui.onNodeWithText("Search foods").assertIsDisplayed().performClick()
        assertEquals("saved_foods", shadowOf(ui.activity).resultIntent.getStringExtra("action"))
    }
}
