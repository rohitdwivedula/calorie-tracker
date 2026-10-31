package dev.rohit.labelaudit;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;
import org.json.*;

/** Versioned recorded facts and separate raw provider responses; estimates are never persisted. */
public final class Data extends SQLiteOpenHelper {
  public Data(Context c) {
    super(c, "nutrition.db", null, 4);
  }

  public void onCreate(SQLiteDatabase d) {
    d.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY, kind TEXT NOT NULL, body TEXT NOT NULL)");
    d.execSQL("CREATE INDEX records_kind ON records(kind)");
    createLookups(d);
    createSteps(d);
  }

  private static void createLookups(SQLiteDatabase d) {
    d.execSQL(
        "CREATE TABLE lookups(id TEXT PRIMARY KEY, barcode TEXT NOT NULL, retrieved_at INTEGER NOT"
            + " NULL, url TEXT NOT NULL, payload TEXT NOT NULL, sha256 TEXT NOT NULL)");
  }

  private static void createSteps(SQLiteDatabase d) {
    d.execSQL(
        "CREATE TABLE step_cache(day TEXT NOT NULL, zone TEXT NOT NULL, body TEXT NOT NULL, PRIMARY"
            + " KEY(day,zone))");
  }

  public JSONObject stepCache(String day, String zone) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT body FROM step_cache WHERE day=? AND zone=?", new String[] {day, zone})) {
      return c.moveToFirst() ? new JSONObject(c.getString(0)) : new JSONObject();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public void saveStepCache(String day, String zone, JSONObject value) {
    ContentValues v = new ContentValues();
    v.put("day", day);
    v.put("zone", zone);
    v.put("body", value.toString());
    getWritableDatabase()
        .insertWithOnConflict("step_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE);
  }

  private JSONArray stepArchive() throws Exception {
    JSONArray result = new JSONArray();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT day,zone,body FROM step_cache WHERE day <> '_connection' ORDER BY day,zone",
                null)) {
      while (c.moveToNext()) {
        JSONObject body = new JSONObject(c.getString(2));
        if (body.has("steps"))
          result.put(
              new JSONObject()
                  .put("date", c.getString(0))
                  .put("zone", c.getString(1))
                  .put("data", body));
      }
    }
    return result;
  }

  private static void validateStepArchive(JSONArray rows) throws Exception {
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i), data = row.getJSONObject("data");
      java.time.LocalDate date = java.time.LocalDate.parse(row.getString("date"));
      java.time.ZoneId zone = java.time.ZoneId.of(row.getString("zone"));
      if (!keys.add(date + ":" + zone)) throw new Exception("Duplicate step date and zone");
      number(data, "steps", 0, 1000000);
      if (data.getDouble("steps") != data.getLong("steps"))
        throw new Exception("Steps must be an integer");
      number(data, "synced", 0, 32503680000000d);
      number(data, "attempted", data.getLong("synced"), 32503680000000d);
      long from = date.atStartOfDay(zone).toInstant().toEpochMilli();
      long to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
      number(data, "through", from, Math.min(to, data.getLong("synced")));
    }
  }

  public void onUpgrade(SQLiteDatabase d, int a, int b) {
    if (a < 1 || b != 4) throw new IllegalStateException("Unsupported migration " + a + " to " + b);
    if (a < 2) d.execSQL("CREATE INDEX IF NOT EXISTS records_kind ON records(kind)");
    if (a < 3) createLookups(d);
    if (a < 4) createSteps(d);
  }

  public static final int MAX_LOOKUP_BYTES = 1024 * 1024, MAX_BACKUP_BYTES = 64 * 1024 * 1024;

  static String payloadHash(String text) throws Exception {
    byte[] digest =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) hex.append(String.format(java.util.Locale.US, "%02x", b & 255));
    return hex.toString();
  }

  static void validateLookup(JSONObject j) throws Exception {
    String code = j.getString("barcode"),
        url = j.getString("url"),
        payload = j.getString("payload");
    if (j.getString("id").isEmpty() || !code.equals(BarcodeValue.productCode(code)))
      throw new Exception("Invalid lookup identity");
    String endpoint = "https://world.openfoodfacts.org/api/v2/product/" + code + ".json";
    if (!url.equals(endpoint) && !url.startsWith(endpoint + "?"))
      throw new Exception("Invalid lookup source");
    number(j, "retrievedAt", 0, 32503680000000d);
    if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_LOOKUP_BYTES)
      throw new Exception("Lookup payload too large");
    new JSONObject(payload);
    if (!payloadHash(payload).equals(j.getString("sha256")))
      throw new Exception("Lookup checksum mismatch");
  }

  private void insertLookup(JSONObject j) throws Exception {
    validateLookup(j);
    ContentValues v = new ContentValues();
    v.put("id", j.getString("id"));
    v.put("barcode", j.getString("barcode"));
    v.put("retrieved_at", j.getLong("retrievedAt"));
    v.put("url", j.getString("url"));
    v.put("payload", j.getString("payload"));
    v.put("sha256", j.getString("sha256"));
    getWritableDatabase().insertOrThrow("lookups", null, v);
  }

  public String cacheLookup(String barcode, String url, String payload) throws Exception {
    JSONObject j =
        new JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("barcode", barcode)
            .put("retrievedAt", System.currentTimeMillis())
            .put("url", url)
            .put("payload", payload)
            .put("sha256", payloadHash(payload));
    insertLookup(j);
    return j.getString("id");
  }

  public JSONArray lookupArchive() throws Exception {
    JSONArray out = new JSONArray();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,barcode,retrieved_at,url,payload,sha256 FROM lookups ORDER BY"
                    + " retrieved_at",
                null)) {
      while (c.moveToNext())
        out.put(
            new JSONObject()
                .put("id", c.getString(0))
                .put("barcode", c.getString(1))
                .put("retrievedAt", c.getLong(2))
                .put("url", c.getString(3))
                .put("payload", c.getString(4))
                .put("sha256", c.getString(5)));
    }
    return out;
  }

  static void validateLookupReferences(JSONArray records, JSONArray lookups) throws Exception {
    Map<String, String> ids = new HashMap<>();
    for (int i = 0; i < lookups.length(); i++) {
      JSONObject j = lookups.getJSONObject(i);
      validateLookup(j);
      if (ids.put(j.getString("id"), j.getString("barcode")) != null)
        throw new Exception("Duplicate lookup ID");
    }
    for (int i = 0; i < records.length(); i++) {
      JSONObject row = records.getJSONObject(i), data = row.getJSONObject("data");
      String kind = row.getString("kind");
      if (kind.equals("food")) validateFoodLookup(data, ids);
      else if (kind.equals("batch") || kind.equals("direct")) {
        JSONArray a = data.getJSONArray("ingredients");
        for (int k = 0; k < a.length(); k++)
          validateFoodLookup(a.getJSONObject(k).getJSONObject("food"), ids);
      }
    }
  }

  private static void validateFoodLookup(JSONObject food, Map<String, String> ids)
      throws Exception {
    if (food.has("lookupId")
        && !food.optString("barcode").equals(ids.get(food.getString("lookupId"))))
      throw new Exception("Missing or mismatched lookup reference");
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
      for (int n = 4; n < Nutrients.KEYS.length; n++)
        if (j.has(Nutrients.KEYS[n])) number(j, Nutrients.KEYS[n], 0, 100000);
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
      if (j.has("energyStart")) java.time.LocalDate.parse(j.getString("energyStart"));
      if (j.has("nutrientGoals")) {
        JSONObject goals = j.getJSONObject("nutrientGoals");
        for (int i = 1; i < Nutrients.KEYS.length; i++)
          if (goals.has(Nutrients.KEYS[i])) number(goals, Nutrients.KEYS[i], 0.000000001, 100000);
      }

      number(j, "week", 1, 7);
      number(j, "goal", 0, 70000);
      if (!Estimator.POLICY.equals(j.getString("policy")))
        throw new Exception("Unsupported policy");
    } else if (kind.equals("activity")) {
      java.time.LocalDate.parse(j.getString("date"));
      number(j, "kcal", 0.001, 100000);
      if (j.optString("description").length() > 1000) throw new Exception("Description too long");
    } else if (kind.equals("rate") || kind.equals("resting")) {
      java.time.LocalDate.parse(j.getString("date"));
      number(j, "value", 1, 10000);
    } else throw new Exception("Unknown record type");
  }

  public String backup() throws Exception {
    JSONObject root = new JSONObject().put("schema", 4);
    JSONArray rows = new JSONArray();
    for (String k :
        new String[] {
          "food", "batch", "direct", "calorie", "settings", "rate", "resting", "activity"
        }) for (JSONObject j : list(k)) rows.put(new JSONObject().put("kind", k).put("data", j));
    String result =
        root.put("records", rows)
            .put("lookups", lookupArchive())
            .put("cachedSteps", stepArchive())
            .toString(2);
    if (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BACKUP_BYTES)
      throw new Exception("Backup exceeds 64 MB limit");
    return result;
  }

  public void restore(String text) throws Exception {
    JSONObject root = new JSONObject(text);
    if (root.getInt("schema") < 1 || root.getInt("schema") > 4)
      throw new Exception("Unsupported schema");
    JSONArray rows = root.getJSONArray("records");
    JSONArray lookups = root.has("lookups") ? root.getJSONArray("lookups") : new JSONArray();
    JSONArray steps = root.has("cachedSteps") ? root.getJSONArray("cachedSteps") : new JSONArray();
    validateStepArchive(steps);
    validateLookupReferences(rows, lookups);
    Set<String> ids = new HashSet<>();
    int settings = 0;
    Set<String> dates = new HashSet<>(), restingDates = new HashSet<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject r = rows.getJSONObject(i), j = r.getJSONObject("data");
      String k = r.getString("kind");
      validate(k, j);
      if (!ids.add(j.getString("id"))) throw new Exception("Duplicate ID");
      if (k.equals("settings")) settings++;
      if (k.equals("resting") && !restingDates.add(j.getString("date")))
        throw new Exception("Duplicate resting date");
      if (k.equals("rate") && !dates.add(j.getString("date")))
        throw new Exception("Duplicate expenditure date");
    }
    if (settings != 1 || dates.isEmpty()) throw new Exception("Settings and expenditure required");
    String tracking = "", energyStart = "";
    double goal = 0;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject r = rows.getJSONObject(i);
      if (r.getString("kind").equals("settings")) {
        tracking = r.getJSONObject("data").getString("start");
        energyStart = r.getJSONObject("data").optString("energyStart", "");
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
    if (!energyStart.isEmpty()) {
      final String boundary = energyStart;
      if (restingDates.stream().noneMatch(d -> d.compareTo(boundary) <= 0))
        throw new Exception("Resting rate must cover expenditure model start");
    }
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      d.delete("records", null, null);
      d.delete("lookups", null, null);
      d.delete("step_cache", null, null);
      for (int i = 0; i < steps.length(); i++) {
        JSONObject row = steps.getJSONObject(i), body = row.getJSONObject("data");
        // Keep measured inputs across reinstalls/history-read limits, but recheck connection/data.
        body.put("status", "restored");
        saveStepCache(row.getString("date"), row.getString("zone"), body);
      }
      for (int i = 0; i < lookups.length(); i++) insertLookup(lookups.getJSONObject(i));
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
