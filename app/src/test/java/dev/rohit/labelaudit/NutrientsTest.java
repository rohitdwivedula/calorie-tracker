package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class NutrientsTest {
  @Test
  public void sodiumLimitIsNotAMinimum() {
    Nutrients.Days d = new Nutrients.Days();
    d.add(1.5, false, 2, true);
    d.add(3, false, 2, true);
    d.add(0, true, 2, true);
    assertEquals(1, d.met);
    assertEquals(1, d.unknown);
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
  public void averagesExcludeUnknownDays() {
    Nutrients.Days d = new Nutrients.Days();
    d.add(100, false, 80);
    d.add(0, true, 80);
    d.add(50, false, 80);
    assertEquals(75, d.average(), 0);
    assertEquals(1, d.met);
    assertEquals(1, d.unknown);
  }

  @Test
  public void noCompleteDaysHasNoAverage() {
    Nutrients.Days d = new Nutrients.Days();
    d.add(0, true, 10);
    assertTrue(Double.isNaN(d.average()));
  }
}
