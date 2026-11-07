package dev.rohit.labelaudit;

import java.util.*;
import org.json.*;

/**
 * US general food-label DVs (adults/children 4+), never the user's personal RDA/AI goals. Sources:
 * FDA current DV reference and 1998 labeling guide; NIH ODS A/E/folate conversions. Legacy IU/form
 * ambiguities remain unknown unless the user specifies a supported form.
 */
public final class LabelDailyValues {
  public static final String VERSION = "us-label-dv-v1";
  public static final String CURRENT = "us-current", LEGACY = "us-legacy";
  // Display units from Nutrients: core g, vitamins/minerals mg or micrograms.
  private static final double[] CURRENT_VALUES = {
    0, 50, 275, 78, 28, 900, 90, 20, 15, 120, 1.2, 1.3, 16, 1.7, 400, 2.4, 1300, 18, 420, 4700, 11,
    55, 2300
  };
  private static final double[] LEGACY_VALUES = {
    0, 50, 300, 65, 25, 5000, 60, 400, 30, 80, 1.5, 1.7, 20, 2, 400, 6, 1000, 18, 400, 3500, 15, 70,
    2400
  };

  public static int index(String key) {
    for (int i = 5; i < Nutrients.KEYS.length; i++) if (Nutrients.KEYS[i].equals(key)) return i;
    throw new IllegalArgumentException("This nutrient has no supported Daily Value");
  }

  public static String formKind(String key, String standard) {
    if (!LEGACY.equals(standard)) return "";
    return switch (key) {
      case "vitamin-a" -> "a";
      case "vitamin-e" -> "e";
      case "vitamin-b9" -> "folate";
      default -> "";
    };
  }

  public static String[] forms(String key, String standard) {
    return switch (formKind(key, standard)) {
      case "a" ->
          new String[] {"unknown", "retinol", "food-beta-carotene", "other-food-carotenoids"};
      case "e" -> new String[] {"unknown", "natural-alpha", "synthetic-alpha"};
      case "folate" -> new String[] {"unknown", "food-folate", "folic-acid-with-food"};
      default -> new String[] {"unknown"};
    };
  }

  /** Returns normalized grams, or null for an unresolved legacy chemical form. */
  public static Double grams(String key, double percent, String standard, String form) {
    int i = index(key);
    if (!CURRENT.equals(standard) && !LEGACY.equals(standard))
      throw new IllegalArgumentException("Choose a supported US label standard");
    if (!Double.isFinite(percent) || percent < 0 || percent > 100000)
      throw new IllegalArgumentException("Enter a valid nonnegative percentage");
    if (!Arrays.asList(forms(key, standard)).contains(form))
      throw new IllegalArgumentException("Unsupported nutrient form");
    double value = (LEGACY.equals(standard) ? LEGACY_VALUES[i] : CURRENT_VALUES[i]) * percent / 100;
    if (percent == 0) return 0.0; // Zero does not require a chemical-form conversion.
    if (LEGACY.equals(standard)) {
      if (key.equals("vitamin-a")) {
        value *=
            switch (form) {
              case "retinol" -> .3;
              case "food-beta-carotene" -> .05;
              case "other-food-carotenoids" -> .025;
              default -> Double.NaN;
            };
      } else if (key.equals("vitamin-d")) value *= .025; // IU to micrograms.
      else if (key.equals("vitamin-e")) {
        value *=
            switch (form) {
              case "natural-alpha" -> .67;
              case "synthetic-alpha" -> .45;
              default -> Double.NaN;
            };
      } else if (key.equals("vitamin-b9")) {
        value *=
            switch (form) {
              case "food-folate" -> 1;
              case "folic-acid-with-food" -> 1 / .6;
              default -> Double.NaN;
            };
      }
    }
    return Double.isNaN(value) ? null : value / Nutrients.displayFactor(i);
  }

  public static JSONObject entry(String key, double percent, String standard, String form)
      throws Exception {
    grams(key, percent, standard, form);
    return new JSONObject()
        .put("version", VERSION)
        .put("percent", percent)
        .put("standard", standard)
        .put("form", form);
  }

  /** Raw label percentages are provenance, not inferred meals or personalized goals. */
  public static void validate(JSONObject entries) throws Exception {
    Iterator<String> keys = entries.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      JSONObject input = entries.getJSONObject(key);
      if (!VERSION.equals(input.getString("version")))
        throw new Exception("Unsupported label DV version");
      grams(key, input.getDouble("percent"), input.getString("standard"), input.getString("form"));
    }
  }
}
