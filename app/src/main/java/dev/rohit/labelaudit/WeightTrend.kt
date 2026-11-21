package dev.rohit.labelaudit

import java.time.LocalDate
import kotlin.math.roundToLong

internal data class WeightChange(
    val kg: Double,
    val start: LocalDate,
    val end: LocalDate,
    val smoothed: Boolean,
) {
    val days
        get() = end.toEpochDay() - start.toEpochDay()

    fun suggestedKg(balance: LoggedHistory.Summary): Double? =
        if (days >= 21 && balance.loggedDays >= days * .8) -balance.deficit() / 7700.0 else null
}

internal fun weightChange(daily: Map<LocalDate, Double>): WeightChange? {
    if (daily.size < 2) return null
    val first = daily.keys.min()
    val last = daily.keys.max()
    val early = daily.filterKeys { it < first.plusDays(7) }
    val late = daily.filterKeys { it > last.minusDays(7) }
    if (last >= first.plusDays(14) && early.size >= 3 && late.size >= 3) {
        // Match calorie balance to the centers of the two groups of measured days.
        fun center(dates: Set<LocalDate>) =
            LocalDate.ofEpochDay(dates.map { it.toEpochDay() }.average().roundToLong())
        return WeightChange(
            late.values.average() - early.values.average(),
            center(early.keys),
            center(late.keys),
            true,
        )
    }
    return WeightChange(daily.getValue(last) - daily.getValue(first), first, last, false)
}
