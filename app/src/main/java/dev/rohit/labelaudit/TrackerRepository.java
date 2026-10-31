package dev.rohit.labelaudit;

import android.content.Context;
import java.time.*;
import java.util.*;
import org.json.*;

/** Persistence and accounting shared by the Compose screens; no UI dependencies. */
public final class TrackerRepository {
  public final Data db;
  public JSONObject settings;
  public final ZoneId zone = ZoneId.systemDefault();

  public TrackerRepository(Context context) throws Exception {
    db = new Data(context);
    if (db.list("settings").isEmpty()) {
      db.save(
          "settings",
          new JSONObject()
              .put("id", "settings")
              .put("start", LocalDate.now(zone).toString())
              .put("duration", 48)
              .put("week", 7)
              .put("goal", 0)
              .put("policy", Estimator.POLICY));
      db.save(
          "rate",
          new JSONObject()
              .put("id", "initial-rate")
              .put("date", LocalDate.now(zone).toString())
              .put("value", 2000));
    }
    reload();
  }

  public void reload() {
    settings = db.list("settings").get(0);
    if (!settings.has("energyStart")) {
      // Explicit model boundary: preserve old total-expenditure rates and historical results.
      String today = LocalDate.now(zone).toString();
      android.database.sqlite.SQLiteDatabase sql = db.getWritableDatabase();
      sql.beginTransaction();
      try {
        db.save("resting", new JSONObject().put("date", today).put("value", 1766));
        settings.put("energyStart", today);
        db.save("settings", settings);
        sql.setTransactionSuccessful();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      } finally {
        sql.endTransaction();
      }
    }
  }

  public String display(double n) {
    return String.format(Locale.US, "%.2f", n).replaceAll("0+$", "").replaceAll("\\.$", "");
  }

  public long midnight(LocalDate d) {
    return d.atStartOfDay(zone).toInstant().toEpochMilli();
  }

  public double[] nutrition(JSONObject batch) throws Exception {
    double[] v = new double[4];
    JSONArray a = batch.getJSONArray("ingredients");
    String[] keys = {"kcal", "protein", "carbs", "fat"};
    for (int i = 0; i < a.length(); i++) {
      JSONObject x = a.getJSONObject(i), f = x.getJSONObject("food");
      double factor = x.getDouble("quantity") / f.getDouble("basis");
      for (int k = 0; k < 4; k++) v[k] += f.getDouble(keys[k]) * factor;
    }
    return v;
  }

  public double[] portionShares(JSONObject batch) throws Exception {
    JSONArray events = batch.optJSONArray("eatingEvents");
    double[] shares = new double[events == null ? 0 : events.length()];
    for (int i = 0; i < shares.length; i++)
      shares[i] = events.getJSONObject(i).getDouble("fraction");
    return shares;
  }

  public double loggedShare(JSONObject batch) throws Exception {
    return RecordedPortions.total(portionShares(batch), batch.getDouble("share"));
  }

  public double batchFraction(JSONObject batch, long from, long to, long now) throws Exception {
    JSONArray events = batch.optJSONArray("eatingEvents");
    if (batch.optBoolean("portionTracking") || (events != null && events.length() > 0)) {
      long[] times = new long[events == null ? 0 : events.length()];
      for (int i = 0; i < times.length; i++) times[i] = events.getJSONObject(i).getLong("time");
      return RecordedPortions.fraction(times, portionShares(batch), from, to, now);
    }
    return batch.getDouble("share")
        * Estimator.fraction(
            batch.getLong("available"),
            batch.isNull("completed") ? null : batch.getLong("completed"),
            (long) (settings.getDouble("duration") * 3600000),
            from,
            to,
            now);
  }

  public double[] intake(long from, long to, long now) throws Exception {
    double[] sum = new double[4];
    for (String kind : new String[] {"batch", "direct"})
      for (JSONObject j : db.list(kind)) {
        double fraction = batchFraction(j, from, to, now);
        double[] n = nutrition(j);
        for (int i = 0; i < 4; i++) sum[i] += n[i] * fraction;
      }
    for (JSONObject entry : db.list("calorie")) {
      long t = entry.getLong("time");
      sum[0] += entry.getDouble("kcal") * Estimator.fraction(t, t, 1, from, to, now);
    }
    return sum;
  }

  public double[] nutrientValues(JSONObject food) {
    double[] values = new double[Nutrients.KEYS.length];
    for (int i = 0; i < values.length; i++)
      values[i] =
          food.has(Nutrients.KEYS[i]) ? food.optDouble(Nutrients.KEYS[i], Double.NaN) : Double.NaN;
    return values;
  }

  public Nutrients.Total batchNutrients(JSONObject batch, double fraction) throws Exception {
    Nutrients.Total result = new Nutrients.Total();
    JSONArray ingredients = batch.getJSONArray("ingredients");
    for (int i = 0; i < ingredients.length(); i++) {
      JSONObject item = ingredients.getJSONObject(i), food = item.getJSONObject("food");
      result.add(
          nutrientValues(food), fraction * item.getDouble("quantity") / food.getDouble("basis"));
    }
    return result;
  }

