package dev.rohit.labelaudit;

import java.time.*;
import java.util.*;
import org.json.*;

/**
 * Reconstructible view: only dates with recorded intake are eligible for balance/averages. No
 * inferred dates or aggregates are written to the database.
 */
public final class LoggedHistory {
  public static final class Day {
    public final LocalDate date;
    public final Nutrients.Total nutrition;
    public final double expenditure, allocatedKcal;
    public final boolean incomplete;

    Day(
        LocalDate date,
        Nutrients.Total nutrition,
        DailyExpenditure.Estimate expenditure,
        double allocatedKcal) {
      this.date = date;
      this.nutrition = nutrition;
      this.expenditure = expenditure.total;
      this.incomplete = expenditure.incomplete();
      this.allocatedKcal = allocatedKcal;
    }
  }

  public static final class Summary {
    public final Nutrients.Total nutrition = new Nutrients.Total();
    public int loggedDays, incompleteDays;
    public double expenditure, allocatedKcal;

    public double deficit() {
      return expenditure - nutrition.values[0];
    }

    public double averageIntake() {
      return loggedDays == 0 ? Double.NaN : nutrition.values[0] / loggedDays;
    }
  }

  public final NavigableMap<LocalDate, Day> days = new TreeMap<>();
  public final long asOf;
  public final LocalDate today;

  public LoggedHistory(TrackerRepository repo, long asOf) throws Exception {
    this.asOf = asOf;
    today = Instant.ofEpochMilli(asOf).atZone(repo.zone).toLocalDate();
    LocalDate start = LocalDate.parse(repo.settings.getString("start"));
    Set<LocalDate> logged = new TreeSet<>();
    List<JSONObject> batches = repo.db.list("batch");
    for (JSONObject b : batches) {
      JSONArray events = b.optJSONArray("eatingEvents");
      if (events != null)
        for (int i = 0; i < events.length(); i++) {
          JSONObject e = events.getJSONObject(i);
          if (e.getDouble("fraction") > 0) mark(logged, e.getLong("time"), repo.zone, start);
        }
    }
    for (JSONObject d : repo.db.list("direct"))
      if (!d.isNull("completed") && d.getDouble("share") > 0)
        mark(logged, d.getLong("completed"), repo.zone, start);
    for (JSONObject c : repo.db.list("calorie")) mark(logged, c.getLong("time"), repo.zone, start);
    for (LocalDate date : logged) {
      long from = repo.midnight(date), to = Math.min(asOf, repo.midnight(date.plusDays(1)));
      double allocatedKcal = 0;
      for (JSONObject batch : batches) {
        JSONArray events = batch.optJSONArray("eatingEvents");
        if (!batch.optBoolean("portionTracking") && (events == null || events.length() == 0))
          allocatedKcal +=
              repo.batchNutrients(batch, repo.batchFraction(batch, from, to, asOf)).values[0];
      }
      days.put(
          date,
          new Day(
              date,
              repo.nutrientTotals(from, to, asOf),
              repo.dailyExpenditure(date, asOf),
              allocatedKcal));
    }
  }

  private void mark(Set<LocalDate> dates, long time, ZoneId zone, LocalDate start) {
    LocalDate date = Instant.ofEpochMilli(time).atZone(zone).toLocalDate();
    if (time < asOf && !date.isBefore(start)) dates.add(date);
  }

  public Summary summary(LocalDate from, LocalDate until) {
    Summary result = new Summary();
    if (!from.isBefore(until)) return result;
    for (Day day : days.subMap(from, true, until, false).values()) {
      result.loggedDays++;
      if (day.incomplete) result.incompleteDays++;
      result.expenditure += day.expenditure;
      result.allocatedKcal += day.allocatedKcal;
      for (int i = 0; i < Nutrients.KEYS.length; i++) {
        result.nutrition.values[i] += day.nutrition.values[i];
        result.nutrition.known[i] |= day.nutrition.known[i];
        result.nutrition.missing[i] |= day.nutrition.missing[i];
      }
    }
    return result;
  }

  public static LocalDate weekStart(LocalDate date, int dayOfWeek) {
    if (dayOfWeek < 1 || dayOfWeek > 7) throw new IllegalArgumentException("Invalid week start");
    return date.minusDays(Math.floorMod(date.getDayOfWeek().getValue() - dayOfWeek, 7));
  }

  public static LocalDate sunday(LocalDate date) {
    return weekStart(date, 7);
  }
}
