package dev.rohit.labelaudit;

import java.time.*;
import java.util.function.ToDoubleFunction;

/** A day's expenditure accrues by its elapsed local-day fraction (23/24/25 hours). */
public final class Energy {
  public static double accrued(
      long from, long to, long trackingStart, ZoneId zone, ToDoubleFunction<LocalDate> rate) {
    long cursor = Math.max(from, trackingStart);
    double sum = 0;
    while (cursor < to) {
      LocalDate day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate();
      long a = day.atStartOfDay(zone).toInstant().toEpochMilli(),
          b = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
          end = Math.min(b, to);
      sum += rate.applyAsDouble(day) * (end - cursor) / (double) (b - a);
      cursor = end;
    }
    return sum;
  }
}