  public Nutrients.Total nutrientTotals(long from, long to, long now) throws Exception {
    return nutrientTotals(from, to, now, true);
  }

  private Nutrients.Total nutrientTotals(long from, long to, long now, boolean includeCalorieOnly)
      throws Exception {
    Nutrients.Total result = new Nutrients.Total();
    for (String kind : new String[] {"batch", "direct"})
      for (JSONObject batch : db.list(kind)) {
        double fraction = batchFraction(batch, from, to, now);
        if (fraction <= 0) continue;
        JSONArray ingredients = batch.getJSONArray("ingredients");
        for (int i = 0; i < ingredients.length(); i++) {
          JSONObject item = ingredients.getJSONObject(i), food = item.getJSONObject("food");
          result.add(
              nutrientValues(food),
              fraction * item.getDouble("quantity") / food.getDouble("basis"));
        }
      }
    if (includeCalorieOnly)
      for (JSONObject entry : db.list("calorie")) {
        long t = entry.getLong("time");
        if (t >= from && t < Math.min(to, now)) {
          double[] n = new double[Nutrients.KEYS.length];
          Arrays.fill(n, Double.NaN);
          n[0] = entry.getDouble("kcal");
          result.add(n, 1);
        }
      }
    return result;
  }

  public String nutrientText(Nutrients.Total total, int i) {
    if (total.missing[i] && !total.known[i]) return "—";
    if (i == 0) return String.format(Locale.US, "%,.0f kcal", total.values[i]);
    return (total.missing[i] ? "≥" : "")
        + display(total.values[i] * Nutrients.displayFactor(i))
        + " "
        + Nutrients.unit(i);
  }

  public double goal(int i) {
    JSONObject goals = settings.optJSONObject("nutrientGoals");
    return goals == null ? 0 : goals.optDouble(Nutrients.KEYS[i], 0);
  }

  public double rate(LocalDate date) throws Exception {
    String best = "";
    double value = 0;
    for (JSONObject r : db.list("rate")) {
      String d = r.getString("date");
      if (d.compareTo(date.toString()) <= 0 && d.compareTo(best) > 0) {
        best = d;
        value = r.getDouble("value");
      }
    }
    return value;
  }

  public double restingRate(LocalDate date) throws Exception {
    String best = "";
    double value = 0;
    for (JSONObject r : db.list("resting")) {
      String d = r.getString("date");
      if (d.compareTo(date.toString()) <= 0 && d.compareTo(best) > 0) {
        best = d;
        value = r.getDouble("value");
      }
    }
    return value;
  }

  public double extraActivity(LocalDate date) throws Exception {
    double result = 0;
    for (JSONObject a : db.list("activity"))
      if (date.toString().equals(a.getString("date"))) result += a.getDouble("kcal");
    return result;
  }

  public DailyExpenditure.Estimate dailyExpenditure(LocalDate date, long now) throws Exception {
    if (date.isBefore(LocalDate.parse(settings.getString("start")))
        || date.isAfter(Instant.ofEpochMilli(now).atZone(zone).toLocalDate()))
      return new DailyExpenditure.Estimate(0, 0, null, 0, false, true, false);
    long from = midnight(date), to = Math.min(now, midnight(date.plusDays(1)));
    if (date.isBefore(LocalDate.parse(settings.getString("energyStart")))) {
      double old =
          Energy.accrued(
              from,
              to,
              midnight(LocalDate.parse(settings.getString("start"))),
              zone,
              d -> {
                try {
                  return rate(d);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      return new DailyExpenditure.Estimate(old, 0, null, 0, false, false, true);
    }
    JSONObject cache = db.stepCache(date.toString(), zone.getId());
    Long steps = cache.has("steps") && !cache.isNull("steps") ? cache.getLong("steps") : null;
    long ttl =
        date.equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
            ? 15 * 60000L
            : 7 * 86400000L;
    String access = db.stepCache("_connection", "_").optString("status", "ok");
    boolean stale =
        !"ok".equals(access)
            || !"ok".equals(cache.optString("status"))
            || now - cache.optLong("synced", 0) > ttl
            || (date.equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
                && !"local-day-v2".equals(cache.optString("queryPolicy")))
            || (date.isBefore(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
                && cache.optLong("through", 0) < midnight(date.plusDays(1)));
    double accruedResting =
        restingRate(date) * (to - from) / (double) (midnight(date.plusDays(1)) - from);
    double calorieOnlyKcal = 0;
    for (JSONObject entry : db.list("calorie")) {
      long time = entry.getLong("time");
      if (time >= from && time < to) calorieOnlyKcal += entry.getDouble("kcal");
    }
    // Each calorie-only record uses the fallback INSTEAD of macro TEF. The public intake
    // nutrient totals still mark its macros unknown; no fabricated grams are persisted.
    return DailyExpenditure.calculate(
        accruedResting,
        nutrientTotals(from, to, now, false),
        steps,
        extraActivity(date),
        stale,
        calorieOnlyKcal);
  }
}
