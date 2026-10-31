package dev.rohit.labelaudit;

import java.util.UUID;
import org.json.*;

/** Pure recorded-fact edits used by the UI. Every operation returns an independent copy. */
public final class BatchOperations {
  public static double remaining(JSONObject batch) throws Exception {
    JSONArray events = batch.optJSONArray("eatingEvents");
    double[] shares = new double[events == null ? 0 : events.length()];
    for (int i = 0; i < shares.length; i++)
      shares[i] = events.getJSONObject(i).getDouble("fraction");
    return RecordedPortions.remaining(shares, batch.getDouble("share"));
  }

  public static JSONObject record(JSONObject original, double fraction, long time, String source)
      throws Exception {
    if (!original.isNull("completed"))
      throw new IllegalArgumentException("This batch is already finished");
    if (fraction <= 0 || !Double.isFinite(fraction) || fraction > remaining(original) + 1e-9)
      throw new IllegalArgumentException("Portion exceeds the remaining share");
    JSONObject batch = new JSONObject(original.toString());
    JSONArray events = batch.optJSONArray("eatingEvents");
    if (events == null) events = new JSONArray();
    String id = UUID.randomUUID().toString();
    events.put(
        new JSONObject()
            .put("id", id)
            .put("time", time)
            .put("fraction", fraction)
            .put("source", source));
    batch.put("eatingEvents", events).put("portionTracking", true);
    if (source.equals("finish")) batch.put("finishEventId", id);
    Data.validate("batch", batch);
    return batch;
  }

  public static JSONObject finish(JSONObject original, long time) throws Exception {
    if (!original.isNull("completed")) return new JSONObject(original.toString());
    double remaining = remaining(original);
    JSONObject batch =
        remaining > 1e-9
            ? record(original, remaining, time, "finish")
            : new JSONObject(original.toString());
    batch.put("completed", time).put("portionTracking", true);
    Data.validate("batch", batch);
    return batch;
  }

  public static JSONObject undo(JSONObject original) throws Exception {
    JSONObject batch = new JSONObject(original.toString());
    String id = batch.optString("finishEventId");
    JSONArray events = batch.optJSONArray("eatingEvents");
    if (events != null)
      for (int i = events.length() - 1; i >= 0; i--)
        if (events.getJSONObject(i).getString("id").equals(id)) events.remove(i);
    batch.remove("finishEventId");
    batch.put("completed", JSONObject.NULL);
    Data.validate("batch", batch);
    return batch;
  }

  public static JSONObject duplicate(JSONObject original, long now) throws Exception {
    JSONObject batch = new JSONObject(original.toString());
    batch
        .put("id", UUID.randomUUID().toString())
        .put("available", now)
        .put("completed", JSONObject.NULL)
        .put("share", 1)
        .put("portionTracking", true);
    batch.remove("eatingEvents");
    batch.remove("finishEventId");
    return batch;
  }
}
