package dev.rohit.labelaudit;

/** Fractions of the whole batch are recorded facts, not inferred session sizes. */
public final class RecordedPortions {
  public static double total(double[] shares, double limit) {
    if (!Double.isFinite(limit) || limit < 0 || limit > 1)
      throw new IllegalArgumentException("Invalid batch share");
    double sum = 0;
    for (double share : shares) {
      if (!Double.isFinite(share) || share <= 0 || share > 1)
        throw new IllegalArgumentException("Enter a share between 0 and 100%");
      sum += share;
    }
    if (sum > limit + 1e-9)
      throw new IllegalArgumentException("Portions exceed your share of the batch");
    return Math.min(sum, limit);
  }

  public static double fraction(long[] times, double[] shares, long from, long to, long asOf) {
    if (times.length != shares.length)
      throw new IllegalArgumentException("Invalid portion records");
    total(shares, 1);
    double sum = 0;
    for (int i = 0; i < times.length; i++)
      if (times[i] >= from && times[i] < Math.min(to, asOf)) sum += shares[i];
    return Math.min(sum, 1);
  }

  public static double remaining(double[] shares, double limit) {
    return Math.max(0, limit - total(shares, limit));
  }
}
