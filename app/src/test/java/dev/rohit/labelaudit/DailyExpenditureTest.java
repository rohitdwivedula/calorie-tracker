package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import org.junit.Test;

public class DailyExpenditureTest {
  @Test
  public void requestedExampleRetainsPrecisionAndFoodCalories() {
    Nutrients.Total n = new Nutrients.Total();
    double[] values = new double[Nutrients.KEYS.length];
    values[0] = 2100;
    values[1] = 150;
    values[2] = 200;
    values[3] = 60;
    n.add(values, 1);
    DailyExpenditure.Estimate e = DailyExpenditure.calculate(1766, n, 6000L, 100, false);
    assertEquals(220.8, e.digestion, 1e-9);
    assertEquals(180, e.steps, 1e-9);
    assertEquals(2266.8, e.total, 1e-9);
    assertEquals(2100, n.values[0], 0);
    assertFalse(e.incomplete());
  }

  @Test
  public void missingMacrosAndStepsStayDistinctFromKnownZero() {
    Nutrients.Total unknown = new Nutrients.Total();
    double[] values = new double[Nutrients.KEYS.length];
    java.util.Arrays.fill(values, Double.NaN);
    values[0] = 1100;
    unknown.add(values, 1);
    DailyExpenditure.Estimate e = DailyExpenditure.calculate(883, unknown, null, 0, false);
    assertTrue(e.nutritionIncomplete);
    assertTrue(e.stepsIncomplete);
    assertNull(e.steps);
    assertEquals(883, e.total, 0);
    DailyExpenditure.Estimate zero =
        DailyExpenditure.calculate(883, new Nutrients.Total(), 0L, 0, false);
    assertFalse(zero.incomplete());
    assertEquals(0, zero.steps, 0);
  }

  @Test
  public void staleSavedStepsAreRetainedAndFlagged() {
    DailyExpenditure.Estimate e =
        DailyExpenditure.calculate(883, new Nutrients.Total(), 6000L, 0, true);
    assertEquals(180, e.steps, 0);
    assertTrue(e.stepsIncomplete);
  }
}
