package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
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

  private JSONObject birthProfile(int month, int year, String sex, String stage) throws Exception {
    return new JSONObject()
        .put("policy", NutrientReferences.POLICY)
        .put("birthMonth", month)
        .put("birthYear", year)
        .put("sex", sex)
        .put("stage", stage);
  }

  @Test
  public void ageAdvancesOnlyAtStartOfBirthMonthIncludingYearRollover() throws Exception {
    JSONObject p = birthProfile(12, 1996, "male", "general");
    assertEquals(29, NutrientReferences.age(p, YearMonth.of(2026, 11)));
    assertEquals(30, NutrientReferences.age(p, YearMonth.of(2026, 12)));
    assertEquals(30, NutrientReferences.age(p, YearMonth.of(2027, 1)));
    assertEquals(31, NutrientReferences.age(p, YearMonth.of(2027, 12)));
    assertEquals(YearMonth.of(1996, 12), NutrientReferences.parseBirthMonth("12/1996"));
    assertFalse(p.has("age"));
    assertFalse(p.has("birthDay"));
  }

  @Test
  public void nutrientBandsUpdateAutomaticallyFromBirthMonth() throws Exception {
    JSONObject p = birthProfile(3, 1996, "male", "general");
    assertEquals(.4, NutrientReferences.value(18, p, YearMonth.of(2027, 2)), 1e-12);
    assertEquals(.42, NutrientReferences.value(18, p, YearMonth.of(2027, 3)), 1e-12);
    p = birthProfile(3, 1976, "female", "general");
    assertEquals(1.0, NutrientReferences.value(16, p, YearMonth.of(2027, 2)), 1e-12);
    assertEquals(1.2, NutrientReferences.value(16, p, YearMonth.of(2027, 3)), 1e-12);
    p = birthProfile(3, 1956, "male", "general");
    assertEquals(.000015, NutrientReferences.value(7, p, YearMonth.of(2027, 2)), 1e-12);
    assertEquals(.000020, NutrientReferences.value(7, p, YearMonth.of(2027, 3)), 1e-12);
  }

  @Test
  public void birthMonthUsesLocalCalendarAndLegacyAgesStayUnchanged() throws Exception {
    JSONObject p = birthProfile(11, 1996, "male", "general");
    Instant boundary = Instant.parse("2026-11-01T00:30:00Z");
    assertEquals(
        29,
        NutrientReferences.age(p, YearMonth.from(boundary.atZone(ZoneId.of("America/Chicago")))));
    assertEquals(30, NutrientReferences.age(p, YearMonth.from(boundary.atZone(ZoneId.of("UTC")))));
    JSONObject old = profile(30, "male", "general");
    assertEquals(30, NutrientReferences.age(old, YearMonth.of(2030, 12)));
    assertFalse(old.has("birthMonth"));
  }

  @Test
  public void malformedAndUnsupportedBirthProfilesAreRejected() throws Exception {
    for (String text : new String[] {"1/1996", "13/1996", "01/96", "1996-01", "01/1996/15"}) {
      try {
        NutrientReferences.parseBirthMonth(text);
        fail("Malformed birth month accepted");
      } catch (IllegalArgumentException expected) {
      }
    }
    for (JSONObject p :
        new JSONObject[] {
          birthProfile(13, 1996, "male", "general"),
          birthProfile(1, 2030, "male", "general"),
          birthProfile(1, 2010, "male", "general"),
          birthProfile(1, 1996, "male", "general").put("age", 30),
          birthProfile(1, 1996, "male", "general").put("birthMonth", 1.5)
        }) {
      try {
        NutrientReferences.validate(p, YearMonth.of(2026, 10));
        fail("Invalid birth profile accepted");
      } catch (Exception expected) {
      }
    }
  }

  @Test
  public void agingBeyondLifeStageTableDoesNotCrashOrRewriteProfile() throws Exception {
    JSONObject p = birthProfile(11, 1976, "female", "pregnant");
    assertEquals(71, NutrientReferences.value(1, p, YearMonth.of(2026, 11)), 0);
    assertEquals(0, NutrientReferences.value(1, p, YearMonth.of(2027, 11)), 0);
    assertEquals("pregnant", p.getString("stage"));
    assertEquals(1976, p.getInt("birthYear"));
  }
}
