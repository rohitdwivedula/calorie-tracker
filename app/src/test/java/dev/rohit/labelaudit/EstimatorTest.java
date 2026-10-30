package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import java.time.*;
import org.junit.Test;

public class EstimatorTest {
  @Test
  public void provisionalCapsWithoutCompleting() {
    assertEquals(.5, Estimator.fraction(100L, null, 100, 0, 200, 150), 1e-9);
    assertEquals(1, Estimator.fraction(100L, null, 100, 0, 1000, 900), 1e-9);
  }

  @Test
  public void reconcilesInsteadOfAdding() {
    assertEquals(.5, Estimator.fraction(0L, null, 100, 0, 100, 50), 1e-9);
    assertEquals(.25, Estimator.fraction(0L, 200L, 100, 0, 50, 300), 1e-9);
  }

  @Test
  public void boundariesPartitionWholeBatch() {
    double a = Estimator.fraction(10L, 110L, 100, 0, 50, 200),
        b = Estimator.fraction(10L, 110L, 100, 50, 200, 200);
    assertEquals(1, a + b, 1e-9);
  }

  @Test
  public void pointAllocatedExactlyOnce() {
    assertEquals(0, Estimator.fraction(50L, 50L, 100, 0, 50, 100), 0);
    assertEquals(1, Estimator.fraction(50L, 50L, 100, 50, 100, 100), 0);
  }

  @Test
  public void futureAvailabilityIsZero() {
    assertEquals(0, Estimator.fraction(100L, null, 100, 0, 200, 50), 0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsInvalidCompletion() {
    Estimator.fraction(100L, 50L, 100, 0, 200, 200);
  }

  @Test
  public void daylightSavingUsesInstants() {
    ZoneId z = ZoneId.of("America/Chicago");
    long a = LocalDate.of(2026, 11, 1).atStartOfDay(z).toInstant().toEpochMilli(),
        b = LocalDate.of(2026, 11, 2).atStartOfDay(z).toInstant().toEpochMilli();
    assertEquals(25 * 3600000, b - a);
    assertEquals(1, Estimator.fraction(a, b, 100, a, b, b), 0);
  }
}
