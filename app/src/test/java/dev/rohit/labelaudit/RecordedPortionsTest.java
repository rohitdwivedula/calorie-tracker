package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecordedPortionsTest {
  @Test
  public void pastaThirdCountsImmediately() {
    assertEquals(
        1300.0 / 3,
        1300 * RecordedPortions.fraction(new long[] {10}, new double[] {1.0 / 3}, 0, 100, 100),
        1e-9);
  }

  @Test
  public void arbitraryFineGrainedShare() {
    assertEquals(
        390,
        1300 * RecordedPortions.fraction(new long[] {10}, new double[] {.3}, 0, 100, 100),
        1e-9);
  }

  @Test
  public void finishingDoesNotRewriteEarlierPortion() {
    long[] t = {10, 110};
    double[] s = {.3, .7};
    assertEquals(.3, RecordedPortions.fraction(t, s, 0, 100, 200), 1e-9);
    assertEquals(.7, RecordedPortions.fraction(t, s, 100, 200, 200), 1e-9);
  }

  @Test
  public void finishingRecordsOnlyRemaining() {
    assertEquals(.4, RecordedPortions.remaining(new double[] {.3, .3}, 1), 1e-9);
  }

  @Test
  public void threeExactThirdsHaveNoRemainder() {
    assertEquals(0, RecordedPortions.remaining(new double[] {1.0 / 3, 1.0 / 3, 1.0 / 3}, 1), 1e-9);
  }

  @Test(expected = IllegalArgumentException.class)
  public void cannotExceedBatch() {
    RecordedPortions.remaining(new double[] {.75, .5}, 1);
  }

  @Test
  public void ownShareIsLimitNotSecondMultiplier() {
    assertEquals(.1, RecordedPortions.remaining(new double[] {.3, .5}, .9), 1e-9);
  }

  @Test
  public void boundariesNeverDoubleCharge() {
    long[] t = {10, 100};
    double[] s = {.3, .7};
    assertEquals(
        1,
        RecordedPortions.fraction(t, s, 0, 100, 200)
            + RecordedPortions.fraction(t, s, 100, 200, 200),
        1e-9);
  }

  @Test
  public void removingAllPortionsMeansZero() {
    assertEquals(0, RecordedPortions.fraction(new long[] {}, new double[] {}, 0, 100, 100), 0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void nonFiniteShareRejected() {
    RecordedPortions.total(new double[] {Double.NaN}, 1);
  }
}
