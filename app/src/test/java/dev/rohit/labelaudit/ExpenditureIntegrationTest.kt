package dev.rohit.labelaudit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.*
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpenditureIntegrationTest {
    private lateinit var r: TrackerRepository
    private lateinit var previousZone: TimeZone
    private val day = LocalDate.of(2026, 11, 1) // Chicago: 25 hours

    private fun time(date: LocalDate, hour: Int) =
        date.atTime(hour, 0).atZone(r.zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("nutrition.db")
        r = TrackerRepository(context)
        r.db.save(
            "settings",
            r.settings.put("start", day.toString()).put("energyStart", day.toString()),
        )
        r.db.list("resting").forEach { r.db.delete(it) }
        r.db.save(
            "resting",
            JSONObject().put("id", "resting-test").put("date", day.toString()).put("value", 1766),
        )
        r.reload()
    }

    @After
    fun close() {
        r.db.close()
        TimeZone.setDefault(previousZone)
    }

    private class FakeSource : StepSource {
        var state = "ok"
        var count: Long? = 6000
        var fail = false
        var restricted = false
        val intervals = mutableListOf<Pair<Instant, Instant>>()

        override suspend fun access() = state

        override suspend fun aggregate(from: Instant, until: Instant): Long? {
            intervals.add(from to until)
            if (restricted) throw SecurityException("History restricted")
            if (fail) throw java.io.IOException("Sync failed")
            return count
        }
    }

    @Test
    fun restingAccruesAtSameAsOfTimeWithDstAndEffectiveRates() {
        val noon = time(day, 12)
        assertEquals(1766 * 13.0 / 25, r.dailyExpenditure(day, noon).resting, 1e-8)
        assertEquals(1766.0, r.dailyExpenditure(day, time(day.plusDays(1), 12)).resting, 1e-8)
        r.db.save(
            "resting",
            JSONObject()
                .put("id", "changed")
                .put("date", day.plusDays(1).toString())
                .put("value", 2000),
        )
        assertEquals(1766.0, r.dailyExpenditure(day, time(day.plusDays(1), 12)).resting, 1e-8)
        assertEquals(
            1000.0,
            r.dailyExpenditure(day.plusDays(1), time(day.plusDays(1), 12)).resting,
            1e-8,
        )
    }

    @Test
    fun refreshReplacesRatherThanAddsAndUsesLocalBoundaries() = runBlocking {
        val source = FakeSource()
        val sync = StepsSync(r, source)
        val now = time(day.plusDays(1), 12)
        sync.refresh(listOf(day, day.plusDays(1)), now)
        val past = source.intervals.first { it.first == Instant.ofEpochMilli(r.midnight(day)) }
        assertEquals(25 * 3600L, Duration.between(past.first, past.second).seconds)
        assertEquals(Instant.ofEpochMilli(now), source.intervals.first().second)
        source.count = 5000
        sync.refresh(listOf(day), now + 1000)
        val row = r.db.stepCache(day.toString(), r.zone.id)
        assertEquals(5000, row.getLong("steps"))
        assertEquals(now + 1000, row.getLong("synced"))
        assertEquals(150.0, r.dailyExpenditure(day, now + 1000).steps!!, 0.0)
    }

    @Test
    fun errorsAndRevocationPreserveLastSuccessAndFoodStillWorks() = runBlocking {
        val source = FakeSource()
        val sync = StepsSync(r, source)
        val now = time(day.plusDays(1), 12)
        sync.refresh(listOf(day), now)
        source.fail = true
        sync.refresh(listOf(day), now + 1000)
        var row = r.db.stepCache(day.toString(), r.zone.id)
        assertEquals("error", row.getString("status"))
        assertEquals(now, row.getLong("synced"))
        assertEquals(6000, row.getLong("steps"))
        assertTrue(r.dailyExpenditure(day, now + 1000).stepsIncomplete)
        source.state = "permission"
        sync.refresh(listOf(day), now + 2000)
        row = r.db.stepCache(day.toString(), r.zone.id)
        assertEquals("permission", row.getString("status"))
        assertEquals(now, row.getLong("synced"))
        assertEquals(180.0, r.dailyExpenditure(day, now + 2000).steps!!, 0.0)
        r.db.save(
            "calorie",
            JSONObject().put("id", "food-log").put("time", time(day, 12)).put("kcal", 120),
        )
        assertEquals(120.0, r.intake(r.midnight(day), now, now)[0], 0.0)
    }

    @Test
    fun noDataIsNotZeroAndUnavailableDoesNotReadRecords() = runBlocking {
        val source = FakeSource()
        source.count = null
        val sync = StepsSync(r, source)
        val now = time(day.plusDays(1), 12)
        sync.refresh(listOf(day), now)
        assertFalse(r.db.stepCache(day.toString(), r.zone.id).has("steps"))
        assertNull(r.dailyExpenditure(day, now).steps)
        source.count = 0
        sync.refresh(listOf(day), now + 1000)
        assertEquals(0.0, r.dailyExpenditure(day, now + 1000).steps!!, 0.0)
        assertFalse(r.dailyExpenditure(day, now + 1000).stepsIncomplete)
        source.state = "unavailable"
        val calls = source.intervals.size
        sync.refresh(listOf(day), now + 2000)
        assertEquals(calls, source.intervals.size)
        assertTrue(r.dailyExpenditure(day, now + 2000).stepsIncomplete)
    }

    @Test
    fun partialYesterdayCacheIsIncompleteEvenWhenRecent() = runBlocking {
        val sync = StepsSync(r, FakeSource())
        sync.refresh(listOf(day), time(day, 12))
        assertFalse(r.dailyExpenditure(day, time(day, 12)).stepsIncomplete)
        assertTrue(r.dailyExpenditure(day, time(day.plusDays(1), 1)).stepsIncomplete)
        sync.refresh(listOf(day), time(day.plusDays(1), 1))
        assertFalse(r.dailyExpenditure(day, time(day.plusDays(1), 1)).stepsIncomplete)
        assertEquals(
            r.midnight(day.plusDays(1)),
            r.db.stepCache(day.toString(), r.zone.id).getLong("through"),
        )
    }

    @Test
    fun historyRestrictionDoesNotPretendPermissionWasRevoked() = runBlocking {
        val source = FakeSource()
        source.restricted = true
        val sync = StepsSync(r, source)
        val now = time(day.plusDays(1), 12)
        assertEquals("ok", sync.refresh(listOf(day), now))
        assertEquals("unavailable", r.db.stepCache(day.toString(), r.zone.id).getString("status"))
        assertEquals("ok", r.db.stepCache("_connection", "_").getString("status"))
    }

    @Test
    fun activityEditingDeletingAndBackupKeepFactsSeparateFromStepCache() = runBlocking {
        val activity =
            JSONObject()
                .put("id", "lifting")
                .put("date", day.toString())
                .put("kcal", 100)
                .put("description", "Lifting")
        r.db.save("activity", activity)
        r.db.save(
            "activity",
            JSONObject().put("id", "cycling").put("date", day.toString()).put("kcal", 200),
        )
        assertEquals(300.0, r.extraActivity(day), 0.0)
        r.db.save("activity", activity.put("kcal", 150).put("date", day.plusDays(1).toString()))
        assertEquals(200.0, r.extraActivity(day), 0.0)
        assertEquals(150.0, r.extraActivity(day.plusDays(1)), 0.0)
        r.db.delete(r.db.list("activity").first { it.getString("id") == "cycling" })
        assertEquals(0.0, r.extraActivity(day), 0.0)
        StepsSync(r, FakeSource()).refresh(listOf(day), time(day.plusDays(1), 12))
        val backup = r.db.backup()
        assertEquals(4, JSONObject(backup).getInt("schema"))
        assertEquals(1, JSONObject(backup).getJSONArray("cachedSteps").length())
        r.db.restore(backup)
        r.db.restore(backup)
        r.reload()
        assertEquals(1, r.db.list("activity").size)
        assertEquals(150.0, r.extraActivity(day.plusDays(1)), 0.0)
        val restored = r.db.stepCache(day.toString(), r.zone.id)
        assertEquals(6000, restored.getLong("steps"))
        assertEquals("restored", restored.getString("status"))
        assertTrue(r.dailyExpenditure(day, time(day.plusDays(1), 12)).stepsIncomplete)
    }

    @Test
    fun editingFoodSnapshotRecalculatesDigestionWithoutChangingCalories() {
        val food =
            JSONObject()
                .put("id", "food")
                .put("name", "Test food")
                .put("unit", "g")
                .put("basis", 100)
                .put("kcal", 1000)
                .put("protein", 100)
                .put("carbs", 100)
                .put("fat", 30)
        val b =
            JSONObject()
                .put("id", "dish")
                .put("name", "Dish")
                .put("available", r.midnight(day))
                .put("completed", JSONObject.NULL)
                .put("share", 1)
                .put("portionTracking", true)
                .put(
                    "ingredients",
                    JSONArray().put(JSONObject().put("food", food).put("quantity", 100)),
                )
        val recorded = BatchOperations.record(b, .5, time(day, 10), "ate")
        r.db.save("batch", recorded)
        val now = time(day, 12)
        val first = r.dailyExpenditure(day, now).digestion
        recorded
            .getJSONArray("ingredients")
            .getJSONObject(0)
            .getJSONObject("food")
            .put("protein", 150)
        r.db.save("batch", recorded)
        assertEquals(first + 25, r.dailyExpenditure(day, now).digestion, 1e-8)
        assertEquals(500.0, r.nutrientTotals(r.midnight(day), now, now).values[0], 0.0)
    }

    @Test
    fun quickCaloriesUseEightPercentAndEditsMoveOrRemoveOnlyTheirFallback() {
        val food =
            JSONObject()
                .put("id", "food")
                .put("name", "Label rounding example")
                .put("unit", "g")
                .put("basis", 100)
                .put("kcal", 15)
                .put("protein", 4)
                .put("carbs", 0)
                .put("fat", 0)
        val direct =
            JSONObject()
                .put("id", "direct")
                .put("name", "Known macros")
                .put("available", time(day, 9))
                .put("completed", time(day, 9))
                .put("share", 1)
                .put(
                    "ingredients",
                    JSONArray().put(JSONObject().put("food", food).put("quantity", 100)),
                )
        r.db.save("direct", direct)
        val quick = JSONObject().put("id", "quick").put("time", time(day, 10)).put("kcal", 1100)
        r.db.save("calorie", quick)
        val now = time(day, 12)
        val first = r.dailyExpenditure(day, now)
        assertEquals(88.0, first.fallbackDigestion, 1e-9)
        assertEquals(92.0, first.digestion, 1e-9) // 4 from protein, 88 only from quick calories
        assertFalse(first.nutritionIncomplete)
        val intake = r.nutrientTotals(r.midnight(day), now, now)
        assertEquals(1115.0, intake.values[0], 0.0)
        assertEquals(4.0, intake.values[1], 0.0)
        assertTrue(intake.missing[1]) // fallback never invents protein grams
        r.db.save("calorie", quick.put("kcal", 2200))
        assertEquals(180.0, r.dailyExpenditure(day, now).digestion, 1e-9)
        r.db.save("calorie", quick.put("time", time(day.plusDays(1), 10)))
        val later = time(day.plusDays(1), 12)
        assertEquals(4.0, r.dailyExpenditure(day, later).digestion, 1e-9)
        assertEquals(176.0, r.dailyExpenditure(day.plusDays(1), later).digestion, 1e-9)
        r.db.delete(quick)
        assertEquals(0.0, r.dailyExpenditure(day.plusDays(1), later).digestion, 0.0)
        assertEquals(1, r.db.list("direct").size)
        assertEquals(
            15.0,
            r.db
                .list("direct")
                .single()
                .getJSONArray("ingredients")
                .getJSONObject(0)
                .getJSONObject("food")
                .getDouble("kcal"),
            0.0,
        )
    }

    @Test
    fun versionThreeMigrationPreservesOldTotalRatesAndFood() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oldDate = LocalDate.now(r.zone).minusDays(1)
        val oldSettings =
            JSONObject(r.settings.toString()).put("start", oldDate.toString()).apply {
                remove("energyStart")
            }
        r.db.close()
        context.deleteDatabase("nutrition.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                context.getDatabasePath("nutrition.db"),
                null,
            )
            .use { db ->
                db.execSQL(
                    "CREATE TABLE records(id TEXT PRIMARY KEY,kind TEXT NOT NULL,body TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX records_kind ON records(kind)")
                db.execSQL(
                    "CREATE TABLE lookups(id TEXT PRIMARY KEY,barcode TEXT NOT NULL,retrieved_at INTEGER NOT NULL,url TEXT NOT NULL,payload TEXT NOT NULL,sha256 TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO records VALUES(?,?,?)",
                    arrayOf("settings", "settings", oldSettings.toString()),
                )
                db.execSQL(
                    "INSERT INTO records VALUES(?,?,?)",
                    arrayOf(
                        "old-rate",
                        "rate",
                        JSONObject()
                            .put("id", "old-rate")
                            .put("date", oldDate.toString())
                            .put("value", 2890)
                            .toString(),
                    ),
                )
                db.execSQL(
                    "INSERT INTO records VALUES(?,?,?)",
                    arrayOf(
                        "old-food",
                        "calorie",
                        JSONObject()
                            .put("id", "old-food")
                            .put("time", time(oldDate, 12))
                            .put("kcal", 1100)
                            .toString(),
                    ),
                )
                db.version = 3
            }
        r = TrackerRepository(context)
        assertEquals(4, r.db.readableDatabase.version)
        assertEquals(2890.0, r.rate(oldDate), 0.0)
        assertEquals(1766.0, r.restingRate(LocalDate.now(r.zone)), 0.0)
        assertEquals(LocalDate.now(r.zone).toString(), r.settings.getString("energyStart"))
        assertTrue(r.dailyExpenditure(oldDate, System.currentTimeMillis()).legacy)
        assertEquals(2890.0, r.dailyExpenditure(oldDate, System.currentTimeMillis()).total, 0.0)
        assertEquals(1100.0, r.db.list("calorie").single().getDouble("kcal"), 0.0)
    }

    @Test
    fun invalidCachedStepsRejectRestoreWithoutTouchingFacts() = runBlocking {
        val now = time(day.plusDays(1), 12)
        StepsSync(r, FakeSource()).refresh(listOf(day), now)
        r.db.save(
            "activity",
            JSONObject().put("id", "keep").put("date", day.toString()).put("kcal", 100),
        )
        val backup = JSONObject(r.db.backup())
        backup.getJSONArray("cachedSteps").getJSONObject(0).getJSONObject("data").put("steps", -1)
        try {
            r.db.restore(backup.toString())
            fail("Invalid steps accepted")
        } catch (e: Exception) {}
        assertEquals(100.0, r.extraActivity(day), 0.0)
        assertEquals(6000, r.db.stepCache(day.toString(), r.zone.id).getLong("steps"))
    }

    @Test
    fun oldPortableBackupActivatesNewModelWithoutReinterpretingRates() {
        val backup = JSONObject(r.db.backup()).put("schema", 3)
        val rows = backup.getJSONArray("records")
        for (i in rows.length() - 1 downTo 0) {
            val row = rows.getJSONObject(i)
            if (row.getString("kind") == "resting") rows.remove(i)
            else if (row.getString("kind") == "settings")
                row.getJSONObject("data").remove("energyStart")
        }
        val ratesBefore = r.db.list("rate").map { it.toString() }
        r.db.restore(backup.toString())
        r.reload()
        assertEquals(ratesBefore, r.db.list("rate").map { it.toString() })
        assertEquals(1766.0, r.restingRate(LocalDate.now(r.zone)), 0.0)
    }
}
