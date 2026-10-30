package dev.rohit.labelaudit;

import java.util.regex.*;

/** Unit conversions require an explicit amount per label serving. */
public final class Portion {
  public static final int SERVING_STEPS = 28;

  public static double servingSliderValue(int step) {
    return 1 + Math.max(0, Math.min(SERVING_STEPS, step)) / 4.0;
  }

  public static int servingSliderProgress(double servings) {
    return Math.max(0, Math.min(SERVING_STEPS, (int) Math.round((servings - 1) * 4)));
  }

  public static double labelAmount(String label, String unit) {
    Matcher m =
        Pattern.compile("(?i)(?<![\\d.,/])([0-9]+(?:\\.[0-9]+)?)\\s*" + unit + "\\b")
            .matcher(label);
    if (!m.find()) return 0;
    double amount = Double.parseDouble(m.group(1));
    return Double.isFinite(amount) && amount > 0 ? amount : 0;
  }

  public static double convert(double value, String from, String to, double grams, double ml) {
    if (!Double.isFinite(value) || value <= 0)
      throw new IllegalArgumentException("Enter a positive quantity");
    if (from.equals(to)) return value;
    if (from.equals("serving") && to.equals("g") && grams > 0) return value * grams;
    if (from.equals("g") && to.equals("serving") && grams > 0) return value / grams;
    if (from.equals("serving") && to.equals("ml") && ml > 0) return value * ml;
    if (from.equals("ml") && to.equals("serving") && ml > 0) return value / ml;
    throw new IllegalArgumentException("No verified conversion for these units");
  }
}
