package dev.rohit.labelaudit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.*
import java.util.TimeZone
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoggedHistoryTest {
    private lateinit var r: TrackerRepository
    private lateinit var originalZone: TimeZone
    private val today = LocalDate.of(2026, 11, 2)

    private fun at(day: LocalDate, hour: Int) =
        day.atTime(hour, 0).atZone(r.zone).toInstant().toEpochMilli()

    private val start
        get() = today.minusDays(7)

    private val now
        get() = at(today, 12)

    @Before
    fun setup() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("nutrition.db")
        r = TrackerRepository(context)
        r.db.list("rate").forEach { r.db.delete(it) }
        r.db.save(
            "settings",
            r.settings
                .put("start", start.toString())
                .put("energyStart", today.plusDays(1).toString()),
        )
        r.db.save(
            "rate",
            JSONObject().put("id", "test-rate").put("date", start.toString()).put("value", 2400),
        )
        r.reload()
    }

    @After
    fun close() {
        r.db.close()
        TimeZone.setDefault(originalZone)
    }

    private fun calories(id: String, day: LocalDate, hour: Int, kcal: Int) {
        r.db.save(
            "calorie",
            JSONObject().put("id", id).put("time", at(day, hour)).put("kcal", kcal),
        )
    }

    private fun food() =
        JSONObject()
            .put("id", "food")
            .put("name", "Seitan")
            .put("basis", 100)
            .put("unit", "g")
            .put("kcal", 200)
            .put("protein", 25)
            .put("fiber", 6)
            .put("carbs", 10)
            .put("fat", 2)

    private fun batch() =
        JSONObject()
            .put("id", "dish")
            .put("name", "Pasta")
            .put("available", at(start, 0))
            .put("completed", JSONObject.NULL)
            .put("share", 1)
            .put("portionTracking", true)
            .put(
                "ingredients",
                JSONArray().put(JSONObject().put("food", food()).put("quantity", 300)),
            )

    @Test
    fun blankDaysNeverCreateDeficitsAndTodayUsesAccruedExpenditure() {
        calories("yesterday", today.minusDays(1), 20, 1000)
        calories("today", today, 10, 300)
        val h = LoggedHistory(r, now)
        val s = h.summary(start, today.plusDays(1))
        assertEquals(2, s.loggedDays)
        assertEquals(1300.0, s.nutrition.values[0], 1e-8)
        assertEquals(650.0, s.averageIntake(), 1e-8)
        assertEquals(3600.0, s.expenditure, 1e-8)
        assertEquals(2300.0, s.deficit(), 1e-8)
        assertEquals(0, h.summary(start, start.plusDays(1)).loggedDays)
        assertTrue(h.summary(start, start.plusDays(1)).averageIntake().isNaN())
    }

    @Test
    fun zeroCalorieFoodCountsButSavedFoodsAndCookingDoNot() {
        r.db.save("food", food())
        r.db.save("batch", batch())
        assertTrue(LoggedHistory(r, now).days.isEmpty())
        val direct =
            batch().put("id", "drink").put("available", at(today, 9)).put("completed", at(today, 9))
        direct
            .getJSONArray("ingredients")
            .getJSONObject(0)
            .put(
                "food",
                food()
                    .put("kcal", 0)
                    .put("protein", 0)
                    .put("fiber", 0)
                    .put("carbs", 0)
                    .put("fat", 0),
            )
        r.db.save("direct", direct)
        val s = LoggedHistory(r, now).summary(today, today.plusDays(1))
        assertEquals(1, s.loggedDays)
        assertEquals(0.0, s.averageIntake(), 0.0)
        assertEquals(1200.0, s.deficit(), 1e-8)
    }

    @Test
    fun portionDayContributionsAndFinishReconstructWithoutDoubleCounting() {
        var b = BatchOperations.record(batch(), .3, at(today.minusDays(1), 19), "ate")
        b = BatchOperations.finish(b, at(today, 10))
        r.db.save("batch", b)
        val h = LoggedHistory(r, now)
        assertEquals(180.0, h.days[today.minusDays(1)]!!.nutrition.values[0], 1e-8)
        assertEquals(420.0, h.days[today]!!.nutrition.values[0], 1e-8)
        assertEquals(22.5, h.days[today.minusDays(1)]!!.nutrition.values[1], 1e-8)
        assertEquals(5.4, h.days[today.minusDays(1)]!!.nutrition.values[4], 1e-8)
        assertEquals(600.0, h.summary(start, today.plusDays(1)).nutrition.values[0], 1e-8)
        assertEquals(2, b.getJSONArray("eatingEvents").length())
    }

    @Test
    fun inferredLegacyDatesAreNotLoggedDaysAndTrackingStartIsRespected() {
        r.db.save("batch", batch().put("portionTracking", false))
        calories("old", start.minusDays(1), 12, 1000)
        calories("future", today, 15, 1000)
        assertTrue(LoggedHistory(r, now).days.isEmpty())
        calories("valid", today, 8, 200)
        assertEquals(setOf(today), LoggedHistory(r, now).days.keys)
        r.db.save("batch", batch().put("portionTracking", false).put("available", at(today, 0)))
        val summary = LoggedHistory(r, now).summary(today, today.plusDays(1))
        assertEquals(1, summary.loggedDays)
        assertEquals(150.0, summary.allocatedKcal, 1e-8)
        assertEquals(350.0, summary.nutrition.values[0], 1e-8)
    }

    @Test
    fun historicalRateAndDstUseLocalCalendarDays() {
        val yesterday = today.minusDays(1) // November 1: 25-hour day in America/Chicago
        assertEquals(25 * 3600000L, r.midnight(today) - r.midnight(yesterday))
        r.db.save(
            "rate",
            JSONObject().put("id", "new-rate").put("date", today.toString()).put("value", 2000),
        )
        calories("yesterday", yesterday, 20, 800)
        calories("today", today, 10, 200)
        val h = LoggedHistory(r, now)
        assertEquals(2400.0, h.days[yesterday]!!.expenditure, 1e-8)
        assertEquals(1000.0, h.days[today]!!.expenditure, 1e-8)
        assertEquals(2400.0, h.summary(start, today.plusDays(1)).deficit(), 1e-8)
    }

    @Test
    fun calendarWeekAndMonthBoundariesNeverDuplicateIntake() {
        val sunday = LocalDate.of(2026, 11, 1)
        assertEquals(sunday, LoggedHistory.sunday(today))
        assertEquals(sunday, LoggedHistory.sunday(sunday))
        calories("oct", sunday.minusDays(1), 22, 600)
        calories("nov", sunday, 10, 400)
        val h = LoggedHistory(r, now)
        val october = h.summary(LocalDate.of(2026, 10, 1), sunday)
        val november = h.summary(sunday, LocalDate.of(2026, 12, 1))
        assertEquals(600.0, october.nutrition.values[0], 0.0)
        assertEquals(400.0, november.nutrition.values[0], 0.0)
        assertEquals(1000.0, h.summary(start, today.plusDays(1)).nutrition.values[0], 0.0)
    }
}
