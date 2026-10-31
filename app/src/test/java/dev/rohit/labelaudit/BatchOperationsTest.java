package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class BatchOperationsTest {
  JSONObject batch() throws Exception {
    return new JSONObject()
        .put("id", "batch")
        .put("name", "Pasta")
        .put("available", 1)
        .put("completed", JSONObject.NULL)
        .put("share", 1)
        .put("portionTracking", true)
        .put(
            "ingredients",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("quantity", 100)
                        .put(
                            "food",
                            new JSONObject()
                                .put("id", "food")
                                .put("name", "Pasta")
                                .put("basis", 100)
                                .put("unit", "g")
                                .put("kcal", 1300)
                                .put("protein", 10)
                                .put("carbs", 20)
                                .put("fat", 30))));
  }

  @Test
  public void recordDoesNotMutateInput() throws Exception {
    JSONObject original = batch();
    JSONObject changed = BatchOperations.record(original, .3, 10, "ate");
    assertFalse(original.has("eatingEvents"));
    assertEquals(.7, BatchOperations.remaining(changed), 1e-9);
  }

  @Test
  public void finishAddsOnlyRemainingAndIsIdempotent() throws Exception {
    JSONObject b = BatchOperations.record(batch(), .3, 10, "ate");
    b = BatchOperations.finish(b, 20);
    assertEquals(.7, b.getJSONArray("eatingEvents").getJSONObject(1).getDouble("fraction"), 1e-9);
    assertEquals(2, BatchOperations.finish(b, 30).getJSONArray("eatingEvents").length());
  }

  @Test
  public void undoRemovesOnlyFinishPortion() throws Exception {
    JSONObject b = BatchOperations.record(batch(), .3, 10, "ate");
    b = BatchOperations.undo(BatchOperations.finish(b, 20));
    assertTrue(b.isNull("completed"));
    assertEquals(1, b.getJSONArray("eatingEvents").length());
    assertEquals(.7, BatchOperations.remaining(b), 1e-9);
  }

  @Test
  public void duplicateDoesNotCopyHistory() throws Exception {
    JSONObject b = BatchOperations.finish(batch(), 20);
    JSONObject copy = BatchOperations.duplicate(b, 30);
    assertFalse(copy.has("eatingEvents"));
    assertFalse(copy.has("finishEventId"));
    assertNotEquals(b.getString("id"), copy.getString("id"));
    assertEquals(
        b.getJSONArray("ingredients").toString(), copy.getJSONArray("ingredients").toString());
  }

  @Test(expected = IllegalArgumentException.class)
  public void cannotRecordAfterCompletion() throws Exception {
    BatchOperations.record(BatchOperations.finish(batch(), 20), .2, 30, "ate");
  }

  @Test(expected = IllegalArgumentException.class)
  public void cannotOverfill() throws Exception {
    BatchOperations.record(BatchOperations.record(batch(), .75, 10, "ate"), .5, 20, "ate");
  }
}
