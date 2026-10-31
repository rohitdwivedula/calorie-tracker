package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class NutrientReferencesTest {
  private JSONObject profile(int age, String sex, String stage) throws Exception {
    return new JSONObject()
        .put("policy", NutrientReferences.POLICY)
        .put("age", age)
        .put("sex", sex)
        .put("stage", stage);
  }

  private double shown(String key, JSONObject p) {
    int i = java.util.Arrays.asList(Nutrients.KEYS).indexOf(key);
    return NutrientReferences.value(i, p) * Nutrients.displayFactor(i);
  }

  @Test
  public void maleAdultUsesRdaAiAndCdrrNotLabelDailyValues() throws Exception {
    JSONObject p = profile(30, "male", "general");
    assertEquals(56, shown("protein", p), 1e-9);
    assertEquals(38, shown("fiber", p), 1e-9);
    assertEquals(15, shown("vitamin-d", p), 1e-9);
    assertEquals(8, shown("iron", p), 1e-9);
    assertEquals(3400, shown("potassium", p), 1e-9);
    assertEquals(2300, shown("sodium", p), 1e-9);
    assertEquals("AI", NutrientReferences.basis(4));
    assertEquals("CDRR limit", NutrientReferences.basis(22));
    assertEquals(0, shown("fat", p), 0);
  }

  @Test
  public void referenceBoundariesAreInclusiveAndSexSpecific() throws Exception {
    assertEquals(400, shown("magnesium", profile(30, "male", "general")), 1e-9);
    assertEquals(420, shown("magnesium", profile(31, "male", "general")), 1e-9);
    assertEquals(1000, shown("calcium", profile(50, "female", "general")), 1e-9);
    assertEquals(1200, shown("calcium", profile(51, "female", "general")), 1e-9);
    assertEquals(1000, shown("calcium", profile(70, "male", "general")), 1e-9);
    assertEquals(1200, shown("calcium", profile(71, "male", "general")), 1e-9);
    assertEquals(15, shown("vitamin-d", profile(70, "female", "general")), 1e-9);
    assertEquals(20, shown("vitamin-d", profile(71, "female", "general")), 1e-9);
    assertEquals(18, shown("iron", profile(50, "female", "general")), 1e-9);
    assertEquals(8, shown("iron", profile(51, "female", "general")), 1e-9);
    assertEquals(21, shown("fiber", profile(51, "female", "general")), 1e-9);
  }

  @Test
  public void pregnancyAndLactationUseTheirOwnPublishedRows() throws Exception {
    JSONObject p = profile(31, "female", "pregnant");
    assertEquals(71, shown("protein", p), 1e-9);
    assertEquals(175, shown("carbs", p), 1e-9);
    assertEquals(600, shown("vitamin-b9", p), 1e-9);
    assertEquals(27, shown("iron", p), 1e-9);
    assertEquals(360, shown("magnesium", p), 1e-9);
    p = profile(31, "female", "lactating");
    assertEquals(210, shown("carbs", p), 1e-9);
    assertEquals(1300, shown("vitamin-a", p), 1e-9);
    assertEquals(2800, shown("potassium", p), 1e-9);
    assertEquals(9, shown("iron", p), 1e-9);
  }

  @Test
  public void normalizedUnitsAndReferenceKindsAreExplicit() throws Exception {
    JSONObject p = profile(30, "male", "general");
    assertEquals(.0009, NutrientReferences.value(5, p), 1e-12);
    assertEquals(.09, NutrientReferences.value(6, p), 1e-12);
    assertEquals(.0000024, NutrientReferences.value(15, p), 1e-12);
    assertEquals("µg RAE", NutrientReferences.unit(5));
    assertEquals("mg NE", NutrientReferences.unit(12));
    assertEquals("µg DFE", NutrientReferences.unit(14));
    for (int i = 1; i < Nutrients.KEYS.length; i++)
      if (i != 3) assertTrue(NutrientReferences.value(i, p) > 0);
    assertEquals(0, NutrientReferences.value(1, null), 0);
  }

  @Test
  public void invalidProfilesFailWithoutAssumedDemographics() throws Exception {
    for (JSONObject p :
        new JSONObject[] {
          profile(18, "male", "general"),
          profile(30, "", "general"),
          profile(30, "male", "pregnant"),
          profile(51, "female", "pregnant"),
          profile(30, "female", "unknown"),
          profile(30, "male", "general").put("age", 30.5)
        }) {
      try {
        NutrientReferences.validate(p);
        fail("Invalid profile accepted");
      } catch (IllegalArgumentException expected) {
      }
    }
  }
}
