package dev.rohit.labelaudit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    @Test
    fun launcherUsesAdaptiveArtAndSupportsThemedIcons() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric's package-manager shadow does not resolve adaptive application icons.
        // Load the packaged resource through Android Resources; manifest linkage is checked by
        // aapt.
        val icon = context.getDrawable(R.mipmap.ic_launcher)!!
        assertTrue(icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        assertNotNull(icon.monochrome)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 256, 256)
        icon.draw(Canvas(bitmap))
        assertTrue(android.graphics.Color.alpha(bitmap.getPixel(128, 128)) > 0)
        val folder = File("build/outputs/compose-screenshots")
        folder.mkdirs()
        File(folder, "launcher-icon.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
