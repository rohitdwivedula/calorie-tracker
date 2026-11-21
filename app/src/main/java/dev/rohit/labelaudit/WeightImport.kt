package dev.rohit.labelaudit

import java.time.*
import java.util.UUID
import kotlin.math.abs
import org.json.JSONObject

internal data class ImportedWeight(
    val date: LocalDate,
    val time: LocalTime?,
    val zone: ZoneId,
    val unit: String,
    val weight: Double,
    val fat: Double?,
) {
    val instant = time?.let { date.atTime(it).atZone(zone).toInstant() }
    val kg = if (unit == "lb") weight * 0.45359237 else weight
    val id =
        "imported-weight-" +
            UUID.nameUUIDFromBytes(
                "${instant ?: date}|${Math.round(kg * 1_000_000)}".toByteArray(Charsets.UTF_8)
            )

    fun json(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("date", date.toString())
            .put("time", time?.toString())
            .put("timeZone", zone.id)
            .put("unit", unit)
            .put("weight", weight)
            .put("bodyFatPercent", fat)

    fun matches(row: WeighIn): Boolean =
        instant != null &&
            instant.epochSecond / 60 == row.time / 60000 &&
            abs(kg - row.kg) <= (if (unit == "lb") 0.023 else 0.051)

    fun displayDate(displayZone: ZoneId): LocalDate =
        instant?.atZone(displayZone)?.toLocalDate() ?: date
}

internal object WeightImport {
    const val kind = "importedWeight"
    const val prompt =
        """Transcribe all fully visible weigh-ins from the screenshot or earlier conversation into JSON only. Use this format: {"schemaVersion":1,"unit":"lb","weighIns":[{"date":"2026-01-31","time":"14:25","weight":150.5,"bodyFatPercent":20.1}]}. Use lb or kg as shown. Keep distinct readings. Include time in 24-hour HH:mm only when visible, and bodyFatPercent only when shown. Include a top-level timeZone if known. Use the year from context; ask if ambiguous. Skip cut-off values and do not invent data."""

    private fun row(j: JSONObject, zone: ZoneId, unit: String): ImportedWeight {
        require(unit in setOf("lb", "kg")) { "Use lb or kg" }
        fun number(key: String): Double =
            (j.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: error("Invalid $key")
        val weight = number("weight")
        val fat = if (j.isNull("bodyFatPercent")) null else number("bodyFatPercent")
        require(weight > 0 && weight <= 2000 && (fat == null || fat in 0.0..100.0)) {
            "Invalid weight or body fat"
        }
        return ImportedWeight(
            LocalDate.parse(j.getString("date")),
            if (j.isNull("time")) null else LocalTime.parse(j.getString("time")),
            zone,
            unit,
            weight,
            fat,
        )
    }

    fun stored(j: JSONObject): ImportedWeight =
        row(j, ZoneId.of(j.getString("timeZone")), j.getString("unit"))

    @JvmStatic
    fun validate(j: JSONObject) {
        require(stored(j).id == j.getString("id")) { "Invalid imported weight identity" }
    }

    fun parse(text: String, defaultZone: ZoneId): List<ImportedWeight> {
        require(text.length <= 1024 * 1024) { "Weight JSON exceeds 1 MB" }
        val root = JSONObject(text)
        require((root.opt("schemaVersion") as? Number)?.toDouble() == 1.0) {
            "Expected schemaVersion 1"
        }
        val zone = ZoneId.of(root.optString("timeZone", defaultZone.id))
        val entries = root.getJSONArray("weighIns")
        require(entries.length() in 1..5000) { "Expected 1 to 5000 weigh-ins" }
        return (0 until entries.length())
            .map { i ->
                try {
                    row(entries.getJSONObject(i), zone, root.getString("unit"))
                } catch (e: Exception) {
                    throw IllegalArgumentException("Reading ${i+1}: ${e.message}", e)
                }
            }
            .distinctBy { it.id }
    }
}

internal data class WeightCandidate(
    val row: ImportedWeight,
    val sameDay: List<WeighIn>,
    val duplicate: Boolean,
)

internal fun Data.weightCandidates(rows: List<ImportedWeight>): List<WeightCandidate> {
    val saved = list(WeightImport.kind).mapTo(mutableSetOf()) { it.getString("id") }
    return rows.map { row ->
        val start = row.date.atStartOfDay(row.zone).toInstant().toEpochMilli()
        val end = row.date.plusDays(1).atStartOfDay(row.zone).toInstant().toEpochMilli()
        val nearby = weighIns(start, end)
        WeightCandidate(row, nearby, row.id in saved || nearby.any(row::matches))
    }
}

internal fun Data.importWeights(rows: List<ImportedWeight>, confirmedOverlaps: Set<String>): Int {
    val sql = writableDatabase
    sql.beginTransaction()
    try {
        val selected =
            weightCandidates(rows).filter {
                !it.duplicate && (it.sameDay.isEmpty() || it.row.id in confirmedOverlaps)
            }
        selected.forEach { save(WeightImport.kind, it.row.json()) }
        sql.setTransactionSuccessful()
        return selected.size
    } finally {
        sql.endTransaction()
    }
}

internal data class DisplayWeight(
    val date: LocalDate,
    val time: Long?,
    val kg: Double,
    val imported: ImportedWeight? = null,
)

internal fun Data.chartWeights(
    start: LocalDate,
    end: LocalDate,
    zone: ZoneId,
    now: Instant,
): List<DisplayWeight> {
    val hc =
        weighIns(
            start.atStartOfDay(zone).toInstant().toEpochMilli(),
            minOf(end.atStartOfDay(zone).toInstant().toEpochMilli(), now.toEpochMilli() + 1),
        )
    val measured =
        hc.map {
            DisplayWeight(Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate(), it.time, it.kg)
        }
    val byMinute = hc.groupBy { it.time / 60000 }
    val imported =
        list(WeightImport.kind).map(WeightImport::stored).mapNotNull { row ->
            val date = row.displayDate(zone)
            if (
                date < start ||
                    date >= end ||
                    date > now.atZone(zone).toLocalDate() ||
                    row.instant?.let {
                        it > now || byMinute[it.epochSecond / 60].orEmpty().any(row::matches)
                    } == true
            )
                null
            else DisplayWeight(date, row.instant?.toEpochMilli(), row.kg, row)
        }
    return (measured + imported).sortedWith(compareBy({ it.date }, { it.time }))
}
