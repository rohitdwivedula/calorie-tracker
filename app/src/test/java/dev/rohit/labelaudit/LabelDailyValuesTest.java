package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import org.junit.Test;

public class LabelDailyValuesTest {
  @Test
  public void currentLabelPercentagesUseLabelDvsAndNormalizedGrams() {
    assertEquals(
        .00009,
        LabelDailyValues.grams("vitamin-a", 10, LabelDailyValues.CURRENT, "unknown"),
        1e-12);
    assertEquals(
        .018, LabelDailyValues.grams("vitamin-c", 20, LabelDailyValues.CURRENT, "unknown"), 1e-12);
    assertEquals(
        .13, LabelDailyValues.grams("calcium", 10, LabelDailyValues.CURRENT, "unknown"), 1e-12);
    assertEquals(
        .0009, LabelDailyValues.grams("iron", 5, LabelDailyValues.CURRENT, "unknown"), 1e-12);
  }

  @Test
  public void oldLabelsHaveDifferentReferences() {
    assertEquals(
        .012, LabelDailyValues.grams("vitamin-c", 20, LabelDailyValues.LEGACY, "unknown"), 1e-12);
    assertEquals(
        .1, LabelDailyValues.grams("calcium", 10, LabelDailyValues.LEGACY, "unknown"), 1e-12);
    assertEquals(
        .000001,
        LabelDailyValues.grams("vitamin-d", 10, LabelDailyValues.LEGACY, "unknown"),
        1e-12);
  }

  @Test
  public void oldVitaminARequiresKnownFormAndPreservesZero() {
    assertNull(LabelDailyValues.grams("vitamin-a", 10, LabelDailyValues.LEGACY, "unknown"));
    assertEquals(
        .00015, LabelDailyValues.grams("vitamin-a", 10, LabelDailyValues.LEGACY, "retinol"), 1e-12);
    assertEquals(
        .000025,
        LabelDailyValues.grams("vitamin-a", 10, LabelDailyValues.LEGACY, "food-beta-carotene"),
        1e-12);
    assertEquals(
        .0000125,
        LabelDailyValues.grams("vitamin-a", 10, LabelDailyValues.LEGACY, "other-food-carotenoids"),
        1e-12);
    assertEquals(0, LabelDailyValues.grams("vitamin-a", 0, LabelDailyValues.LEGACY, "unknown"), 0);
  }

  @Test
  public void oldVitaminEAndFolateDoNotGuessChemicalForms() {
    assertNull(LabelDailyValues.grams("vitamin-e", 100, LabelDailyValues.LEGACY, "unknown"));
    assertEquals(
        .0201,
        LabelDailyValues.grams("vitamin-e", 100, LabelDailyValues.LEGACY, "natural-alpha"),
        1e-12);
    assertEquals(
        .0135,
        LabelDailyValues.grams("vitamin-e", 100, LabelDailyValues.LEGACY, "synthetic-alpha"),
        1e-12);
    assertNull(LabelDailyValues.grams("vitamin-b9", 100, LabelDailyValues.LEGACY, "unknown"));
    assertEquals(
        .0004,
        LabelDailyValues.grams("vitamin-b9", 100, LabelDailyValues.LEGACY, "food-folate"),
        1e-12);
    assertEquals(
        .0004 / .6,
        LabelDailyValues.grams("vitamin-b9", 100, LabelDailyValues.LEGACY, "folic-acid-with-food"),
        1e-12);
  }

  @Test
  public void invalidStandardsNegativeNonfiniteAndUnsupportedFormsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> LabelDailyValues.grams("vitamin-c", -1, LabelDailyValues.CURRENT, "unknown"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LabelDailyValues.grams("vitamin-c", Double.NaN, LabelDailyValues.CURRENT, "unknown"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LabelDailyValues.grams("vitamin-a", 10, "eu", "unknown"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LabelDailyValues.grams("vitamin-a", 10, LabelDailyValues.LEGACY, "mixed-guess"));
  }
}
