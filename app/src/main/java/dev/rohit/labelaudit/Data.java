package dev.rohit.labelaudit;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import org.json.*;

/** Authoritative documents only; estimates are never persisted. Versioned SQLite schema. */
public final class Data extends SQLiteOpenHelper {
  public Data(Context c) {
    super(c, "nutrition.db", null, 2);
  }

  public void onCreate(SQLiteDatabase d) {
    d.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY, kind TEXT NOT NULL, body TEXT NOT NULL)");
    d.execSQL("CREATE INDEX records_kind ON records(kind)");
  }

  public void onUpgrade(SQLiteDatabase d, int a, int b) {
    if (a == 1 && b == 2) {
      d.execSQL("CREATE INDEX IF NOT EXISTS records_kind ON records(kind)");
      return;
    }
    throw new IllegalStateException("Unsupported migration " + a + " to " + b);
  }

  public List<JSONObject> list(String kind) {
    List<JSONObject> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT body FROM records WHERE kind=? ORDER BY rowid DESC", new String[] {kind})) {
      while (c.moveToNext())
        try {
          out.add(new JSONObject(c.getString(0)));
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
    }
    return out;
  }

  public void save(String kind, JSONObject j) {
    try {
      if (!j.has("id")) j.put("id", UUID.randomUUID().toString());
      validate(kind, j);
      ContentValues v = new ContentValues();
      v.put("id", j.getString("id"));
      v.put("kind", kind);
      v.put("body", j.toString());
      getWritableDatabase()
          .insertWithOnConflict("records", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    } catch (Exception e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  public void delete(JSONObject j) {
    getWritableDatabase().delete("records", "id=?", new String[] {j.optString("id")});
  }

  static void number(JSONObject j, String k, double min, double max) throws Exception {
    double n = j.getDouble(k);
    if (!Double.isFinite(n) || n < min || n > max) throw new Exception("Invalid " + k);
  }

  static void validate(String kind, JSONObject j) throws Exception {
    if (j.getString("id").isEmpty()) throw new Exception("Missing ID");
    if (kind.equals("food")) {
      if (j.getString("name").trim().isEmpty()) throw new Exception("Name required");
      number(j, "basis", 0.001, 100000);
      if (j.has("servingGrams")) number(j, "servingGrams", 0.001, 100000);
      if (j.has("servingMl")) number(j, "servingMl", 0.001, 100000);
      if (!Arrays.asList("g", "serving", "ml").contains(j.getString("unit")))
        throw new Exception("Invalid unit");
      for (String k : new String[] {"kcal", "protein", "carbs", "fat"}) number(j, k, 0, 100000);
    } else if (kind.equals("batch") || kind.equals("direct")) {
      if (j.getString("name").trim().isEmpty()) throw new Exception("Name required");
      number(j, "available", 0, 32503680000000d);
      number(j, "share", 0, 1);
      if (!j.isNull("completed") && j.getLong("completed") < j.getLong("available"))
        throw new Exception("Completion must follow availability");
      if (kind.equals("direct")
          && (j.isNull("completed") || j.getLong("completed") != j.getLong("available")))
        throw new Exception("Direct consumption must be an instant");
      JSONArray events = j.optJSONArray("eatingEvents");
      if (events != null) {
        if (!kind.equals("batch") || events.length() > 10000)
          throw new Exception("Invalid eating sessions");
        Set<String> eventIds = new HashSet<>();
        double[] fractions = new double[events.length()];
        for (int e = 0; e < events.length(); e++) {
          JSONObject event = events.getJSONObject(e);
          number(event, "fraction", 0.000000001, 1);
          fractions[e] = event.getDouble("fraction");
          String id = event.getString("id");
          if (id.isEmpty() || !eventIds.add(id)) throw new Exception("Duplicate eating session");
          number(
              event,
              "time",
              j.getLong("available"),
              j.isNull("completed") ? 32503680000000d : j.getLong("completed"));
        }
        RecordedPortions.total(fractions, j.getDouble("share"));
      }
      JSONArray a = j.getJSONArray("ingredients");
      if (a.length() == 0 || a.length() > 1000) throw new Exception("Ingredients required");
      for (int i = 0; i < a.length(); i++) {
        JSONObject x = a.getJSONObject(i);
        validate("food", x.getJSONObject("food"));
        number(x, "quantity", 0.001, 1000000);
      }
    } else if (kind.equals("calorie")) {
      number(j, "time", 0, 32503680000000d);
      number(j, "kcal", 0.001, 100000);
    } else if (kind.equals("settings")) {
      java.time.LocalDate.parse(j.getString("start"));
      number(j, "duration", 1, 720);
      number(j, "week", 1, 7);
      number(j, "goal", 0, 70000);
      if (!Estimator.POLICY.equals(j.getString("policy")))
        throw new Exception("Unsupported policy");
    } else if (kind.equals("rate")) {
      java.time.LocalDate.parse(j.getString("date"));
      number(j, "value", 1, 10000);
    } else throw new Exception("Unknown record type");
  }

  public String backup() throws Exception {
    JSONObject root = new JSONObject().put("schema", 2);
    JSONArray rows = new JSONArray();
    for (String k : new String[] {"food", "batch", "direct", "calorie", "settings", "rate"})
      for (JSONObject j : list(k)) rows.put(new JSONObject().put("kind", k).put("data", j));
    return root.put("records", rows).toString(2);
  }

  public void restore(String text) throws Exception {
    JSONObject root = new JSONObject(text);
    if (root.getInt("schema") != 1 && root.getInt("schema") != 2)
      throw new Exception("Unsupported schema");
    JSONArray rows = root.getJSONArray("records");
    Set<String> ids = new HashSet<>();
    int settings = 0;
    Set<String> dates = new HashSet<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject r = rows.getJSONObject(i), j = r.getJSONObject("data");
      String k = r.getString("kind");
      validate(k, j);
      if (!ids.add(j.getString("id"))) throw new Exception("Duplicate ID");
      if (k.equals("settings")) settings++;
      if (k.equals("rate") && !dates.add(j.getString("date")))
        throw new Exception("Duplicate expenditure date");
    }
    if (settings != 1 || dates.isEmpty()) throw new Exception("Settings and expenditure required");
    String tracking = "";
    double goal = 0;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject r = rows.getJSONObject(i);
      if (r.getString("kind").equals("settings")) {
        tracking = r.getJSONObject("data").getString("start");
        goal = r.getJSONObject("data").getDouble("goal");
      }
    }
    boolean covered = false;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject r = rows.getJSONObject(i);
      if (r.getString("kind").equals("rate")) {
        JSONObject v = r.getJSONObject("data");
        if (v.getString("date").compareTo(tracking) <= 0) covered = true;
      }
    }
    if (!covered) throw new Exception("Expenditure must cover tracking start");
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      d.delete("records", null, null);
      for (int i = 0; i < rows.length(); i++) {
        JSONObject r = rows.getJSONObject(i);
        save(r.getString("kind"), r.getJSONObject("data"));
      }
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
  }
}
