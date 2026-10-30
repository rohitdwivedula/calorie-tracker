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

  final Navigation<ScreenState> navigation = new Navigation<>();
  String currentRoute;
  android.view.View currentRoot;
  LinearLayout currentPage;
  androidx.activity.OnBackPressedCallback backNavigation;

  final class ScreenState {
    final android.view.View root = currentRoot;
    final LinearLayout content = currentPage;
    final EditText name = batchName;
    final JSONObject batch = editing;
    final JSONArray ingredients = draft;
    final boolean ingredient = addingIngredient, isCooking = cooking;
  }

  void present(android.view.View root, String route) {
    navigation.show(currentRoute, route, new ScreenState());
    currentRoute = route;
    currentRoot = root;
    currentPage = page;
    setContentView(root);
    if (backNavigation != null) backNavigation.setEnabled(navigation.canGoBack());
  }

  void navigateBack() {
    Navigation.Entry<ScreenState> previous = navigation.back();
    if (previous == null) return;
    ScreenState state = previous.state;
    currentRoute = previous.route;
    currentRoot = state.root;
    page = state.content;
    currentPage = page;
    batchName = state.name;
    editing = state.batch;
    draft = state.ingredients;
    addingIngredient = state.ingredient;
    cooking = state.isCooking;
    if (currentRoute.equals("Today")
        || currentRoute.equals("screen:History")
        || currentRoute.equals("screen:Saved foods")
        || currentRoute.equals("screen:Settings & backup")
        || currentRoute.equals("screen:Nutrition details")) {
      cooking = false;
      addingIngredient = false;
    }
    boolean dark = currentRoute.startsWith("portion:");
    getWindow().setStatusBarColor(dark ? 0xff080808 : 0xfff4f5ef);
    getWindow().setNavigationBarColor(dark ? 0xff080808 : 0xfff4f5ef);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(dark ? 0 : android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    setContentView(currentRoot);
    backNavigation.setEnabled(navigation.canGoBack());
    android.view.View focus = getCurrentFocus();
    if (focus != null)
      ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(focus.getWindowToken(), 0);
  }

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
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
              String action = r.getData().getStringExtra("action");
              if ("calories".equals(action)) safe(() -> calorieEditor(null));
              else if ("manual_barcode".equals(action)) manualBarcode();
              else lookup(r.getData().getStringExtra("raw_code"));
            } else if (this.addingIngredient) safe(this::ingredientPicker);
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
    backNavigation =
        new androidx.activity.OnBackPressedCallback(false) {
          @Override
          public void handleOnBackPressed() {
            navigateBack();
          }
        };
    getOnBackPressedDispatcher().addCallback(this, backNavigation);
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
            home();
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
    screen(title, "screen:" + title);
  }

  void screen(String title, String route) {
    getWindow().setStatusBarColor(0xfff4f5ef);
    getWindow().setNavigationBarColor(0xfff4f5ef);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    page = layout();
    page.setBackgroundColor(0xfff5f5ed);
    ScrollView s = new ScrollView(this);
    s.addView(page);
    present(s, route);
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

  double[] portionShares(JSONObject batch) throws Exception {
    JSONArray events = batch.optJSONArray("eatingEvents");
    double[] shares = new double[events == null ? 0 : events.length()];
    for (int i = 0; i < shares.length; i++)
      shares[i] = events.getJSONObject(i).getDouble("fraction");
    return shares;
  }

  double loggedShare(JSONObject batch) throws Exception {
    return RecordedPortions.total(portionShares(batch), batch.getDouble("share"));
  }

  double batchFraction(JSONObject batch, long from, long to, long now) throws Exception {
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

  double[] intake(long from, long to, long now) throws Exception {
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
    screen("Today", "Today");
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
      if (b.isNull("completed") && !b.optBoolean("portionTracking") && b.getLong("available") < now)
        provisional = true;
    if (provisional) label(page, "Includes unfinished-dish estimates", 12);
    LinearLayout actions = new LinearLayout(this);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    page.addView(actions);
    Button create = new Button(this);
    create.setText("+ Dish");
    actions.addView(create, new LinearLayout.LayoutParams(0, dp(52), 1));
    create.setOnClickListener(v -> safe(() -> batchEditor(null, false)));
    Button scanFood = new Button(this);
    scanFood.setText("Quick log");
    actions.addView(scanFood, new LinearLayout.LayoutParams(0, dp(52), 1));
    scanFood.setOnClickListener(v -> launchQuickLog());
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
        dish.addView(name, 0, new LinearLayout.LayoutParams(-1, -2));
        name.setOnClickListener(v -> safe(() -> details(batch, "batch")));
        name.setContentDescription(batch.getString("name") + ", edit dish");
        name.setFocusable(true);
        Button ate = new Button(this);
        ate.setText("Ate");
        row.addView(ate, new LinearLayout.LayoutParams(0, dp(48), 1));
        ate.setOnClickListener(v -> safe(() -> recordPortion(batch)));
        if (batch.optBoolean("portionTracking"))
          label(dish, display(loggedShare(batch) * 100) + "% logged", 12);
        Button finish = new Button(this);
        finish.setText("Finished");
        row.addView(finish, new LinearLayout.LayoutParams(0, dp(48), 1));
        finish.setOnClickListener(v -> safe(() -> finishBatch(batch)));
      }
    if (count == 0) label(page, "Your dishes will appear here.", 14);

    button(
        page,
        "More",
        () ->
            new AlertDialog.Builder(this)
                .setItems(
                    new String[] {
                      "Nutrition details", "History", "Settings & backup", "Saved foods"
                    },
                    (d, which) ->
                        safe(
                            () -> {
                              if (which == 0) summary();
                              else if (which == 1) history();
                              else if (which == 2) preferences();
                              else foods();
                            }))
                .show());
  }

  void metricRow(LinearLayout parent, String title, String value) {
    LinearLayout row = new LinearLayout(this);
    row.setPadding(0, dp(8), 0, dp(8));
    parent.addView(row);
    TextView left = new TextView(this);
    left.setText(title);
    left.setTextSize(15);
    left.setTextColor(0xff54665e);
    row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
    TextView right = new TextView(this);
    right.setText(value);
    right.setTextSize(17);
    right.setTextColor(0xff163d34);
    right.setTypeface(null, android.graphics.Typeface.BOLD);
    row.addView(right);
  }

  void summary() throws Exception {
    screen("Nutrition details");
    long now = System.currentTimeMillis(),
        tracking = midnight(LocalDate.parse(settings.getString("start")));
    double cumulative = expenditure(tracking, now) - intake(tracking, now, now)[0];
    LinearLayout lifetime = card(page);
    label(lifetime, "Estimated cumulative " + (cumulative >= 0 ? "deficit" : "surplus"), 15);
    label(lifetime, String.format(Locale.US, "%,.0f kcal", Math.abs(cumulative)), 28);
    label(
        lifetime,
        "Since "
            + LocalDate.parse(settings.getString("start"))
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d")),
        12);
    LocalDate today = LocalDate.now(zone),
        week =
            today
                .minusDays(
                    Math.floorMod(today.getDayOfWeek().getValue() - settings.getInt("week"), 7))
                .plusWeeks(weekOffset);
    long from = Math.max(tracking, midnight(week)),
        to = Math.max(from, Math.min(now, midnight(week.plusWeeks(1))));
    double[] n = intake(from, to, now);
    double exp = expenditure(from, to), balance = exp - n[0];
    LinearLayout weekly = card(page);
    label(
        weekly,
        weekOffset == 0
            ? "This week · so far"
            : "Week of " + week.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")),
        20);
    metricRow(weekly, "Estimated intake", String.format(Locale.US, "%,.0f kcal", n[0]));
    metricRow(weekly, "Estimated expenditure", String.format(Locale.US, "%,.0f kcal", exp));
    metricRow(
        weekly,
        "Estimated " + (balance >= 0 ? "deficit" : "surplus"),
        String.format(Locale.US, "%,.0f kcal", Math.abs(balance)));
    metricRow(
        weekly,
        "Protein / Carbs / Fat",
        String.format(Locale.US, "%.0f / %.0f / %.0f g", n[1], n[2], n[3]));
    for (JSONObject c : db.list("calorie"))
      if (c.getLong("time") >= from && c.getLong("time") < to) {
        label(weekly, "Macros exclude calorie-only logs", 12);
        break;
      }
    double provisional = 0;
    for (JSONObject batch : db.list("batch"))
      if (batch.isNull("completed") && !batch.optBoolean("portionTracking"))
        provisional += nutrition(batch)[0] * batchFraction(batch, from, to, now);
    if (provisional > 0)
      label(
          weekly,
          String.format(Locale.US, "Includes %.0f kcal from unfinished dishes", provisional),
          12);
    double full = 0;
    for (int i = 0; i < 7; i++) full += rate(week.plusDays(i));
    LinearLayout target = card(page);
    label(target, "Full-week targets", 18);
    metricRow(
        target,
        "Intake",
        String.format(Locale.US, "%,.0f kcal", full - settings.getDouble("goal")));
    metricRow(
        target, "Deficit goal", String.format(Locale.US, "%,.0f kcal", settings.getDouble("goal")));
    LinearLayout weeks = new LinearLayout(this);
    page.addView(weeks);
    Button previous = new Button(this);
    previous.setText("← Previous");
    weeks.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
    previous.setOnClickListener(
        v ->
            safe(
                () -> {
                  weekOffset--;
                  summary();
                }));
    if (weekOffset < 0) {
      Button next = new Button(this);
      next.setText("Next →");
      weeks.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
      next.setOnClickListener(
          v ->
              safe(
                  () -> {
                    weekOffset++;
                    summary();
                  }));
    }
    button(
        page,
        "How estimates work",
        () ->
            new AlertDialog.Builder(this)
                .setMessage(
                    "Ate records your chosen percentage of the whole dish at that time. Finished"
                        + " offers to log the remaining share now, without changing earlier"
                        + " portions. Older dishes without portion logs may still use linear"
                        + " estimates until you log a portion. Expenditure is your assumption.")
                .setPositiveButton("Got it", null)
                .show());
  }

  void launchQuickLog() {
    addingIngredient = false;
    scan.launch(new Intent(this, CaptureActivity.class).putExtra("quick_log", true));
  }

  void manualBarcode() {
    LinearLayout form = layout();
    EditText code = field(form, "Barcode", "");
    new AlertDialog.Builder(this)
        .setTitle("Enter barcode")
        .setView(form)
        .setPositiveButton("Look up", (d, w) -> lookup(code.getText().toString()))
        .setNegativeButton("Cancel", null)
        .show();
  }

  void calorieEditor(JSONObject existing) throws Exception {
    screen("Quick log");
    EditText calories =
        field(page, "Calories (kcal)", existing == null ? "" : display(existing.getDouble("kcal")));
    calories.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    EditText name =
        field(page, "Name (optional)", existing == null ? "" : existing.optString("name"));
    label(page, "Calories only · macros unknown", 12);
    button(
        page,
        "Save",
        () -> {
          JSONObject record =
              existing == null
                  ? obj().put("time", System.currentTimeMillis())
                  : new JSONObject(existing.toString());
          record.put("kcal", num(calories)).put("name", name.getText().toString().trim());
          db.save("calorie", record);
          home();
        });
    if (existing != null)
      button(
          page,
          "Delete",
          () ->
              new AlertDialog.Builder(this)
                  .setMessage("Delete this calorie log?")
                  .setNegativeButton("Cancel", null)
                  .setPositiveButton(
                      "Delete",
                      (d, w) ->
                          safe(
                              () -> {
                                db.delete(existing);
                                home();
                              }))
                  .show());
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
                String servingLabel = product.optString("serving_size", "");
                double grams = Portion.labelAmount(servingLabel, "g"),
                    ml = Portion.labelAmount(servingLabel, "ml");
                if (grams == 0 && ml == 0) {
                  String declaredUnit = product.optString("serving_quantity_unit");
                  double amount = product.optDouble("serving_quantity", 0);
                  if (Double.isFinite(amount) && amount > 0) {
                    if (declaredUnit.equals("g")) grams = amount;
                    else if (declaredUnit.equals("ml")) ml = amount;
                  }
                }
                boolean hasServing =
                    !servingLabel.isEmpty()
                        || grams > 0
                        || ml > 0
                        || product.optString("nutrition_data_per").equals("serving");
                f.put("basis", hasServing ? 1 : 100)
                    .put(
                        "unit",
                        hasServing
                            ? "serving"
                            : product.optString("nutrition_data_per").equals("100ml") ? "ml" : "g");
                if (grams > 0) f.put("servingGrams", grams);
                if (ml > 0) f.put("servingMl", ml);
                f.put("servingLabel", servingLabel);
                if (nutrients != null) {
                  String[] source = {"energy-kcal", "proteins", "carbohydrates", "fat"},
                      dest = {"kcal", "protein", "carbs", "fat"};
                  for (int i = 0; i < 4; i++) {
                    if (hasServing && nutrients.has(source[i] + "_serving"))
                      f.put(dest[i], nutrients.getDouble(source[i] + "_serving"));
                    else if (nutrients.has(source[i] + "_100g")) {
                      if (!hasServing) f.put(dest[i], nutrients.getDouble(source[i] + "_100g"));
                      else if (grams > 0 && ml == 0)
                        f.put(dest[i], nutrients.getDouble(source[i] + "_100g") * grams / 100);
                      else if (ml > 0
                          && grams == 0
                          && product.optString("nutrition_data_per").equals("100ml"))
                        f.put(dest[i], nutrients.getDouble(source[i] + "_100g") * ml / 100);
                    }
                  }
                }
                runOnUiThread(() -> safe(() -> showFood(f)));
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

  void showFood(JSONObject f) throws Exception {
    if (!f.has("id")) f.put("id", UUID.randomUUID().toString());
    try {
      Data.validate("food", f);
    } catch (Exception incomplete) {
      toast("Complete the missing nutrition from the label");
      foodEditor(f);
      return;
    }
    if (addingIngredient) ingredientQuantity(f);
    else
      quantity(
          f,
          q -> {
            db.save("food", f);
            quickWithQuantity(f, q);
          });
  }

  void quickWithQuantity(JSONObject f, double q) throws Exception {
    long now = System.currentTimeMillis();
    db.save(
        "direct",
        obj()
            .put("name", f.getString("name"))
            .put("available", now)
            .put("completed", now)
            .put("share", 1)
            .put(
                "ingredients",
                new JSONArray()
                    .put(obj().put("food", new JSONObject(f.toString())).put("quantity", q))));
    home();
  }

  void foodEditor(JSONObject f) throws Exception {
    screen("Food nutrition");
    label(page, "Enter nutrition exactly as shown on the label.", 14);
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
    EditText servingGrams =
        field(
            page,
            "Grams in one serving (optional)",
            f.has("servingGrams") ? display(f.optDouble("servingGrams")) : "");
    EditText servingMl =
        field(
            page,
            "mL in one serving (optional)",
            f.has("servingMl") ? display(f.optDouble("servingMl")) : "");
    button(
        page,
        "Continue",
        () -> {
          JSONObject j = new JSONObject(f.toString());
          for (int i = 0; i < 7; i++)
            j.put(keys[i], i == 0 || i == 2 ? e[i].getText().toString().trim() : num(e[i]));
          j.remove("servingLabel");
          j.remove("servingGrams");
          j.remove("servingMl");
          if (!servingGrams.getText().toString().isBlank())
            j.put("servingGrams", num(servingGrams));
          if (!servingMl.getText().toString().isBlank()) j.put("servingMl", num(servingMl));
          if (!j.has("id")) j.put("id", UUID.randomUUID().toString());
          Data.validate("food", j);
          if (addingIngredient) ingredientQuantity(j);
          else {
            db.save("food", j);
            foods();
          }
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
      editing.remove("eatingEvents");
      editing.remove("finishEventId");
      editing.put("completed", JSONObject.NULL);
      editing.put("share", 1);
      editing.put("available", System.currentTimeMillis());
    }
    if (b == null || duplicate) editing.put("portionTracking", true);
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
    quantity(
        food,
        q -> {
          db.save("food", food);
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

  String display(double n) {
    return String.format(Locale.US, "%.2f", n).replaceAll("0+$", "").replaceAll("\\.$", "");
  }

  TextView portionText(LinearLayout parent, String text, int size, boolean bold) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextColor(0xfff5f5f5);
    view.setTextSize(size);
    view.setPadding(0, dp(8), 0, dp(8));
    if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
    parent.addView(view);
    return view;
  }

  LinearLayout portionCard() {
    LinearLayout box = card(page);
    android.graphics.drawable.GradientDrawable background =
        new android.graphics.drawable.GradientDrawable();
    background.setColor(0xff19191b);
    background.setCornerRadius(dp(24));
    box.setBackground(background);
    box.setPadding(dp(20), dp(16), dp(20), dp(16));
    return box;
  }

  void portionButton(LinearLayout parent, String title, boolean primary, Action action) {
    Button b = new Button(this);
    b.setText(title);
    b.setAllCaps(false);
    b.setTextSize(17);
    b.setTextColor(primary ? 0xff071b11 : 0xffededed);
    android.graphics.drawable.GradientDrawable bg =
        new android.graphics.drawable.GradientDrawable();
    bg.setColor(primary ? 0xff80d6a2 : 0xff303033);
    bg.setCornerRadius(dp(28));
    b.setBackground(bg);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1);
    params.setMargins(dp(4), dp(8), dp(4), dp(8));
    parent.addView(b, params);
    b.setOnClickListener(v -> safe(action));
  }

  TextView portionRow(LinearLayout parent, String title, int size, boolean bold) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    parent.addView(row);
    TextView left = portionText(row, title, size, bold);
    left.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    TextView value = portionText(row, "", size, bold);
    value.setGravity(android.view.Gravity.END);
    return value;
  }

  void quantity(JSONObject f, Quantity callback) {
    safe(
        () -> {
          LinearLayout root = new LinearLayout(this);
          root.setOrientation(LinearLayout.VERTICAL);
          root.setBackgroundColor(0xff080808);
          ScrollView scroll = new ScrollView(this);
          scroll.setFillViewport(true);
          page = new LinearLayout(this);
          page.setOrientation(LinearLayout.VERTICAL);
          page.setPadding(dp(16), dp(12), dp(16), dp(20));
          scroll.addView(page);
          root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
          LinearLayout footer = new LinearLayout(this);
          footer.setOrientation(LinearLayout.HORIZONTAL);
          footer.setPadding(dp(20), 0, dp(20), dp(8));
          root.addView(footer);
          present(root, "portion:" + f.optString("id", f.optString("name")));
          getWindow().setStatusBarColor(0xff080808);
          getWindow().setNavigationBarColor(0xff080808);
          getWindow().getDecorView().setSystemUiVisibility(0);
          portionText(page, "Set portion size", 26, true);
          LinearLayout controls = portionCard();
          portionText(controls, f.getString("name"), 22, true);
          TextView selectedCalories = portionText(controls, "", 24, false);
          selectedCalories.setTextColor(0xffb7b7bc);

          double grams = f.optDouble("servingGrams", 0), ml = f.optDouble("servingMl", 0);
          String base = f.getString("unit");
          ArrayList<String> units = new ArrayList<>();
          if (base.equals("serving")
              || base.equals("g") && grams > 0
              || base.equals("ml") && ml > 0) units.add("serving");
          if (base.equals("g") || base.equals("serving") && grams > 0) units.add("g");
          if (base.equals("ml") || base.equals("serving") && ml > 0) units.add("ml");
          Spinner unit = new Spinner(this);
          ArrayList<String> unitLabels = new ArrayList<>();
          for (String u : units)
            unitLabels.add(
                u.equals("serving")
                    ? "Serving"
                        + (grams > 0
                            ? " (" + display(grams) + " g)"
                            : ml > 0 ? " (" + display(ml) + " ml)" : "")
                    : u.equals("g") ? "Grams" : "Milliliters");
          unit.setAdapter(
              new ArrayAdapter<String>(
                  this, android.R.layout.simple_spinner_dropdown_item, unitLabels) {
                @Override
                public android.view.View getView(
                    int position, android.view.View recycled, android.view.ViewGroup parent) {
                  TextView v = (TextView) super.getView(position, recycled, parent);
                  v.setTextColor(0xff80d6a2);
                  v.setTextSize(18);
                  v.setGravity(android.view.Gravity.CENTER);
                  return v;
                }
              });
          EditText amount = new EditText(this);
          amount.setText(units.get(0).equals("serving") ? "1" : display(f.getDouble("basis")));
          amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
          amount.setTextSize(32);
          amount.setTextColor(0xffffffff);
          amount.setGravity(android.view.Gravity.CENTER);
          amount.setSingleLine(true);
          amount.setContentDescription("Portion quantity");
          amount.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff80d6a2));
          LinearLayout.LayoutParams amountParams = new LinearLayout.LayoutParams(dp(150), dp(64));
          amountParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
          amountParams.topMargin = dp(16);
          controls.addView(amount, amountParams);
          SeekBar slider = new SeekBar(this);
          slider.setMax(units.get(0).equals("serving") ? 79 : 1999);
          slider.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff80d6a2));
          slider.setThumbTintList(android.content.res.ColorStateList.valueOf(0xff80d6a2));
          slider.setContentDescription("Adjust portion quantity");
          controls.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
          controls.addView(unit, new LinearLayout.LayoutParams(-1, dp(48)));
          TextView total = portionText(controls, "", 14, false);
          total.setTextColor(0xffb7b7bc);
          total.setGravity(android.view.Gravity.CENTER);
          LinearLayout macroCard = portionCard();
          portionText(macroCard, "Macronutrients", 22, true);
          TextView macroLegend = portionText(macroCard, "", 14, false);
          LinearLayout macroBar = new LinearLayout(this);
          macroBar.setOrientation(LinearLayout.HORIZONTAL);
          macroCard.addView(macroBar, new LinearLayout.LayoutParams(-1, dp(12)));
          android.view.View[] segments = new android.view.View[3];
          int[] colors = {0xff1598aa, 0xfff3ad36, 0xffe7ce4f};
          for (int i = 0; i < 3; i++) {
            segments[i] = new android.view.View(this);
            segments[i].setBackgroundColor(colors[i]);
            macroBar.addView(segments[i], new LinearLayout.LayoutParams(0, -1, 1));
          }
          LinearLayout facts = macroCard;
          portionText(facts, "Nutrition facts", 24, true);
          String serving = f.optString("servingLabel", "");
          if (serving.isEmpty() && base.equals("serving"))
            serving =
                grams > 0 ? display(grams) + " g" : ml > 0 ? display(ml) + " ml" : "1 serving";
          portionText(
              facts,
              base.equals("serving")
                  ? "1 serving · " + serving
                  : "Per " + display(f.getDouble("basis")) + " " + base,
              14,
              false);
          TextView selectedBasis = portionText(facts, "", 14, false);
          TextView calories = portionRow(facts, "Calories", 28, true);
          String[] keys = {"carbs", "fat", "protein"}, names = {"Carbohydrate", "Fat", "Protein"};
          TextView[] macros = new TextView[3];
          for (int i = 0; i < 3; i++) {
            android.view.View line = new android.view.View(this);
            line.setBackgroundColor(0xffaaaaaa);
            facts.addView(line, new LinearLayout.LayoutParams(-1, dp(i == 0 ? 3 : 1)));
            macros[i] = portionRow(facts, names[i], 18, false);
          }
          final boolean[] updating = {false};
          final String[] previous = {units.get(0)};
          Runnable update =
              () -> {
                try {
                  double value = Double.parseDouble(amount.getText().toString());
                  String selected = units.get(unit.getSelectedItemPosition());
                  double canonical = Portion.convert(value, selected, base, grams, ml);
                  double factor = canonical / f.getDouble("basis");
                  selectedCalories.setText(
                      String.format(Locale.US, "%,.0f Cal", f.getDouble("kcal") * factor));
                  total.setText(
                      selected.equals("serving")
                          ? (grams > 0
                              ? display(value * grams) + " g"
                              : ml > 0 ? display(value * ml) + " ml" : "Label servings")
                          : ((selected.equals("g") ? grams : ml) > 0
                              ? display(value / (selected.equals("g") ? grams : ml)) + " servings"
                              : ""));
                  selectedBasis.setText(
                      "Selected amount · "
                          + display(value)
                          + " "
                          + (selected.equals("serving") ? "servings" : selected));
                  calories.setText(String.format(Locale.US, "%,.0f", f.getDouble("kcal") * factor));
                  double macroEnergy =
                      f.getDouble("carbs") * 4
                          + f.getDouble("fat") * 9
                          + f.getDouble("protein") * 4;
                  StringBuilder legend = new StringBuilder();
                  for (int i = 0; i < 3; i++) {
                    double nutrient = f.getDouble(keys[i]);
                    macros[i].setText(String.format(Locale.US, "%.1f g", nutrient * factor));
                    double percent =
                        macroEnergy > 0 ? 100 * nutrient * (i == 1 ? 9 : 4) / macroEnergy : 0;
                    segments[i].setLayoutParams(
                        new LinearLayout.LayoutParams(0, -1, (float) (percent)));
                    if (i > 0) legend.append("  ·  ");
                    legend
                        .append(i == 0 ? "Carb" : names[i])
                        .append(" ")
                        .append(Math.round(percent))
                        .append("%");
                  }
                  macroBar.setVisibility(
                      macroEnergy > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
                  macroLegend.setText(macroEnergy > 0 ? legend.toString() : "No macro calories");
                  if (!updating[0]) {
                    updating[0] = true;
                    slider.setProgress(
                        (int) Math.round(selected.equals("serving") ? value * 4 - 1 : value - 1));
                    updating[0] = false;
                  }
                } catch (Exception invalid) {
                  total.setText("Enter a positive quantity");
                  selectedCalories.setText("— Cal");
                }
              };
          amount.addTextChangedListener(
              new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence t, int st, int count, int after) {}

                public void onTextChanged(CharSequence t, int st, int before, int count) {
                  update.run();
                }

                public void afterTextChanged(android.text.Editable e) {}
              });
          slider.setOnSeekBarChangeListener(
              new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar b) {}

                public void onStopTrackingTouch(SeekBar b) {}

                public void onProgressChanged(SeekBar b, int progress, boolean user) {
                  if (user) {
                    updating[0] = true;
                    amount.setText(
                        display(
                            units.get(unit.getSelectedItemPosition()).equals("serving")
                                ? (progress + 1) / 4.0
                                : progress + 1));
                    updating[0] = false;
                  }
                }
              });
          unit.setOnItemSelectedListener(
              new android.widget.AdapterView.OnItemSelectedListener() {
                public void onNothingSelected(android.widget.AdapterView<?> a) {}

                public void onItemSelected(
                    android.widget.AdapterView<?> a, android.view.View v, int pos, long id) {
                  String selected = units.get(pos);
                  slider.setMax(selected.equals("serving") ? 79 : 1999);
                  try {
                    double converted =
                        Portion.convert(
                            Double.parseDouble(amount.getText().toString()),
                            previous[0],
                            selected,
                            grams,
                            ml);
                    previous[0] = selected;
                    amount.setText(
                        java.math.BigDecimal.valueOf(converted)
                            .setScale(6, java.math.RoundingMode.HALF_UP)
                            .stripTrailingZeros()
                            .toPlainString());
                  } catch (Exception ignored) {
                    previous[0] = selected;
                    amount.setText("1");
                  }
                  update.run();
                }
              });
          LinearLayout more = new LinearLayout(this);
          page.addView(more);
          portionButton(
              more,
              "Edit nutrition / serving size",
              false,
              () -> foodEditor(new JSONObject(f.toString())));
          if (!addingIngredient) {
            LinearLayout saveOnly = new LinearLayout(this);
            page.addView(saveOnly);
            portionButton(
                saveOnly,
                "Save food only",
                false,
                () -> {
                  db.save("food", f);
                  foods();
                });
          }
          portionButton(
              footer,
              "Cancel",
              false,
              () -> {
                if (addingIngredient) batchForm();
                else foods();
              });
          portionButton(
              footer,
              "Save",
              true,
              () -> {
                double canonical =
                    Portion.convert(
                        num(amount), units.get(unit.getSelectedItemPosition()), base, grams, ml);
                if (canonical > 1000000) throw new Exception("Quantity is too large");
                callback.done(canonical);
              });
          update.run();
        });
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
    for (JSONObject entry : db.list("calorie"))
      button(
          page,
          (entry.optString("name").isEmpty() ? "Quick log" : entry.optString("name"))
              + " · "
              + display(entry.getDouble("kcal"))
              + " kcal",
          () -> calorieEditor(entry));
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
          copy.put(
                  "available",
                  a.getText().toString().equals(time(b.getLong("available")))
                      ? b.getLong("available")
                      : timestamp(a))
              .put(
                  "completed",
                  c.getText().toString().isBlank()
                      ? JSONObject.NULL
                      : !b.isNull("completed")
                              && c.getText().toString().equals(time(b.getLong("completed")))
                          ? b.getLong("completed")
                          : timestamp(c))
              .put("share", num(share) / 100);
          if (kind.equals("direct")) {
            if (copy.isNull("completed")) throw new Exception("Direct logs need a timestamp");
            copy.put("available", copy.getLong("completed"));
          }
          db.save(kind, copy);
          home();
        });
    if (kind.equals("batch")) {
      JSONArray events = b.optJSONArray("eatingEvents");
      if (events != null && events.length() > 0) {
        label(page, "Recorded portions", 20);
        for (int i = 0; i < events.length(); i++) {
          final int index = i;
          JSONObject event = events.getJSONObject(i);
          button(
              page,
              display(event.getDouble("fraction") * 100) + "% · " + time(event.getLong("time")),
              () -> editEatingEvent(b, index));
        }
      }
      button(page, "Edit ingredients", () -> batchEditor(b, false));
      button(page, "Duplicate for new cooking session", () -> batchEditor(b, true));
      if (!b.isNull("completed"))
        button(
            page,
            "Undo completion",
            () -> {
              String finishId = b.optString("finishEventId", "");
              JSONArray logs = b.optJSONArray("eatingEvents");
              if (logs != null)
                for (int i = logs.length() - 1; i >= 0; i--)
                  if (logs.getJSONObject(i).optString("id").equals(finishId)) logs.remove(i);
              b.remove("finishEventId");
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

  void appendPortion(JSONObject batch, double fraction, long time, String source) throws Exception {
    JSONArray events = batch.optJSONArray("eatingEvents");
    if (events == null) events = new JSONArray();
    JSONObject event =
        obj()
            .put("id", UUID.randomUUID().toString())
            .put("time", time)
            .put("fraction", fraction)
            .put("source", source);
    events.put(event);
    batch.put("eatingEvents", events).put("portionTracking", true);
    if (source.equals("finish")) batch.put("finishEventId", event.getString("id"));
  }

  void recordPortion(JSONObject original) throws Exception {
    double remaining =
        RecordedPortions.remaining(portionShares(original), original.getDouble("share"));
    if (remaining <= 1e-9) {
      toast("Your full share is already logged");
      return;
    }
    LinearLayout form = layout();
    label(form, "Share of the whole dish", 16);
    ArrayList<Double> ticks = new ArrayList<>();
    for (double choice : new double[] {.1, .25, 1.0 / 3, .5, 2.0 / 3, .75, 1})
      if (choice < remaining - 1e-9) ticks.add(choice);
    ticks.add(remaining);
    LinearLayout controls = new LinearLayout(this);
    controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
    form.addView(controls);
    SeekBar slider = new SeekBar(this);
    slider.setMax(ticks.size() - 1);
    slider.setContentDescription("Choose a common share of the dish");
    controls.addView(slider, new LinearLayout.LayoutParams(0, dp(56), 1));
    EditText percent = new EditText(this);
    percent.setSingleLine(true);
    percent.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    percent.setContentDescription("Percent of whole dish");
    controls.addView(percent, new LinearLayout.LayoutParams(dp(90), dp(56)));
    label(controls, "%", 18);
    TextView preview = new TextView(this);
    preview.setTextSize(17);
    form.addView(preview);
    final double[] selected = {Math.min(1.0 / 3, remaining)};
    final boolean[] changing = {false};
    double totalCalories = nutrition(original)[0];
    Runnable refresh =
        () ->
            preview.setText(
                String.format(
                    Locale.US,
                    "%.0f kcal · up to %s%% remaining",
                    totalCalories * selected[0],
                    display(remaining * 100)));
    percent.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence t, int a, int c, int d) {}

          public void afterTextChanged(android.text.Editable e) {}

          public void onTextChanged(CharSequence t, int a, int b, int c) {
            if (!changing[0]) {
              try {
                selected[0] = Double.parseDouble(t.toString()) / 100;
                refresh.run();
              } catch (Exception invalid) {
                selected[0] = Double.NaN;
                preview.setText("Enter a percentage");
              }
            }
          }
        });
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}

          public void onProgressChanged(SeekBar b, int position, boolean user) {
            if (user) {
              selected[0] = ticks.get(position);
              changing[0] = true;
              percent.setText(display(selected[0] * 100));
              changing[0] = false;
              refresh.run();
            }
          }
        });
    int initial = 0;
    for (int i = 0; i < ticks.size(); i++)
      if (Math.abs(ticks.get(i) - selected[0]) < 1e-9) initial = i;
    slider.setProgress(initial);
    changing[0] = true;
    percent.setText(display(selected[0] * 100));
    changing[0] = false;
    refresh.run();
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("How much did you eat?")
            .setView(form)
            .setPositiveButton("Log portion", null)
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
                              if (!Double.isFinite(selected[0])
                                  || selected[0] <= 0
                                  || selected[0] > remaining + 1e-9)
                                throw new Exception(
                                    "Enter a positive percentage no larger than the remaining"
                                        + " share");
                              JSONObject batch = new JSONObject(original.toString());
                              appendPortion(batch, selected[0], System.currentTimeMillis(), "ate");
                              db.save("batch", batch);
                              dialog.dismiss();
                              home();
                            })));
    dialog.show();
  }

  void finishBatch(JSONObject original) throws Exception {
    double remaining =
        RecordedPortions.remaining(portionShares(original), original.getDouble("share"));
    Action finish =
        () -> {
          JSONObject batch = new JSONObject(original.toString());
          long now = System.currentTimeMillis();
          if (remaining > 1e-9) appendPortion(batch, remaining, now, "finish");
          batch.put("portionTracking", true).put("completed", now);
          db.save("batch", batch);
          home();
        };
    if (remaining <= 1e-9) {
      finish.run();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Log remaining share now?")
        .setMessage(
            String.format(
                Locale.US,
                "%s%% of the whole dish · %.0f kcal",
                display(remaining * 100),
                nutrition(original)[0] * remaining))
        .setPositiveButton("Yes, log & finish", (d, w) -> safe(finish))
        .setNegativeButton("Not now", null)
        .show();
  }

  void editEatingEvent(JSONObject batch, int index) throws Exception {
    JSONObject copy = new JSONObject(batch.toString());
    JSONArray events = copy.getJSONArray("eatingEvents");
    JSONObject event = events.getJSONObject(index);
    LinearLayout form = layout();
    long original = event.getLong("time");
    EditText entered = field(form, "When · " + zone, time(original));
    EditText percent =
        field(form, "Percent of whole dish", display(event.getDouble("fraction") * 100));
    percent.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Recorded portion")
            .setView(form)
            .setPositiveButton("Save", null)
            .setNeutralButton("Remove", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.setOnShowListener(
        d -> {
          dialog
              .getButton(-1)
              .setOnClickListener(
                  v ->
                      safe(
                          () -> {
                            event.put(
                                "time",
                                entered.getText().toString().equals(time(original))
                                    ? original
                                    : timestamp(entered));
                            if (!percent
                                .getText()
                                .toString()
                                .equals(display(event.getDouble("fraction") * 100)))
                              event.put("fraction", num(percent) / 100);
                            db.save("batch", copy);
                            dialog.dismiss();
                            home();
                          }));
          dialog
              .getButton(-3)
              .setOnClickListener(
                  v ->
                      safe(
                          () -> {
                            if (copy.optString("finishEventId").equals(event.optString("id")))
                              copy.remove("finishEventId");
                            events.remove(index);
                            db.save("batch", copy);
                            dialog.dismiss();
                            home();
                          }));
        });
    dialog.show();
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
