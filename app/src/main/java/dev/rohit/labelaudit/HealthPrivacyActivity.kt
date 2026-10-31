package dev.rohit.labelaudit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*

/** Required permission-usage destination for both platform and APK Health Connect. */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrackerTheme {
                Surface {
                    Page {
                        Section("Steps & privacy") {
                            Text(
                                "Batch Nutrition reads daily step totals from Health Connect to estimate expenditure. Health Connect combines your sources; we don't write steps or read other health data."
                            )
                            Text(
                                "Step totals and sync times stay on this phone. They aren't uploaded. Explicit backup exports include saved step totals and sync times, separately from your food and activity records. You can revoke access in Android's Health Connect settings. Food logging continues without access."
                            )
                            Text(
                                "Only explicit food-code lookups contact Open Food Facts. No analytics or photos are collected."
                            )
                            TextButton(onClick = { finish() }) { Text("Done") }
                        }
                    }
                }
            }
        }
    }
}
