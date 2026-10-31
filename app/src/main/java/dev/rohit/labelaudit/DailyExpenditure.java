package dev.rohit.labelaudit;

/**
 * Replaceable calculation over authoritative nutrition/rates/activities and the separate step
 * cache.
 */
public final class DailyExpenditure {
  public static final class Estimate {
    public final double resting, digestion, fallbackDigestion, extra, total;
    public final Double steps;
    public final boolean nutritionIncomplete, stepsIncomplete, legacy;

    public Estimate(
        double resting,
        double digestion,
        Double steps,
        double extra,
        boolean nutritionIncomplete,
        boolean stepsIncomplete,
        boolean legacy) {
      this(resting, digestion, steps, extra, nutritionIncomplete, stepsIncomplete, legacy, 0);
    }

    public Estimate(
        double resting,
        double digestion,
        Double steps,
        double extra,
        boolean nutritionIncomplete,
        boolean stepsIncomplete,
        boolean legacy,
        double fallbackDigestion) {
      this.fallbackDigestion = fallbackDigestion;
      this.resting = resting;
      this.digestion = digestion;
      this.steps = steps;
      this.extra = extra;
      this.nutritionIncomplete = nutritionIncomplete;
      this.stepsIncomplete = stepsIncomplete;
      this.legacy = legacy;
      total = resting + digestion + (steps == null ? 0 : steps) + extra;
    }

    public boolean incomplete() {
      return nutritionIncomplete || stepsIncomplete;
    }
  }

  public static Estimate calculate(
      double resting, Nutrients.Total food, Long steps, double extra, boolean stale) {
    return calculate(resting, food, steps, extra, stale, 0);
  }

  public static Estimate calculate(
      double resting,
      Nutrients.Total food,
      Long steps,
      double extra,
      boolean stale,
      double calorieOnlyKcal) {
    if (!Double.isFinite(calorieOnlyKcal) || calorieOnlyKcal < 0)
      throw new IllegalArgumentException("Invalid calorie-only energy");
    double fallback = calorieOnlyKcal * ExpenditureCoefficients.CALORIE_ONLY_TEF_FACTOR;
    double tef =
        food.values[1] * 4 * ExpenditureCoefficients.PROTEIN_TEF_FACTOR
            + food.values[2] * 4 * ExpenditureCoefficients.CARBOHYDRATE_TEF_FACTOR
            + food.values[3] * 9 * ExpenditureCoefficients.FAT_TEF_FACTOR;
    return new Estimate(
        resting,
        tef + fallback,
        steps == null ? null : steps / 1000.0 * ExpenditureCoefficients.KCAL_PER_1000_STEPS,
        extra,
        food.missing[1] || food.missing[2] || food.missing[3],
        steps == null || stale,
        false,
        fallback);
  }
}
