package dev.rohit.labelaudit

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
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

    @Test
    fun galleryOpensImageOnlyPickerWithoutCameraPermissionAndCancelKeepsScanner() {
        ui.onNodeWithContentDescription("Choose barcode image").assertIsEnabled().performClick()
        val launched = shadowOf(ui.activity).nextStartedActivityForResult
        assertNotNull(launched)
        assertTrue(
            launched.intent.action in
                listOf("android.provider.action.PICK_IMAGES", Intent.ACTION_OPEN_DOCUMENT)
        )
        assertEquals("image/*", launched.intent.type)
        ui.runOnIdle {
            shadowOf(ui.activity).receiveResult(launched.intent, Activity.RESULT_CANCELED, null)
        }
        ui.onNodeWithText("Search foods").assertIsDisplayed()
        ui.onNodeWithContentDescription("Choose barcode image").assertIsEnabled()
        assertFalse(ui.activity.isFinishing)
    }

    @Test
    fun unreadableGalleryImageShowsRetryAndLeavesManualEntryAvailable() {
        ui.onNodeWithContentDescription("Choose barcode image").performClick()
        val launched = shadowOf(ui.activity).nextStartedActivityForResult
        ui.runOnIdle {
            shadowOf(ui.activity)
                .receiveResult(
                    launched.intent,
                    Activity.RESULT_OK,
                    Intent().setData(Uri.parse("content://missing-photo-provider/no-image")),
                )
        }
        ui.waitUntil(10000) {
            ui.onAllNodesWithText("Couldn't read that photo. Try another or enter a barcode.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        ui.onNodeWithContentDescription("Choose barcode image").assertIsEnabled()
        ui.onNodeWithText("Enter barcode").assertIsDisplayed()
        assertFalse(ui.activity.isFinishing)
    }

    @Test
    fun flipAndFlashAreDisabledWhenCameraIsNotAvailableButGalleryIsUsable() {
        ui.onNodeWithContentDescription("Flip camera").assertIsDisplayed().assertIsNotEnabled()
        ui.onNodeWithContentDescription("Toggle light").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Choose barcode image")
            .assertIsDisplayed()
            .assertIsEnabled()
    }
}
