package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import org.junit.Test;

public class PortionTest {
  @Test
  public void servingSliderIsOneToEight() {
    assertEquals(1, Portion.servingSliderValue(0), 0);
    assertEquals(8, Portion.servingSliderValue(28), 0);
    assertEquals(2.5, Portion.servingSliderValue(6), 0);
  }

  @Test
  public void largerTypedQuantitiesRemainValid() {
    assertEquals(28, Portion.servingSliderProgress(12));
    assertEquals(12, Portion.convert(12, "serving", "serving", 57, 0), 0);
  }

  @Test
  public void extractsPrintedServingGrams() {
    assertEquals(57, Portion.labelAmount("2 oz (57 g)", "g"), 0);
    assertEquals(121, Portion.labelAmount("0.5 cup (121 g)", "g"), 0);
  }

  @Test
  public void distinguishesVolumeAndMass() {
    assertEquals(0, Portion.labelAmount("15 ml", "g"), 0);
    assertEquals(0, Portion.labelAmount("570 mg", "g"), 0);
    assertEquals(15, Portion.labelAmount("1 tbsp (15 ml)", "ml"), 0);
  }

  @Test
  public void ambiguousAmountsRequireCorrection() {
    assertEquals(0, Portion.labelAmount("1,5 g", "g"), 0);
    assertEquals(0, Portion.labelAmount("1/2 g", "g"), 0);
  }

  @Test
  public void twoServingsToGrams() {
    assertEquals(114, Portion.convert(2, "serving", "g", 57, 0), 0);
    assertEquals(2, Portion.convert(114, "g", "serving", 57, 0), 0);
  }

  @Test
  public void preservesDecimalQuantities() {
    assertEquals(.5, Portion.convert(28.5, "g", "serving", 57, 0), 0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void neverTreatsMlAsGrams() {
    Portion.convert(15, "ml", "g", 0, 15);
  }

  @Test(expected = IllegalArgumentException.class)
  public void missingConversionRejected() {
    Portion.convert(2, "serving", "g", 0, 0);
  }
}
