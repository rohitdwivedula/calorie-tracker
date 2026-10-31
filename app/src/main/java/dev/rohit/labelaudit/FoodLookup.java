package dev.rohit.labelaudit;

import java.io.*;
import java.net.*;
import org.json.*;

/** Explicit Open Food Facts lookup; full response archived before projection. */
public final class FoodLookup {
  public static JSONObject fetch(Data db, String raw) throws Exception {
    String code = BarcodeValue.productCode(raw);
    if (code == null) throw new IllegalArgumentException("Unsupported barcode or QR code");
    String lookupId = null;
    HttpURLConnection c =
        (HttpURLConnection)
            new URL("https://world.openfoodfacts.org/api/v2/product/" + code + ".json")
                .openConnection();
    c.setRequestProperty("User-Agent", "BatchNutrition/1.0 (personal Android tracker)");
    c.setConnectTimeout(15000);
    c.setReadTimeout(15000);
    JSONObject product;
    try (InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) {
        if (out.size() + n > Data.MAX_LOOKUP_BYTES) throw new Exception("Response too large");
        out.write(buf, 0, n);
      }
      String rawResponse = out.toString("UTF-8");
      lookupId = db.cacheLookup(code, c.getURL().toString(), rawResponse);
      JSONObject response = new JSONObject(rawResponse);
      if (response.optInt("status") != 1)
        throw new Exception("Unknown product; create it manually");
      product = response.getJSONObject("product");
    } finally {
      c.disconnect();
    }
    JSONObject f =
        new JSONObject()
            .put("barcode", code)
            .put("lookupId", lookupId)
            .put("name", product.optString("product_name", ""));
    JSONObject nutrients = product.optJSONObject("nutriments");
    String servingLabel = product.optString("serving_size", "");
    double grams = Portion.labelAmount(servingLabel, "g"),
        ml = Portion.labelAmount(servingLabel, "ml");
    if (grams == 0 && ml == 0) {
      String declaredUnit = product.optString("serving_quantity_unit");
      double amount = product.optDouble("serving_quantity", 0);
      if (Double.isFinite(amount) && amount > 0) {
        if (declaredUnit.equals("g")) grams = amount;
        else if (declaredUnit.equals("ml")) ml = amount;
      }
    }
    boolean hasServing =
        !servingLabel.isEmpty()
            || grams > 0
            || ml > 0
            || product.optString("nutrition_data_per").equals("serving");
    f.put("basis", hasServing ? 1 : 100)
        .put(
            "unit",
            hasServing
                ? "serving"
                : product.optString("nutrition_data_per").equals("100ml") ? "ml" : "g");
    if (grams > 0) f.put("servingGrams", grams);
    if (ml > 0) f.put("servingMl", ml);
    f.put("servingLabel", servingLabel);
    if (nutrients != null) {
      String[] source = Nutrients.OFF_KEYS, dest = Nutrients.KEYS;
      for (int i = 0; i < source.length; i++) {
        if (hasServing && nutrients.has(source[i] + "_serving"))
          f.put(dest[i], nutrients.getDouble(source[i] + "_serving"));
        else if (nutrients.has(source[i] + "_100g")) {
          if (!hasServing) f.put(dest[i], nutrients.getDouble(source[i] + "_100g"));
          else if (grams > 0 && ml == 0)
            f.put(dest[i], nutrients.getDouble(source[i] + "_100g") * grams / 100);
          else if (ml > 0 && grams == 0 && product.optString("nutrition_data_per").equals("100ml"))
            f.put(dest[i], nutrients.getDouble(source[i] + "_100g") * ml / 100);
        }
      }
    }

    return f;
  }
}
