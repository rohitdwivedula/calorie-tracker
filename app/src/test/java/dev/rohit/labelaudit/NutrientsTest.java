package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class NutrientsTest {
  @Test
  public void sodiumLimitIsNotAMinimum() {
    Nutrients.Days d = new Nutrients.Days();
    d.add(1.5, true, false, 2, true);
    d.add(3, true, false, 2, true);
    d.add(0, false, true, 2, true);
    assertEquals(2, d.met);
    assertEquals(1, d.unknown);
  }

  @Test
  public void loggedDaysUseRecordedAmountsForMinimumsAndLimits() {
    assertTrue(Nutrients.targetMet(80, 80, false));
    assertFalse(Nutrients.targetMet(0, 80, false));
    assertFalse(Nutrients.targetMet(3, 2, true));
    assertTrue(Nutrients.targetMet(0, 2, true));
    assertFalse(Nutrients.targetMet(0, 0, true));
  }

  double[] food() {
    double[] n = new double[Nutrients.KEYS.length];
    Arrays.fill(n, Double.NaN);
    n[0] = 100;
    n[1] = 20;
    return n;
  }

  @Test
  public void missingFiberDoesNotBecomeZero() {
    Nutrients.Total t = new Nutrients.Total();
    t.add(food(), .3);
    assertEquals(30, t.values[0], 0);
    assertTrue(t.missing[4]);
    assertFalse(t.known[4]);
  }

  @Test
  public void partialTotalsRemainMarked() {
    double[] n = food();
    n[4] = 5;
    Nutrients.Total t = new Nutrients.Total();
    t.add(n, 1);
    t.add(food(), 1);
    assertEquals(5, t.values[4], 0);
    assertTrue(t.missing[4]);
    assertTrue(t.known[4]);
  }

  @Test
  public void explicitZeroIsKnown() {
    double[] n = food();
    n[4] = 0;
    Nutrients.Total t = new Nutrients.Total();
    t.add(n, 1);
    assertFalse(t.missing[4]);
    assertTrue(t.known[4]);
  }

  @Test
  public void zeroPortionDoesNotReduceCoverage() {
    Nutrients.Total t = new Nutrients.Total();
    t.add(food(), 0);
    assertFalse(t.missing[4]);
  }

  @Test
  public void micronutrientDisplayConversions() {
    assertEquals(80, .08 * Nutrients.displayFactor(6), 1e-9);
    assertEquals(2.4, .0000024 * Nutrients.displayFactor(15), 1e-9);
  }

  @Test
  public void averagesIncludeUnknownAndPartialDays() {
    Nutrients.Days d = new Nutrients.Days();
    d.add(100, true, false, 80, false);
    d.add(0, false, true, 80, false);
    d.add(50, true, true, 80, false);
    assertEquals(50, d.average(), 0);
    assertEquals(1, d.met);
    assertEquals(2, d.unknown);
  }

  @Test
  public void noKnownDaysHasNoAverage() {
    Nutrients.Days d = new Nutrients.Days();
    d.add(0, false, true, 10, false);
    assertTrue(Double.isNaN(d.average()));
    d.add(0, true, true, 10, false);
    assertEquals(0, d.average(), 0);
  }
}
