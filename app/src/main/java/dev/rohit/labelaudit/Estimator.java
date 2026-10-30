package dev.rohit.labelaudit;

/** Pure, replaceable linear-v1 estimator. All intervals are half-open instants. */
public final class Estimator {
  public static final String POLICY = "linear-v1";

  public static double fraction(
      long available, Long completed, long duration, long from, long to, long asOf) {
    long end = completed == null ? available + duration : completed;
    if (end < available || duration <= 0) throw new IllegalArgumentException("Invalid interval");
    long cutoff = Math.min(to, asOf);
    if (end == available) return available >= from && available < cutoff ? 1 : 0;
    return Math.max(0, Math.min(end, cutoff) - Math.max(available, from))
        / (double) (end - available);
  }
}
