package dev.rohit.labelaudit

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
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
internal fun WeightScreen(v: TrackerViewModel, connect: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { v.refreshWeight() }
    val r = v.repo
    val h = remember(v.revision) { LoggedHistory(r, System.currentTimeMillis()) }
    val short = DateTimeFormatter.ofPattern("MMM d")
    PeriodPage(h.today, r.settings.getInt("week"), 1) { mode, bars ->
        val start = bars.first().start
        val end = bars.last().end
        val items =
            remember(start, end, v.weightRevision) {
                r.db.weighIns(
                    r.midnight(start),
                    minOf(r.midnight(end), System.currentTimeMillis() + 1),
                )
            }
        fun date(w: WeighIn) = Instant.ofEpochMilli(w.time).atZone(r.zone).toLocalDate()
        val daily = items.groupBy(::date).mapValues { (_, rows) -> rows.map { it.kg }.average() }
        val change = weightChange(daily)
        var selected by rememberSaveable { mutableIntStateOf(-1) }
        val period = h.summary(start, end)
        Section("Weight trend") {
            if (items.isNotEmpty()) {
                val points =
                    bars.map { bar ->
                        if (mode == 0) items.filter { date(it) == bar.start }.map { it.kg }
                        else
                            daily
                                .filterKeys { it >= bar.start && it < bar.end }
                                .values
                                .takeIf { it.isNotEmpty() }
                                ?.let { listOf(it.average()) }
                                .orEmpty()
                    }
                val all = points.flatten()
                val low = all.min()
                val high = all.max()
                val scroll = rememberScrollState()
                val lastMeasured = points.indexOfLast { it.isNotEmpty() }
                LaunchedEffect(lastMeasured, scroll.maxValue) {
                    if (lastMeasured >= 0)
                        scroll.scrollTo(scroll.maxValue * lastMeasured / (bars.size - 1))
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(scroll),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    bars.forEachIndexed { i, bar ->
                        Column(
                            Modifier.width(if (mode == 0) 36.dp else 44.dp)
                                .clickable { selected = if (selected == i) -1 else i }
                                .semantics {
                                    contentDescription =
                                        "${bar.start}: " +
                                            (points[i]
                                                .takeIf { it.isNotEmpty() }
                                                ?.joinToString { pounds(it) } ?: "No weigh-ins")
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.height(120.dp).fillMaxWidth()) {
                                for (value in points[i]) {
                                    val position =
                                        if (high == low) .5 else (high - value) / (high - low)
                                    Box(
                                        Modifier.align(Alignment.TopCenter)
                                            .offset(y = 110.dp * position.toFloat())
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                            HorizontalDivider(
                                color =
                                    if (i == selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                            )
                            Text(bar.label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(
                    "${pounds(low)} – ${pounds(high)} · ${if(mode==0) "weigh-ins" else "averages of measured days"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (selected in bars.indices) {
                    val bar = bars[selected]
                    val readings = items.filter { date(it) >= bar.start && date(it) < bar.end }
                    Text(
                        if (readings.isEmpty()) "No weigh-ins in this period"
                        else "${bar.start.format(short)} · readings",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    for (w in readings) InfoRow(whenText(w.time, r.zone), pounds(w.kg))
                }
            } else
                Text(
                    if (v.weightStatus == "ok") "No weigh-ins in this range"
                    else "No saved weigh-ins in this range"
                )
            if (change != null) {
                InfoRow(
                    if (change.smoothed) "Smoothed scale change" else "Scale change",
                    changeText(change.kg),
                )
                Text(
                    if (change.smoothed) "First and last 7-day averages, using measured days."
                    else "First and last measured days.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (items.isNotEmpty()) Text("Scale change needs two measured days.")
        }
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
                        "Comparison needs 3 weeks between trend endpoints and food logs on 80% of those days.",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
        Section("Health Connect") {
            val synced = remember(v.weightRevision) { r.db.lastWeightSync() }
            if (synced > 0) Text("Last synced ${whenText(synced,r.zone)}")
            when (v.weightStatus) {
                "permission",
                "update" -> TextButton(onClick = connect) { Text("Connect weight") }
                "unavailable" -> Text("Health Connect unavailable")
                else -> {
                    if (v.weightStatus == "error") Text("Refresh failed. Showing saved readings.")
                    TextButton(onClick = v::refreshWeight, enabled = v.weightStatus != "loading") {
                        Text(
                            if (v.weightStatus == "loading") "Refreshing…" else "Refresh weigh-ins"
                        )
                    }
                }
            }
        }
    }
}
