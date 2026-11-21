package dev.rohit.labelaudit

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WeightTrendTest {
    private val start = LocalDate.of(2026, 11, 1)

    @Test
    fun fitUsesElapsedDaysAndRequiresTwoMeasuredDays() {
        val daily = mapOf(start to 80.0, start.plusDays(1) to 79.5, start.plusDays(10) to 75.0)
        val fit = fittedWeightEndpoints(daily)!!
        assertEquals(80.0, fit.first, 1e-9)
        assertEquals(75.0, fit.second, 1e-9)
        assertNull(fittedWeightEndpoints(mapOf(start to 80.0)))
    }

    @Test
    fun threeMeasuredDaysAtEachEndAndOneLoggedDaySuffice() {
        val daily =
            listOf(0, 2, 4, 28, 30, 32).associate {
                start.plusDays(it.toLong()) to if (it < 5) 80.0 + it else 78.0
            }
        val change = weightChange(daily)!!
        assertEquals(3, change.daysPerEnd)
        assertEquals(-4.0, change.kg, 0.0)
        assertEquals(start.plusDays(2), change.start)
        assertEquals(start.plusDays(30), change.end)
        val balance =
            LoggedHistory.Summary().apply {
                loggedDays = 0
                expenditure = 7700.0
            }
        assertNull(change.suggestedKg(balance))
        balance.loggedDays++
        assertEquals(-1.0, change.suggestedKg(balance)!!, 1e-9)
    }

    @Test
    fun fiveDaysUseTwoAtEachEndWithoutOverlap() {
        val daily = listOf(80.0, 82.0, 99.0, 78.0, 80.0)
            .mapIndexed { index, kg -> start.plusDays(index.toLong()) to kg }.toMap()
        val change = weightChange(daily)!!
        assertEquals(2, change.daysPerEnd)
        assertEquals(-2.0, change.kg, 0.0)
    }

    @Test
    fun sparseReadingsUseDailyMeansAndOneDayHasNoChange() {
        val daily = mapOf(start to 70.0)
        assertNull(weightChange(daily))
        val change = weightChange(daily + (start.plusDays(1) to 69.0))!!
        assertEquals(1, change.daysPerEnd)
        assertEquals(-1.0, change.kg, 0.0)
        assertEquals(0.0, change.suggestedKg(LoggedHistory.Summary().apply { loggedDays = 1 })!!, 0.0)
    }
}
