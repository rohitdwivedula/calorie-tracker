package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class LookupArchiveTest {
  JSONObject lookup() throws Exception {
    String raw = "{ \"status\":1,\"product\":{\"future_field\":\"α\"} }";
    return new JSONObject()
        .put("id", "lookup-1")
        .put("barcode", "012345678901")
        .put("retrievedAt", 1700000000000L)
        .put("url", "https://world.openfoodfacts.org/api/v2/product/012345678901.json")
        .put("payload", raw)
        .put("sha256", Data.payloadHash(raw));
  }

  JSONArray records() throws Exception {
    return new JSONArray()
        .put(
            new JSONObject()
                .put("kind", "food")
                .put(
                    "data",
                    new JSONObject().put("barcode", "012345678901").put("lookupId", "lookup-1")));
  }

  @Test
  public void retainsUnknownFieldsAndExactJson() throws Exception {
    JSONObject j = lookup();
    Data.validateLookup(j);
    assertEquals(j.getString("payload"), new JSONObject(j.toString()).getString("payload"));
  }

  @Test
  public void referencesLinkWithoutDuplicatingPayload() throws Exception {
    Data.validateLookupReferences(records(), new JSONArray().put(lookup()));
  }

  @Test(expected = Exception.class)
  public void tamperedResponseRejected() throws Exception {
    Data.validateLookup(lookup().put("payload", "{}"));
  }

  @Test(expected = Exception.class)
  public void missingArchiveRejected() throws Exception {
    Data.validateLookupReferences(records(), new JSONArray());
  }

  @Test(expected = Exception.class)
  public void duplicateIdsRejected() throws Exception {
    JSONObject j = lookup();
    Data.validateLookupReferences(records(), new JSONArray().put(j).put(j));
  }

  @Test(expected = Exception.class)
  public void wrongBarcodeRejected() throws Exception {
    Data.validateLookupReferences(
        records(),
        new JSONArray()
            .put(
                lookup()
                    .put("barcode", "123456789012")
                    .put(
                        "url",
                        "https://world.openfoodfacts.org/api/v2/product/123456789012.json")));
  }

  @Test(expected = Exception.class)
  public void invalidSourceRejected() throws Exception {
    Data.validateLookup(lookup().put("url", "https://example.com"));
  }

  @Test(expected = Exception.class)
  public void malformedJsonRejected() throws Exception {
    Data.validateLookup(
        lookup().put("payload", "broken").put("sha256", Data.payloadHash("broken")));
  }

  @Test(expected = Exception.class)
  public void oversizedPayloadRejected() throws Exception {
    String large = "x".repeat(Data.MAX_LOOKUP_BYTES + 1);
    Data.validateLookup(lookup().put("payload", large).put("sha256", Data.payloadHash(large)));
  }

  @Test
  public void olderFoodsNeedNoArchive() throws Exception {
    Data.validateLookupReferences(
        new JSONArray()
            .put(
                new JSONObject()
                    .put("kind", "food")
                    .put("data", new JSONObject().put("barcode", "012345678901"))),
        new JSONArray());
  }
}
