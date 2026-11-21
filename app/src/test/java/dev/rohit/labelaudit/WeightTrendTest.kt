package dev.rohit.labelaudit

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WeightTrendTest {
    private val start = LocalDate.of(2026, 11, 1)

    @Test
    fun smoothWindowsMatchTheirCentersAndRequireCoverage() {
        val daily =
            listOf(0, 2, 4, 28, 30, 32).associate {
                start.plusDays(it.toLong()) to if (it < 5) 80.0 + it else 78.0
            }
        val change = weightChange(daily)!!
        assertTrue(change.smoothed)
        assertEquals(-4.0, change.kg, 0.0)
        assertEquals(start.plusDays(2), change.start)
        assertEquals(start.plusDays(30), change.end)
        val balance =
            LoggedHistory.Summary().apply {
                loggedDays = 22
                expenditure = 7700.0
            }
        assertNull(change.suggestedKg(balance))
        balance.loggedDays++
        assertEquals(-1.0, change.suggestedKg(balance)!!, 1e-9)
    }

    @Test
    fun sparseReadingsUseDailyMeansAndOneDayHasNoChange() {
        val daily = mapOf(start to 70.0)
        assertNull(weightChange(daily))
        val change = weightChange(daily + (start.plusDays(1) to 69.0))!!
        assertFalse(change.smoothed)
        assertEquals(-1.0, change.kg, 0.0)
        assertNull(change.suggestedKg(LoggedHistory.Summary().apply { loggedDays = 1 }))
    }
}
