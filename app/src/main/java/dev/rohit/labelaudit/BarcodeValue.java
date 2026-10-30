package dev.rohit.labelaudit;

import java.net.URI;
import java.util.regex.*;

public final class BarcodeValue {
  public static String productCode(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.matches("[0-9]{8}|[0-9]{12,14}")) return s;
    try {
      URI u = URI.create(s);
      String host = u.getHost();
      if (host == null
          || !("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())))
        return null;
      Matcher m = Pattern.compile("(?:^|/)01/([0-9]{14})(?:/|$)").matcher(u.getPath());
      if (m.find()) return m.group(1); // GS1 Digital Link: only extract; never visit supplied URL.
      if (host.equals("openfoodfacts.org") || host.endsWith(".openfoodfacts.org")) {
        m = Pattern.compile("/product/([0-9]{8,14})(?:/|$)").matcher(u.getPath());
        if (m.find()) return m.group(1);
      }
    } catch (IllegalArgumentException ignored) {
    }
    return null;
  }
}
