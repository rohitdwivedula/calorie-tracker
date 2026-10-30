package dev.rohit.labelaudit;

/** Nutrient mass is stored in grams; OFF _serving/_100g values use normalized grams. */
public final class Nutrients {
  public static final String[] KEYS = {
    "kcal",
    "protein",
    "carbs",
    "fat",
    "fiber",
    "vitamin-a",
    "vitamin-c",
    "vitamin-d",
    "vitamin-e",
    "vitamin-k",
    "vitamin-b1",
    "vitamin-b2",
    "vitamin-pp",
    "vitamin-b6",
    "vitamin-b9",
    "vitamin-b12",
    "calcium",
    "iron",
    "magnesium",
    "potassium",
    "zinc",
    "selenium",
    "sodium"
  };
  public static final String[] OFF_KEYS = {
    "energy-kcal",
    "proteins",
    "carbohydrates",
    "fat",
    "fiber",
    "vitamin-a",
    "vitamin-c",
    "vitamin-d",
    "vitamin-e",
    "vitamin-k",
    "vitamin-b1",
    "vitamin-b2",
    "vitamin-pp",
    "vitamin-b6",
    "vitamin-b9",
    "vitamin-b12",
    "calcium",
    "iron",
    "magnesium",
    "potassium",
    "zinc",
    "selenium",
    "sodium"
  };
  public static final String[] NAMES = {
    "Calories",
    "Protein",
    "Carbs",
    "Fat",
    "Fiber",
    "Vitamin A",
    "Vitamin C",
    "Vitamin D",
    "Vitamin E",
    "Vitamin K",
    "Vitamin B1",
    "Vitamin B2",
    "Niacin (B3)",
    "Vitamin B6",
    "Folate (B9)",
    "Vitamin B12",
    "Calcium",
    "Iron",
    "Magnesium",
    "Potassium",
    "Zinc",
    "Selenium",
    "Sodium"
  };

  public static double displayFactor(int i) {
    if (i < 5) return 1;
    switch (i) {
      case 5:
      case 7:
      case 9:
      case 14:
      case 15:
      case 21:
        return 1000000;
      default:
        return 1000;
    }
  }

  public static String unit(int i) {
    return i == 0 ? "kcal" : i < 5 ? "g" : displayFactor(i) == 1000000 ? "µg" : "mg";
  }

  public static final class Total {
    public final double[] values = new double[KEYS.length];
    public final boolean[] missing = new boolean[KEYS.length], known = new boolean[KEYS.length];

    public void add(double[] nutrients, double factor) {
      if (factor <= 0) return;
      for (int i = 0; i < values.length; i++) {
        double n = nutrients[i];
        if (Double.isNaN(n)) missing[i] = true;
        else {
          if (!Double.isFinite(n) || n < 0)
            throw new IllegalArgumentException("Invalid nutrient value");
          values[i] += n * factor;
          known[i] = true;
        }
      }
    }
  }

  public static final class Days {
    public int complete = 0, unknown = 0, met = 0;
    private double sum = 0;

    public void add(double value, boolean missing, double goal) {
      add(value, missing, goal, false);
    }

    public void add(double value, boolean missing, double goal, boolean upperLimit) {
      if (missing) {
        unknown++;
        return;
      }
      complete++;
      sum += value;
      if (goal > 0 && (upperLimit ? value <= goal : value >= goal)) met++;
    }

    public double average() {
      return complete == 0 ? Double.NaN : sum / complete;
    }
  }
}
