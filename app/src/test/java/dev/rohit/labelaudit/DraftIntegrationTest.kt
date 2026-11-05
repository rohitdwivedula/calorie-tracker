package dev.rohit.labelaudit

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftIntegrationTest {
    private lateinit var app: Application

    @Before
    fun reset() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("nutrition.db")
    }

    private fun ingredient() =
        JSONObject()
            .put("quantity", 200)
            .put(
                "food",
                JSONObject()
                    .put("id", "synthetic-food")
                    .put("name", "Test peppers")
                    .put("basis", 100)
                    .put("unit", "g")
                    .put("kcal", 30)
                    .put("protein", 1)
                    .put("carbs", 6)
                    .put("fat", 0),
            )

    @Test
    fun draftsSurviveFreshViewModelAndBackupWithoutBecomingIntake() {
        val original = TrackerViewModel(app, SavedStateHandle())
        original.beginCook()
        assertTrue(original.repo.db.list("draft").isEmpty())
        original.draftName("Pasta in progress")
        original.setDraft(original.draft.put("ingredients", JSONArray().put(ingredient())))
        val id = original.draft.getString("id")
        original.back()
        original.beginCook()
        original.draftName("Another dish")
        assertEquals(2, original.repo.db.list("draft").size)
        assertTrue(original.repo.db.list("batch").isEmpty())
        val now = System.currentTimeMillis()
        assertEquals(0.0, original.repo.intake(0, now, now)[0], 0.0)
        val backup = original.repo.db.backup()
        assertEquals(5, JSONObject(backup).getInt("schema"))
        original.repo.db.close()

        // No Activity/SavedStateHandle state: represents returning after process death.
        val fresh = TrackerViewModel(app, SavedStateHandle())
        fresh.repo.db.restore(backup)
        fresh.repo.db.restore(backup)
        assertEquals(2, fresh.repo.db.list("draft").size)
        fresh.resumeDraft("draft-$id")
        assertEquals("Pasta in progress", fresh.draft.getString("name"))
        assertFalse(fresh.draft.has("available"))
        assertEquals(
            200.0,
            fresh.draft.getJSONArray("ingredients").getJSONObject(0).getDouble("quantity"),
            0.0,
        )
        fresh.saveCook()
        assertEquals(id, fresh.repo.db.list("batch").single().getString("id"))
        assertEquals(1, fresh.repo.db.list("draft").size)
        fresh.discardDraft(fresh.repo.db.list("draft").single().getString("id"))
        assertTrue(fresh.repo.db.list("draft").isEmpty())
        assertEquals(1, fresh.repo.db.list("batch").size)
        fresh.repo.db.close()
    }

    @Test
    fun unfinishedEditsDoNotChangeSavedBatchAndMalformedRestoreKeepsDraft() {
        val v = TrackerViewModel(app, SavedStateHandle())
        v.beginCook()
        v.draftName("Original")
        v.setDraft(v.draft.put("ingredients", JSONArray().put(ingredient())))
        v.saveCook()
        val saved = v.repo.db.list("batch").single()
        v.beginCook(saved)
        v.draftName("Edited draft")
        v.removeIngredient(0)
        v.back()
        assertEquals("Original", v.repo.db.list("batch").single().getString("name"))
        v.beginCook(saved)
        assertEquals("Edited draft", v.draft.getString("name"))
        val backup = JSONObject(v.repo.db.backup())
        val rows = backup.getJSONArray("records")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.getString("kind") == "draft") {
                row.getJSONObject("data")
                    .getJSONObject("batch")
                    .put("ingredients", JSONArray().put(ingredient().put("quantity", -1)))
            }
        }
        assertThrows(Exception::class.java) { v.repo.db.restore(backup.toString()) }
        assertEquals(
            0,
            v.repo.db
                .list("draft")
                .single()
                .getJSONObject("batch")
                .getJSONArray("ingredients")
                .length(),
        )
        v.discardDraft(v.repo.db.list("draft").single().getString("id"))
        assertEquals("Original", v.repo.db.list("batch").single().getString("name"))
        v.repo.db.close()
    }
}
