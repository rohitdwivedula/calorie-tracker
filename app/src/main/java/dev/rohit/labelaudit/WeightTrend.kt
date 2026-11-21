package dev.rohit.labelaudit

import java.time.LocalDate
import kotlin.math.roundToLong

/** Straight-line least-squares fit, with equal weight for each measured day. */
internal fun fittedWeightEndpoints(daily: Map<LocalDate, Double>): Pair<Double, Double>? {
    if (daily.size < 2) return null
    val first = daily.keys.min().toEpochDay()
    val last = daily.keys.max().toEpochDay()
    val meanDay = daily.keys.map { it.toEpochDay() - first }.average()
    val meanKg = daily.values.average()
    var covariance = 0.0
    var variance = 0.0
    for ((date, kg) in daily) {
        val offset = date.toEpochDay() - first - meanDay
        covariance += offset * (kg - meanKg)
        variance += offset * offset
    }
    val slope = covariance / variance
    return (meanKg - slope * meanDay) to (meanKg + slope * (last - first - meanDay))
}

internal data class WeightChange(
    val kg: Double,
    val start: LocalDate,
    val end: LocalDate,
    val daysPerEnd: Int,
) {
    val days
        get() = end.toEpochDay() - start.toEpochDay()

    fun suggestedKg(balance: LoggedHistory.Summary): Double? =
        if (balance.loggedDays > 0) -balance.deficit() / 7700.0 else null
}

internal fun weightChange(daily: Map<LocalDate, Double>): WeightChange? {
    if (daily.size < 2) return null
    val daysPerEnd = minOf(3, daily.size / 2)
    val dates = daily.keys.sorted()
    val early = dates.take(daysPerEnd)
    val late = dates.takeLast(daysPerEnd)
    fun center(group: List<LocalDate>) =
        LocalDate.ofEpochDay(group.map { it.toEpochDay() }.average().roundToLong())
    return WeightChange(
        late.map(daily::getValue).average() - early.map(daily::getValue).average(),
        center(early),
        center(late),
        daysPerEnd,
    )
}
