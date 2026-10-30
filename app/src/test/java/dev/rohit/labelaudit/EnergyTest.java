package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import java.time.*;
import org.junit.Test;

public class EnergyTest {
  final ZoneId z = ZoneId.of("America/Chicago");

  long t(String s) {
    return LocalDate.parse(s).atStartOfDay(z).toInstant().toEpochMilli();
  }

  @Test
  public void trackingStartClipsExpenditure() {
    assertEquals(
        2000,
        Energy.accrued(t("2026-10-01"), t("2026-10-03"), t("2026-10-02"), z, d -> 2000),
        .001);
  }

  @Test
  public void effectiveDatePreservesPriorRate() {
    assertEquals(
        4200,
        Energy.accrued(
            t("2026-10-01"),
            t("2026-10-03"),
            0,
            z,
            d -> d.isBefore(LocalDate.parse("2026-10-02")) ? 2000 : 2200),
        .001);
  }

  @Test
  public void dstFullDayStillOneDailyRate() {
    assertEquals(2000, Energy.accrued(t("2026-11-01"), t("2026-11-02"), 0, z, d -> 2000), .001);
  }

  @Test
  public void partialDayUsesSameAsOf() {
    long a = t("2026-11-01"), b = t("2026-11-02");
    assertEquals(1000, Energy.accrued(a, a + (b - a) / 2, 0, z, d -> 2000), .001);
  }
}
