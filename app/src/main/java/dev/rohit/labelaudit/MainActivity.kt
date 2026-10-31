package dev.rohit.labelaudit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(15, 20, 17)
        window.navigationBarColor = android.graphics.Color.rgb(15, 20, 17)
        setContent { TrackerTheme { TrackerApp() } }
    }
}
