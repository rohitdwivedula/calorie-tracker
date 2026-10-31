package dev.rohit.labelaudit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryIntegrationTest {
    private lateinit var context: Context

    @Before
    fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("nutrition.db")
    }

    private fun food() =
        JSONObject()
            .put("id", "food")
            .put("name", "Test food")
            .put("basis", 100)
            .put("unit", "g")
            .put("barcode", "012345678901")
            .put("kcal", 1300)
            .put("protein", 10)
            .put("carbs", 20)
            .put("fat", 30)

    private fun batch() =
        JSONObject()
            .put("id", "batch")
            .put("name", "Test batch")
            .put("available", 1)
            .put("completed", JSONObject.NULL)
            .put("share", 1)
            .put("portionTracking", true)
            .put(
                "ingredients",
                JSONArray().put(JSONObject().put("food", food()).put("quantity", 100)),
            )

    @Test
    fun versionTwoDatabaseMigratesWithoutChangingFacts() {
        val path = context.getDatabasePath("nutrition.db")
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                "CREATE TABLE records(id TEXT PRIMARY KEY,kind TEXT NOT NULL,body TEXT NOT NULL)"
            )
            db.execSQL("CREATE INDEX records_kind ON records(kind)")
            db.execSQL(
                "INSERT INTO records VALUES(?,?,?)",
                arrayOf("food", "food", food().toString()),
            )
            db.version = 2
        }
        Data(context).use { db ->
            assertEquals(3, db.readableDatabase.version)
            assertEquals("Test food", db.list("food").single().getString("name"))
            assertEquals(0, db.lookupArchive().length())
        }
    }

    @Test
    fun backupRoundTripKeepsRawFieldsAndDoesNotDuplicate() {
        val r = TrackerRepository(context)
        val raw = "{\"status\":1,\"product\":{\"future\":\"α\"}}"
        val lookup =
            r.db.cacheLookup(
                "012345678901",
                "https://world.openfoodfacts.org/api/v2/product/012345678901.json",
                raw,
            )
        r.db.save("food", food().put("lookupId", lookup))
        r.db.save("batch", batch())
        val backup = r.db.backup()
        r.db.save("food", food().put("name", "Changed"))
        r.db.restore(backup)
        r.db.restore(backup)
        assertEquals(1, r.db.list("food").size)
        assertEquals("Test food", r.db.list("food").single().getString("name"))
        assertEquals(raw, r.db.lookupArchive().getJSONObject(0).getString("payload"))
        assertEquals(1, r.db.list("batch").size)
        r.db.close()
    }

    @Test
    fun invalidRestoreLeavesExistingDatabaseIntact() {
        val r = TrackerRepository(context)
        r.db.save("food", food())
        val backup = JSONObject(r.db.backup())
        backup.getJSONArray("records").getJSONObject(0).getJSONObject("data").put("basis", -1)
        try {
            r.db.restore(backup.toString())
            fail("Expected validation failure")
        } catch (expected: Exception) {}
        assertEquals("Test food", r.db.list("food").single().getString("name"))
        r.db.close()
    }

    @Test
    fun composedRepositoryChargesRecordedShareOnlyOnce() {
        val r = TrackerRepository(context)
        r.db.save("batch", BatchOperations.record(batch(), .3, 10, "ate"))
        assertEquals(390.0, r.intake(0, 100, 100)[0], 1e-9)
        r.db.save("batch", BatchOperations.finish(r.db.list("batch").single(), 110))
        assertEquals(390.0, r.intake(0, 100, 200)[0], 1e-9)
        assertEquals(910.0, r.intake(100, 200, 200)[0], 1e-9)
        r.db.close()
    }

    @Test
    fun backdatedCalorieEditMovesIntakeWithoutDuplicating() {
        val v =
            TrackerViewModel(
                context as android.app.Application,
                androidx.lifecycle.SavedStateHandle(),
            )
        v.saveCalories("Test meal", 1100.0)
        val original = v.repo.db.list("calorie").single()
        val id = original.getString("id")
        val today = java.time.LocalDate.now(v.repo.zone)
        val yesterday =
            today.minusDays(1).atTime(21, 30).atZone(v.repo.zone).toInstant().toEpochMilli()
        v.beginCalories(original)
        v.saveCalories("Test meal", 1100.0, yesterday)
        val updated = v.repo.db.list("calorie").single()
        org.junit.Assert.assertEquals(id, updated.getString("id"))
        org.junit.Assert.assertEquals(yesterday, updated.getLong("time"))
        val now = System.currentTimeMillis() + 1
        org.junit.Assert.assertEquals(0.0, v.repo.intake(v.repo.midnight(today), now, now)[0], 0.0)
        org.junit.Assert.assertEquals(
            1100.0,
            v.repo.intake(v.repo.midnight(today.minusDays(1)), v.repo.midnight(today), now)[0],
            0.0,
        )
        v.repo.db.close()
    }

    @Test
    fun calorieEditWithoutTimeChangePreservesOriginalInstant() {
        val v =
            TrackerViewModel(
                context as android.app.Application,
                androidx.lifecycle.SavedStateHandle(),
            )
        val time = System.currentTimeMillis() - 3600000
        v.saveCalories("Snack", 120.0, time)
        v.beginCalories(v.repo.db.list("calorie").single())
        v.saveCalories("Snack corrected", 150.0)
        val record = v.repo.db.list("calorie").single()
        org.junit.Assert.assertEquals(time, record.getLong("time"))
        org.junit.Assert.assertEquals(150.0, record.getDouble("kcal"), 0.0)
        v.repo.db.close()
    }
}
