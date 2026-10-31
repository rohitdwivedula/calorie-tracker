package dev.rohit.labelaudit;

/**
 * Fixed approximations, not individualized physiological measurements. TEF factors are within the
 * nutrient-specific ranges in Westerterp (2004), https://pmc.ncbi.nlm.nih.gov/articles/PMC524030/.
 * Steps use the requested flat estimate. Food calories remain unchanged; no additional blanket
 * digestion allowance is applied.
 */
public final class ExpenditureCoefficients {
  private ExpenditureCoefficients() {}

  public static final double PROTEIN_TEF_FACTOR = 0.25;
  public static final double CARBOHYDRATE_TEF_FACTOR = 0.075;
  public static final double FAT_TEF_FACTOR = 0.02;
  public static final double KCAL_PER_1000_STEPS = 30.0;
}
