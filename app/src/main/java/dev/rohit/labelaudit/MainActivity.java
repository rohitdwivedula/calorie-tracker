package dev.rohit.labelaudit;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import org.json.*;

public class MainActivity extends ComponentActivity {
  Data db;
  LinearLayout page;
  JSONObject settings;
  int weekOffset = 0;
  final ZoneId zone = ZoneId.systemDefault();

  interface Action {
    void run() throws Exception;
  }

  void safe(Action a) {
    try {
      a.run();
    } catch (Exception e) {
      new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("OK", null).show();
    }
  }

  JSONObject obj() {
    return new JSONObject();
  }

  JSONObject put(JSONObject j, String k, Object v) {
    try {
      j.put(k, v);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return j;
  }

  ActivityResultLauncher<Intent> scan =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null)
              lookup(r.getData().getStringExtra("raw_code"));
            else if (this.addingIngredient) safe(this::batchForm);
          });
  ActivityResultLauncher<String> export =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument("application/json"),
          uri -> {
            if (uri != null)
              safe(
                  () -> {
                    try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                      o.write(db.backup().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    toast("Backup saved");
                  });
          });
  ActivityResultLauncher<String[]> restore =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri != null)
              safe(
                  () -> {
                    String text;
                    try (InputStream in = getContentResolver().openInputStream(uri);
                        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                      byte[] b = new byte[8192];
                      int n;
                      while ((n = in.read(b)) != -1) {
                        if (out.size() + n > 10000000) throw new Exception("Backup too large");
                        out.write(b, 0, n);
                      }
                      text = out.toString("UTF-8");
                    }
                    final String data = text;
                    new AlertDialog.Builder(this)
                        .setTitle("Replace all local data?")
                        .setMessage(
                            "Restore replaces all foods, batches, history and settings. Export a"
                                + " backup first. It never merges or duplicates records.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton(
                            "Replace",
                            (d, w) ->
                                safe(
                                    () -> {
                                      db.restore(data);
                                      settings = db.list("settings").get(0);
                                      home();
                                    }))
                        .show();
                  });
          });

  public void onCreate(Bundle b) {
    super.onCreate(b);
    db = new Data(this);
    safe(
        () -> {
          if (db.list("settings").isEmpty()) {
            JSONObject s = obj();
            s.put("id", "settings");
            s.put("start", LocalDate.now(zone).toString());
            s.put("duration", 48);
            s.put("week", 7);
            s.put("goal", 0);
            s.put("policy", Estimator.POLICY);
            db.save("settings", s);
            JSONObject rate =
                obj()
                    .put("id", "initial-rate")
                    .put("date", LocalDate.now(zone).toString())
                    .put("value", 2000);
            db.save("rate", rate);
          }
          settings = db.list("settings").get(0);
          if (b != null && b.containsKey("dishDraft")) {
            editing = new JSONObject(b.getString("dishDraft"));
            draft = new JSONArray(b.getString("ingredientsDraft"));
            cooking = true;
            addingIngredient = b.getBoolean("addingIngredient");
            batchForm();
          } else home();
        });
  }

  void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  LinearLayout layout() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(22, 12, 22, 12);
    return l;
  }

  void screen(String title) {
    page = layout();
    page.setBackgroundColor(0xfff5f5ed);
    ScrollView s = new ScrollView(this);
    s.addView(page);
    setContentView(s);
    label(page, title, 28);
  }

  void label(LinearLayout l, String t, int size) {
    TextView v = new TextView(this);
    v.setText(t);
    v.setTextSize(size);
    v.setTextColor(0xff163d34);
    v.setPadding(0, 10, 0, 10);
    l.addView(v);
  }

  void button(LinearLayout l, String title, Action a) {
    Button b = new Button(this);
    b.setText(title);
    l.addView(b);
    b.setOnClickListener(v -> safe(a));
  }

  EditText field(LinearLayout l, String title, String value) {
    label(l, title, 14);
    EditText e = new EditText(this);
    e.setText(value);
    e.setSingleLine(true);
    l.addView(e);
    return e;
  }

  double num(EditText e) throws Exception {
    double n = Double.parseDouble(e.getText().toString());
    if (!Double.isFinite(n)) throw new Exception("Enter a finite number");
    return n;
  }

  long timestamp(EditText e) throws Exception {
    try {
      return LocalDateTime.parse(e.getText().toString()).atZone(zone).toInstant().toEpochMilli();
    } catch (Exception x) {
      throw new Exception("Use date and time like 2026-10-30T18:30");
    }
  }

  String time(long n) {
    return Instant.ofEpochMilli(n)
        .atZone(zone)
        .toLocalDateTime()
        .withSecond(0)
        .withNano(0)
        .toString();
  }

  long midnight(LocalDate d) {
    return d.atStartOfDay(zone).toInstant().toEpochMilli();
  }

  double[] nutrition(JSONObject batch) throws Exception {
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

  double[] intake(long from, long to, long now) throws Exception {
    double[] sum = new double[4];
    for (String kind : new String[] {"batch", "direct"})
      for (JSONObject j : db.list(kind)) {
        long a = j.getLong("available");
        Long c = j.isNull("completed") ? null : j.getLong("completed");
        double fraction =
            Estimator.fraction(
                    a, c, (long) (settings.getDouble("duration") * 3600000), from, to, now)
                * j.getDouble("share");
        double[] n = nutrition(j);
        for (int i = 0; i < 4; i++) sum[i] += n[i] * fraction;
      }
    return sum;
  }

  double rate(LocalDate date) throws Exception {
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

  double expenditure(long from, long to) throws Exception {
    return Energy.accrued(
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
  }

  int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  LinearLayout card(LinearLayout parent) {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(18), dp(8), dp(18), dp(8));
    android.graphics.drawable.GradientDrawable background =
        new android.graphics.drawable.GradientDrawable();
    background.setColor(0xffffffff);
    background.setCornerRadius(dp(18));
    box.setBackground(background);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(5), 0, dp(5));
    parent.addView(box, params);
    return box;
  }

  void tile(String title, double value, String caption) {
    LinearLayout box = card(page);
    label(box, title, 14);
    TextView number = new TextView(this);
    number.setText(String.format(Locale.US, "%,.0f kcal", Math.abs(value)));
    number.setTextSize(30);
    number.setTypeface(null, android.graphics.Typeface.BOLD);
    number.setTextColor(0xff163d34);
    box.addView(number);
    label(box, caption, 12);
    box.setContentDescription(title + ", " + number.getText() + ", " + caption);
    box.setOnClickListener(v -> safe(this::summary));
    box.setFocusable(true);
  }

  void home() throws Exception {
    cooking = false;
    addingIngredient = false;
    screen("Today");
    long now = System.currentTimeMillis();
    LocalDate today = LocalDate.now(zone);
    long start = midnight(LocalDate.parse(settings.getString("start")));
    long dayStart = Math.max(start, midnight(today));
    LocalDate sunday = today.minusDays(today.getDayOfWeek().getValue() % 7);
    long weekStart = Math.max(start, midnight(sunday));
    double daily = intake(dayStart, now, now)[0];
    double dailyBalance = expenditure(dayStart, now) - daily;
    double weeklyBalance = expenditure(weekStart, now) - intake(weekStart, now, now)[0];
    tile("Estimated intake · today", daily, "So far today");
    tile(
        "Estimated " + (dailyBalance >= 0 ? "deficit" : "surplus") + " · today",
        dailyBalance,
        "So far today");
    tile(
        "Estimated " + (weeklyBalance >= 0 ? "deficit" : "surplus") + " · this week",
        weeklyBalance,
        "Sunday–now");
    boolean provisional = false;
    for (JSONObject b : db.list("batch"))
      if (b.isNull("completed") && b.getLong("available") < now) provisional = true;
    if (provisional) label(page, "Includes unfinished-dish estimates", 12);
    LinearLayout actions = new LinearLayout(this);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    page.addView(actions);
    Button create = new Button(this);
    create.setText("+ Dish");
    actions.addView(create, new LinearLayout.LayoutParams(0, dp(52), 1));
    create.setOnClickListener(v -> safe(() -> batchEditor(null, false)));
    Button scanFood = new Button(this);
    scanFood.setText("Scan food");
    actions.addView(scanFood, new LinearLayout.LayoutParams(0, dp(52), 1));
    scanFood.setOnClickListener(
        v -> scan.launch(new Intent(this, CaptureActivity.class).putExtra("mode", "barcode")));
    label(page, "Available dishes", 20);
    int count = 0;
    for (JSONObject batch : db.list("batch"))
      if (batch.isNull("completed")) {
        count++;
        LinearLayout dish = card(page);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        dish.addView(row);
        TextView name = new TextView(this);
        name.setText(batch.getString("name"));
        name.setTextSize(18);
        name.setTextColor(0xff163d34);
        name.setPadding(0, dp(12), dp(8), dp(12));
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        name.setOnClickListener(v -> safe(() -> details(batch, "batch")));
        name.setContentDescription(batch.getString("name") + ", edit dish");
        name.setFocusable(true);
        Button finish = new Button(this);
        finish.setText("Finished");
        row.addView(finish);
        finish.setOnClickListener(
            v ->
                safe(
                    () -> {
                      long completed = System.currentTimeMillis();
                      if (completed < batch.getLong("available"))
                        throw new Exception(
                            "Availability is in the future. Correct it before finishing.");
                      batch.put("completed", completed);
                      db.save("batch", batch);
                      home();
                    }));
      }
    if (count == 0) label(page, "Your dishes will appear here.", 14);
    button(page, "Foods & quick log", this::foods);
    button(
        page,
        "More",
        () ->
            new AlertDialog.Builder(this)
                .setItems(
                    new String[] {"Nutrition details", "History", "Settings & backup"},
                    (d, which) ->
                        safe(
                            () -> {
                              if (which == 0) summary();
                              else if (which == 1) history();
                              else preferences();
                            }))
                .show());
  }

  void summary() throws Exception {
    screen("Nutrition details");
    button(page, "Back", this::home);
    long now = System.currentTimeMillis();
    LocalDate today = LocalDate.now(zone),
        ws =
            today
                .minusDays(
                    Math.floorMod(today.getDayOfWeek().getValue() - settings.getInt("week"), 7))
                .plusWeeks(weekOffset);
    long start = midnight(LocalDate.parse(settings.getString("start"))),
        a = Math.max(start, midnight(ws)),
        end = midnight(ws.plusWeeks(1)),
        cut = Math.min(now, end);
    double[] cumulative = intake(start, now, now), n = intake(a, Math.max(a, cut), now);
    double balance = expenditure(start, now) - cumulative[0];
    label(
        page,
        String.format(
            Locale.US,
            "Estimated cumulative %s\n%,.0f kcal",
            balance >= 0 ? "deficit" : "surplus",
            Math.abs(balance)),
        25);
    label(page, "Since " + settings.getString("start") + " · as of " + time(now), 13);
    label(page, "Week of " + ws, 22);
    button(
        page,
        "← Previous week",
        () -> {
          weekOffset--;
          summary();
        });
    if (weekOffset < 0)
      button(
          page,
          "Next week →",
          () -> {
            weekOffset++;
            summary();
          });
    double provisional = 0;
    for (JSONObject batch : db.list("batch"))
      if (batch.isNull("completed")) {
        provisional +=
            nutrition(batch)[0]
                * batch.getDouble("share")
                * Estimator.fraction(
                    batch.getLong("available"),
                    null,
                    (long) (settings.getDouble("duration") * 3600000),
                    a,
                    Math.max(a, cut),
                    now);
      }
    label(
        page,
        String.format(
            Locale.US,
            "This week's intake includes %.0f provisional kcal and %.0f kcal from completed"
                + " records.",
            provisional,
            n[0] - provisional),
        15);
    double exp = expenditure(a, Math.max(a, cut));
    label(
        page,
        String.format(
            Locale.US,
            "Estimated intake: %,.0f kcal\n"
                + "Protein %.1f g · Carbs %.1f g · Fat %.1f g\n"
                + "Accrued estimated expenditure: %,.0f kcal\n"
                + "Estimated balance: %,.0f kcal deficit (negative = surplus)",
            n[0],
            n[1],
            n[2],
            n[3],
            exp,
            exp - n[0]),
        18);
    double full = 0;
    for (int i = 0; i < 7; i++) full += rate(ws.plusDays(i));
    label(
        page,
        String.format(
            Locale.US,
            "Full-week target: %,.0f kcal intake\nWeekly deficit goal: %,.0f kcal",
            full - settings.getDouble("goal"),
            settings.getDouble("goal")),
        16);
    label(
        page,
        "Unfinished dishes contribute provisional estimates. Finishing replaces those estimates"
            + " across the actual interval; earlier weeks may change. Expenditure is your"
            + " assumption.",
        14);
    button(page, "Back to home", this::home);
  }

  void foods() throws Exception {
    screen("Saved foods");
    button(page, "Dashboard", this::home);
    button(
        page,
        "Enter barcode",
        () -> {
          LinearLayout l = layout();
          EditText e = field(l, "Barcode or supported product QR", "");
          new AlertDialog.Builder(this)
              .setTitle("Look up food")
              .setView(l)
              .setPositiveButton("Look up", (d, w) -> lookup(e.getText().toString()))
              .setNegativeButton("Cancel", null)
              .show();
        });
    button(page, "Create manual food", () -> foodEditor(obj()));
    label(
        page,
        "Open Food Facts · community product data, ODbL. Saved nutrition can be corrected; batch"
            + " snapshots stay unchanged.",
        14);
    for (JSONObject f : db.list("food")) {
      button(
          page,
          f.getString("name") + " · log / edit",
          () -> {
            new AlertDialog.Builder(this)
                .setTitle(f.optString("name"))
                .setItems(
                    new String[] {"Log food now", "Edit food"},
                    (d, w) ->
                        safe(
                            () -> {
                              if (w == 0) quick(f);
                              else foodEditor(new JSONObject(f.toString()));
                            }))
                .show();
          });
    }
  }

  void lookup(String raw) {
    String code = BarcodeValue.productCode(raw);
    if (code == null) {
      toast("Unsupported code. Arbitrary QR URLs are never opened.");
      return;
    }
    toast("Looking up product…");
    new Thread(
            () -> {
              try {
                HttpURLConnection c =
                    (HttpURLConnection)
                        new URL("https://world.openfoodfacts.org/api/v2/product/" + code + ".json")
                            .openConnection();
                c.setRequestProperty("User-Agent", "BatchNutrition/1.0 (personal Android tracker)");
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                JSONObject product;
                try (InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                  byte[] buf = new byte[4096];
                  int n;
                  while ((n = in.read(buf)) != -1) {
                    if (out.size() > 2000000) throw new Exception("Response too large");
                    out.write(buf, 0, n);
                  }
                  JSONObject response = new JSONObject(out.toString("UTF-8"));
                  if (response.optInt("status") != 1)
                    throw new Exception("Unknown product; create it manually");
                  product = response.getJSONObject("product");
                } finally {
                  c.disconnect();
                }
                JSONObject f =
                    obj().put("barcode", code).put("name", product.optString("product_name", ""));
                JSONObject nutrients = product.optJSONObject("nutriments");
                String suffix =
                    product.optString("nutrition_data_per").equals("serving")
                        ? "_serving"
                        : "_100g";
                f.put("basis", suffix.equals("_serving") ? 1 : 100);
                f.put(
                    "unit",
                    suffix.equals("_serving")
                        ? "serving"
                        : product.optString("nutrition_data_per").equals("100ml") ? "ml" : "g");
                if (nutrients != null) {
                  String[] source = {"energy-kcal", "proteins", "carbohydrates", "fat"},
                      dest = {"kcal", "protein", "carbs", "fat"};
                  for (int i = 0; i < 4; i++)
                    if (nutrients.has(source[i] + suffix))
                      f.put(dest[i], nutrients.getDouble(source[i] + suffix));
                }
                runOnUiThread(() -> safe(() -> foodEditor(f)));
              } catch (Exception e) {
                runOnUiThread(
                    () ->
                        new AlertDialog.Builder(this)
                            .setTitle("Lookup unavailable")
                            .setMessage(e.getMessage() + ". Saved foods work offline.")
                            .setPositiveButton(
                                "Manual food",
                                (d, w) -> safe(() -> foodEditor(obj().put("barcode", code))))
                            .setNegativeButton("Cancel", null)
                            .show());
              }
            })
        .start();
  }

  void foodEditor(JSONObject f) throws Exception {
    screen("Food nutrition");
    label(
        page,
        "Verify the label and basis. Missing values require entry; they are never treated as zero."
            + " Use g, ml, or serving. Quantities must use this same unit; ml is never converted to"
            + " g.",
        15);
    String[] keys = {"name", "basis", "unit", "kcal", "protein", "carbs", "fat"};
    String[] names = {
      "Product name",
      "Nutrition basis quantity",
      "Unit: g / ml / serving",
      "Calories (kcal) per basis",
      "Protein (g) per basis",
      "Carbs (g) per basis",
      "Fat (g) per basis"
    };
    EditText[] e = new EditText[7];
    for (int i = 0; i < 7; i++)
      e[i] =
          field(
              page,
              names[i],
              f.has(keys[i]) ? f.optString(keys[i]) : i == 1 ? "100" : i == 2 ? "g" : "");
    button(
        page,
        addingIngredient ? "Save & add to dish" : "Save food",
        () -> {
          JSONObject j = new JSONObject(f.toString());
          for (int i = 0; i < 7; i++)
            j.put(keys[i], i == 0 || i == 2 ? e[i].getText().toString().trim() : num(e[i]));
          db.save("food", j);
          if (addingIngredient) ingredientQuantity(j);
          else foods();
        });
    button(
        page,
        "Cancel",
        () -> {
          if (addingIngredient) batchForm();
          else foods();
        });
  }

  JSONArray draft;
  JSONObject editing;
  EditText batchName;
  boolean addingIngredient = false, cooking = false;

  void batchEditor(JSONObject b, boolean duplicate) throws Exception {
    cooking = true;
    addingIngredient = false;
    editing = b == null ? obj() : new JSONObject(b.toString());
    if (duplicate) {
      editing.remove("id");
      editing.put("completed", JSONObject.NULL);
      editing.put("share", 1);
      editing.put("available", System.currentTimeMillis());
    }
    draft =
        editing.has("ingredients")
            ? new JSONArray(editing.getJSONArray("ingredients").toString())
            : new JSONArray();
    batchForm();
  }

  void batchForm() throws Exception {
    screen("Create / edit dish");
    batchName = field(page, "Dish name", editing.optString("name"));
    for (int i = 0; i < draft.length(); i++) {
      final int index = i;
      JSONObject x = draft.getJSONObject(i);
      label(
          page,
          x.getJSONObject("food").getString("name")
              + " · "
              + x.getDouble("quantity")
              + " "
              + x.getJSONObject("food").getString("unit"),
          17);
      button(
          page,
          "Remove ingredient " + (i + 1),
          () -> {
            remember();
            draft.remove(index);
            batchForm();
          });
    }
    button(
        page,
        "Add ingredient",
        () -> {
          remember();
          ingredientPicker();
        });
    button(
        page,
        "Save dish",
        () -> {
          remember();
          JSONObject b =
              new JSONObject(editing.toString())
                  .put("ingredients", draft)
                  .put("share", editing.optDouble("share", 1));
          if (!b.has("available")) b.put("available", System.currentTimeMillis());
          if (!b.has("completed")) b.put("completed", JSONObject.NULL);
          db.save("batch", b);
          home();
        });
    button(
        page,
        "Change start time (optional)",
        () -> {
          remember();
          ZonedDateTime selected =
              Instant.ofEpochMilli(editing.optLong("available", System.currentTimeMillis()))
                  .atZone(zone);
          new DatePickerDialog(
                  this,
                  (picker, year, month, day) -> {
                    new TimePickerDialog(
                            this,
                            (clock, hour, minute) ->
                                safe(
                                    () -> {
                                      editing.put(
                                          "available",
                                          LocalDateTime.of(year, month + 1, day, hour, minute)
                                              .atZone(zone)
                                              .toInstant()
                                              .toEpochMilli());
                                      batchForm();
                                    }),
                            selected.getHour(),
                            selected.getMinute(),
                            true)
                        .show();
                  },
                  selected.getYear(),
                  selected.getMonthValue() - 1,
                  selected.getDayOfMonth())
              .show();
        });
    button(page, "Cancel", this::home);
  }

  void remember() throws Exception {
    editing.put("name", batchName.getText().toString().trim());
  }

  void ingredientPicker() throws Exception {
    addingIngredient = true;
    android.view.View focused = getCurrentFocus();
    if (focused != null)
      ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(focused.getWindowToken(), 0);
    screen("Add ingredient");
    button(page, "Scan barcode", () -> scan.launch(new Intent(this, CaptureActivity.class)));
    button(page, "Enter food manually", () -> foodEditor(obj()));
    button(
        page,
        "Enter barcode",
        () -> {
          LinearLayout l = layout();
          EditText code = field(l, "Barcode", "");
          new AlertDialog.Builder(this)
              .setTitle("Look up food")
              .setView(l)
              .setPositiveButton("Look up", (d, w) -> lookup(code.getText().toString()))
              .setNegativeButton("Cancel", null)
              .show();
        });
    List<JSONObject> saved = db.list("food");
    if (!saved.isEmpty()) label(page, "Saved foods", 20);
    for (JSONObject food : saved)
      button(page, food.getString("name"), () -> ingredientQuantity(food));
    button(page, "Back to dish", this::batchForm);
  }

  void ingredientQuantity(JSONObject food) throws Exception {
    batchForm();
    quantity(
        food,
        q -> {
          draft.put(obj().put("food", new JSONObject(food.toString())).put("quantity", q));
          addingIngredient = false;
          batchForm();
        });
  }

  @Override
  public void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (cooking && editing != null && draft != null) {
      if (batchName != null) safe(this::remember);
      state.putBoolean("addingIngredient", addingIngredient);
      state.putString("dishDraft", editing.toString());
      state.putString("ingredientsDraft", draft.toString());
    }
  }

  interface Quantity {
    void done(double n) throws Exception;
  }

  void quantity(JSONObject f, Quantity callback) {
    LinearLayout l = layout();
    EditText q = field(l, "Quantity in " + f.optString("unit"), "");
    q.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(f.optString("name"))
            .setView(l)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v ->
                        safe(
                            () -> {
                              double n = num(q);
                              if (n <= 0 || n > 1000000)
                                throw new Exception("Enter a positive quantity");
                              callback.done(n);
                              dialog.dismiss();
                            })));
    dialog.show();
  }

  void quick(JSONObject f) {
    quantity(
        f,
        q -> {
          long now = System.currentTimeMillis();
          JSONObject b =
              obj()
                  .put("name", f.getString("name"))
                  .put("available", now)
                  .put("completed", now)
                  .put("share", 1)
                  .put(
                      "ingredients",
                      new JSONArray()
                          .put(obj().put("food", new JSONObject(f.toString())).put("quantity", q)));
          db.save("direct", b);
          home();
        });
  }

  void history() throws Exception {
    screen("History");
    button(page, "Dashboard", this::home);
    for (String kind : new String[] {"batch", "direct"})
      for (JSONObject b : db.list(kind))
        button(
            page,
            b.getString("name") + " · " + (b.isNull("completed") ? "available" : "completed"),
            () -> details(b, kind));
  }

  void details(JSONObject b, String kind) throws Exception {
    screen(b.getString("name"));
    double[] n = nutrition(b);
    label(
        page,
        String.format(
            Locale.US,
            "Whole batch: %.0f kcal · P %.1f · C %.1f · F %.1f g",
            n[0],
            n[1],
            n[2],
            n[3]),
        18);
    JSONArray ingredients = b.getJSONArray("ingredients");
    for (int i = 0; i < ingredients.length(); i++) {
      JSONObject x = ingredients.getJSONObject(i);
      label(
          page,
          x.getJSONObject("food").getString("name")
              + " · "
              + x.getDouble("quantity")
              + " "
              + x.getJSONObject("food").getString("unit"),
          16);
    }
    EditText a = field(page, "Available from · " + zone, time(b.getLong("available"))),
        c =
            field(
                page,
                "Actual completion (blank = unfinished)",
                b.isNull("completed") ? "" : time(b.getLong("completed"))),
        share = field(page, "My consumed share (%)", String.valueOf(100 * b.getDouble("share")));
    button(
        page,
        "Save corrections",
        () -> {
          JSONObject copy = new JSONObject(b.toString());
          copy.put("available", timestamp(a))
              .put("completed", c.getText().toString().isBlank() ? JSONObject.NULL : timestamp(c))
              .put("share", num(share) / 100);
          if (kind.equals("direct")) {
            if (copy.isNull("completed")) throw new Exception("Direct logs need a timestamp");
            copy.put("available", copy.getLong("completed"));
          }
          db.save(kind, copy);
          home();
        });
    if (kind.equals("batch")) {
      button(page, "Edit ingredients", () -> batchEditor(b, false));
      button(page, "Duplicate for new cooking session", () -> batchEditor(b, true));
      if (!b.isNull("completed"))
        button(
            page,
            "Undo completion",
            () -> {
              b.put("completed", JSONObject.NULL);
              db.save(kind, b);
              home();
            });
    }
    button(
        page,
        "Delete",
        () ->
            new AlertDialog.Builder(this)
                .setMessage("Delete this record and its intake?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                    "Delete",
                    (d, w) ->
                        safe(
                            () -> {
                              db.delete(b);
                              home();
                            }))
                .show());
    button(page, "Dashboard", this::home);
  }

  void preferences() throws Exception {
    screen("Settings & backup");
    label(
        page,
        "Expenditure is an assumption. Initial default: 2000 kcal/day; set your own value. Changes"
            + " apply from their effective date. Calendar days accrue by elapsed fraction of the"
            + " local day, including DST.",
        15);
    EditText start = field(page, "Tracking start (YYYY-MM-DD)", settings.getString("start")),
        duration = field(page, "Expected duration (hours, 1–720)", settings.optString("duration")),
        week = field(page, "Week starts (1 Mon … 7 Sun)", settings.optString("week")),
        goal = field(page, "Weekly deficit goal (kcal)", settings.optString("goal")),
        daily =
            field(
                page,
                "Assumed daily expenditure (kcal)",
                String.valueOf(rate(LocalDate.now(zone)))),
        effective = field(page, "Expenditure effective date", LocalDate.now(zone).toString());
    for (JSONObject r : db.list("rate"))
      label(page, "From " + r.getString("date") + ": " + r.getDouble("value") + " kcal/day", 14);
    button(
        page,
        "Save settings",
        () -> {
          JSONObject s =
              new JSONObject(settings.toString())
                  .put("start", LocalDate.parse(start.getText().toString()).toString())
                  .put("duration", num(duration))
                  .put("week", Integer.parseInt(week.getText().toString()))
                  .put("goal", num(goal));
          LocalDate date = LocalDate.parse(effective.getText().toString());
          double value = num(daily);
          Data.validate("settings", s);
          JSONObject r =
              obj().put("id", "rate-" + date).put("date", date.toString()).put("value", value);
          Data.validate("rate", r);
          if (s.getDouble("goal") >= 7 * value)
            throw new Exception("Weekly goal must leave a positive intake target");
          if (LocalDate.parse(s.getString("start")).isAfter(LocalDate.now(zone)))
            throw new Exception("Tracking start must not be in the future");
          boolean covered = !date.isAfter(LocalDate.parse(s.getString("start")));
          for (JSONObject existing : db.list("rate"))
            if (existing.getString("date").compareTo(s.getString("start")) <= 0) covered = true;
          if (!covered)
            throw new Exception("Set an expenditure effective date on or before tracking start");
          db.getWritableDatabase().beginTransaction();
          try {
            for (JSONObject old : db.list("rate"))
              if (old.getString("date").equals(date.toString())) db.delete(old);
            db.save("rate", r);
            db.save("settings", s);
            db.getWritableDatabase().setTransactionSuccessful();
          } finally {
            db.getWritableDatabase().endTransaction();
          }
          settings = s;
          home();
        });
    button(page, "Export portable backup", () -> export.launch("batch-nutrition-backup.json"));
    button(
        page,
        "Restore backup (replaces all data)",
        () -> restore.launch(new String[] {"application/json", "application/octet-stream"}));
    button(page, "Dashboard", this::home);
  }
}
