package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import org.junit.Test;

public class BarcodeValueTest {
  @Test
  public void preservesLeadingZeros() {
    assertEquals("0193968031206", BarcodeValue.productCode("0193968031206"));
  }

  @Test
  public void readsUserTomatoBarcode() {
    assertEquals("193968031206", BarcodeValue.productCode("193968031206"));
  }

  @Test
  public void supportsGs1WithoutFetchingItsUrl() {
    assertEquals(
        "00012345678905", BarcodeValue.productCode("https://example.com/01/00012345678905/10/ABC"));
  }

  @Test
  public void supportsOffProductLinks() {
    assertEquals(
        "193968031206",
        BarcodeValue.productCode("https://world.openfoodfacts.org/product/193968031206/tomatoes"));
  }

  @Test
  public void rejectsArbitraryQrUrls() {
    assertNull(BarcodeValue.productCode("https://example.com/private"));
    assertNull(BarcodeValue.productCode("https://evilopenfoodfacts.org/product/193968031206"));
  }

  @Test
  public void rejectsMalformed() {
    assertNull(BarcodeValue.productCode(null));
    assertNull(BarcodeValue.productCode("../12345678"));
    assertNull(BarcodeValue.productCode("123"));
  }
}
