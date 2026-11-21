package dev.rohit.labelaudit

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private fun pounds(kg: Double) = String.format(Locale.US, "%.1f lb", kg * 2.20462262)

private fun changeText(kg: Double) =
    when {
        abs(kg) * 2.20462262 < .05 -> "0.0 lb"
        kg < 0 -> "↓ ${pounds(-kg)}"
        else -> "↑ ${pounds(kg)}"
    }

@Composable
internal fun WeightScreen(
    v: TrackerViewModel,
    connect: () -> Unit,
    h: LoggedHistory,
    bars: List<BalanceBar>,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { v.refreshWeight() }
    val r = v.repo
    val short = DateTimeFormatter.ofPattern("MMM d")
    Page {
        val start = bars.first().start
        val end = bars.last().end
        val items =
            remember(start, end, v.weightRevision, v.revision) {
                r.db.chartWeights(start, end, r.zone, Instant.now())
            }
        val daily =
            items.groupBy { it.date }.mapValues { (_, rows) -> rows.map { it.kg }.average() }
        val change = weightChange(daily)
        var selectedDate by rememberSaveable(start.toString(), end.toString()) {
            mutableStateOf<String?>(null)
        }
        val period = h.summary(start, end)
        Section("Weight trend") {
            if (daily.isNotEmpty()) {
                val selected = selectedDate?.let(LocalDate::parse)
                WeightChart(start, end.minusDays(1), daily, selected) { date ->
                    selectedDate = if (selected == date) null else date.toString()
                }
                val low = pounds(daily.values.min())
                val high = pounds(daily.values.max())
                Text(
                    if (low == high) "$low · daily average" else "$low – $high · daily averages",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (selected != null && selected in daily) {
                    val readings = items.filter { it.date == selected }
                    Text(
                        "${selected.format(short)} · ${pounds(daily.getValue(selected))} average",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    for (w in readings) {
                        val label = w.time?.let { whenText(it, r.zone) } ?: w.date.format(short)
                        InfoRow(
                            if (w.imported != null) "$label · imported" else label,
                            pounds(w.kg),
                        )
                    }
                }
            } else
                Text(
                    if (v.weightStatus == "ok") "No weigh-ins in this range"
                    else "No saved weigh-ins in this range"
                )
            if (change != null) {
                InfoRow(
                    if (change.daysPerEnd > 1) "Smoothed scale change" else "Scale change",
                    changeText(change.kg),
                )
                Text(
                    if (change.daysPerEnd > 1) "Average of the first ${change.daysPerEnd} and last ${change.daysPerEnd} measured days."
                    else "First and last measured days.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (items.isNotEmpty()) Text("Scale change needs two measured days.")
        }
        TextButton(onClick = { v.go("weight-import") }) { Text("Import past weigh-ins") }
        Section("Energy comparison") {
            InfoRow(
                if (period.deficit() < 0) "Estimated surplus" else "Estimated deficit",
                if (period.loggedDays == 0) "—" else "${kcal(abs(period.deficit()))} kcal",
            )
            val elapsed =
                maxOf(0L, minOf(end, h.today.plusDays(1)).toEpochDay() - start.toEpochDay())
            Text(
                "${period.loggedDays}/$elapsed days logged",
                style = MaterialTheme.typography.bodySmall,
            )
            if (change != null) {
                val matched = h.summary(change.start, change.end)
                val suggested = change.suggestedKg(matched)
                if (suggested != null) {
                    InfoRow("Suggested by logged balance", changeText(suggested))
                    Text(
                        "${change.start.format(short)} to ${change.end.format(short)} · ${matched.loggedDays}/${change.days} days logged · rough estimate at 3,500 kcal/lb",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else
                    Text(
                        "${change.start.format(short)} to ${change.end.format(short)} · " +
                            "No food logged between these weight endpoints.",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
        Section("Health Connect") {
            val synced = remember(v.weightRevision) { r.db.lastWeightSync() }
            if (synced > 0) {
                val count =
                    remember(v.weightRevision) {
                        r.db.weighIns(synced - Duration.ofDays(30).toMillis(), synced).size
                    }
                Text("Last checked ${whenText(synced, r.zone)}")
                Text(
                    "$count ${if (count == 1) "weigh-in" else "weigh-ins"} found in the last 30 days"
                )
            }
            when (v.weightStatus) {
                "permission",
                "update" -> TextButton(onClick = connect) { Text("Connect weight") }
                "unavailable" -> Text("Health Connect unavailable")
                else -> {
                    if (v.weightStatus == "error") Text("Refresh failed. Showing saved readings.")
                    TextButton(
                        onClick = { v.refreshWeight(recheckRecent = true) },
                        enabled = v.weightStatus != "loading",
                    ) {
                        Text(
                            if (v.weightStatus == "loading") "Refreshing…" else "Refresh weigh-ins"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeightChart(
    start: LocalDate,
    last: LocalDate,
    daily: Map<LocalDate, Double>,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
) {
    val dates = remember(daily) { daily.keys.sorted() }
    val span = last.toEpochDay() - start.toEpochDay()
    fun fraction(date: LocalDate) =
        if (span == 0L) .5f else (date.toEpochDay() - start.toEpochDay()).toFloat() / span

    val fit = remember(daily) { fittedWeightEndpoints(daily) }
    val low = minOf(daily.values.min(), fit?.first ?: Double.POSITIVE_INFINITY, fit?.second ?: Double.POSITIVE_INFINITY)
    val high = maxOf(daily.values.max(), fit?.first ?: Double.NEGATIVE_INFINITY, fit?.second ?: Double.NEGATIVE_INFINITY)
    val ink = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 6.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 7.dp.toPx()
            for (level in listOf(0f, .5f, 1f)) {
                val y = inset + level * (size.height - 2 * inset)
                drawLine(grid, Offset(0f, y), Offset(size.width, y))
            }
            fun position(date: LocalDate, kg: Double): Offset {
                val level = if (high == low) .5f else ((high - kg) / (high - low)).toFloat()
                return Offset(size.width * fraction(date), inset + level * (size.height - 2 * inset))
            }
            if (fit != null) {
                drawLine(
                    color = ink.copy(alpha = .75f),
                    start = position(dates.first(), fit.first),
                    end = position(dates.last(), fit.second),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(.2.dp.toPx(), 5.dp.toPx())),
                )
            }
            for (date in dates) {
                val center = position(date, daily.getValue(date))
                if (date == selected) drawCircle(ink.copy(alpha = .3f), 7.dp.toPx(), center)
                drawCircle(ink, 3.5.dp.toPx(), center)
            }
        }
        // Split the touch area halfway between adjacent dots, including gaps in the log.
        Row(Modifier.fillMaxSize()) {
            var left = 0f
            dates.forEachIndexed { i, date ->
                val right = dates.getOrNull(i + 1)?.let { (fraction(date) + fraction(it)) / 2 } ?: 1f
                Box(
                    Modifier.weight(right - left).fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            onSelect(date)
                        }
                        .semantics { contentDescription = "$date: ${pounds(daily.getValue(date))}" }
                )
                left = right
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val style = MaterialTheme.typography.labelSmall
        val format = DateTimeFormatter.ofPattern(if (span >= 365) "MMM yy" else "MMM d")
        val dateOffsets = when {
            span == 0L -> listOf(0L)
            span > 1 && maxWidth >= 216.dp * density.fontScale -> listOf(0L, span / 2, span)
            else -> listOf(0L, span)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (span == 0L) Arrangement.Center else Arrangement.SpaceBetween,
        ) {
            dateOffsets.forEach { offset ->
                Text(start.plusDays(offset).format(format), style = style, maxLines = 1)
            }
        }
    }
}
