package dev.rohit.labelaudit;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONObject;

/**
 * US healthy-adult DRI reference tables (19+), NOT safety upper limits or individual prescriptions.
 * Sources and reference-unit conventions: docs/nutrient-references.md. Values normalized to grams;
 * no food facts are rewritten. Protein uses the published reference-body-weight gram values. Sodium
 * is the 2019 CDRR threshold (reduce above 2300 mg), not an RDA/AI/UL.
 */
public final class NutrientReferences {
  public static final String POLICY = "us-dri-adult-v1";

  private NutrientReferences() {}

  public static YearMonth parseBirthMonth(String text) {
    if (!text.matches("(0[1-9]|1[0-2])/[0-9]{4}"))
      throw new IllegalArgumentException("Enter birth month/year as MM/YYYY");
    return YearMonth.parse(
        text,
        DateTimeFormatter.ofPattern("MM/uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT));
  }

  /** No birth day is stored. Age advances on the first day of the birth month. */
  public static int age(JSONObject profile, YearMonth asOf) throws Exception {
    boolean birth = profile.has("birthMonth") || profile.has("birthYear");
    if (!birth) {
      int legacy = profile.getInt("age");
      if (profile.getDouble("age") != legacy)
        throw new IllegalArgumentException("Age must be whole years");
      return legacy;
    }
    if (profile.has("age"))
      throw new IllegalArgumentException(
          "Profile must contain birth month/year or legacy age, not both");
    int month = profile.getInt("birthMonth"), year = profile.getInt("birthYear");
    if (profile.getDouble("birthMonth") != month
        || profile.getDouble("birthYear") != year
        || year < 1000
        || year > 9999) throw new IllegalArgumentException("Invalid birth month/year");
    YearMonth.of(year, month);
    return asOf.getYear() - year - (asOf.getMonthValue() < month ? 1 : 0);
  }

  public static void validate(JSONObject profile) throws Exception {
    validate(profile, YearMonth.now());
  }

  public static void validate(JSONObject profile, YearMonth asOf) throws Exception {
    if (!POLICY.equals(profile.getString("policy")))
      throw new IllegalArgumentException("Unsupported nutrient reference policy");
    int age = age(profile, asOf);
    if (age < 19 || age > 120)
      throw new IllegalArgumentException("Adult reference age must be 19–120 years");
    String sex = profile.getString("sex"), stage = profile.getString("stage");
    if (!Arrays.asList("male", "female").contains(sex))
      throw new IllegalArgumentException("Choose a DRI reference sex");
    if (!Arrays.asList("general", "pregnant", "lactating").contains(stage))
      throw new IllegalArgumentException("Invalid reference life stage");
    if (!stage.equals("general") && (!sex.equals("female") || age > 50))
      throw new IllegalArgumentException(
          "Pregnancy/lactation reference tables cover female ages 19–50");
  }

  public static double value(int index, JSONObject profile) {
    return value(index, profile, YearMonth.now());
  }

  public static double value(int index, JSONObject profile, YearMonth asOf) {
    if (profile == null) return 0;
    int age;
    try {
      validate(profile, asOf);
      age = age(profile, asOf);
    } catch (Exception e) {
      // Time can move an existing profile beyond a supported life-stage/age row. Keep food
      // logging usable and the saved facts intact; do not invent a new reference category.
      return 0;
    }
    boolean male = profile.optString("sex").equals("male");
    String stage = profile.optString("stage");
    // Order matches Nutrients.KEYS. These literals use the displayed g/mg/microgram units.
    double[] values;
    if (stage.equals("pregnant")) {
      values =
          new double[] {
            0,
            71,
            175,
            0,
            28,
            770,
            85,
            15,
            15,
            90,
            1.4,
            1.4,
            18,
            1.9,
            600,
            2.6,
            1000,
            27,
            age <= 30 ? 350 : 360,
            2900,
            11,
            60,
            2300
          };
    } else if (stage.equals("lactating")) {
      values =
          new double[] {
            0,
            71,
            210,
            0,
            29,
            1300,
            120,
            15,
            19,
            90,
            1.4,
            1.6,
            17,
            2,
            500,
            2.8,
            1000,
            9,
            age <= 30 ? 310 : 320,
            2800,
            12,
            70,
            2300
          };
    } else if (male) {
      values =
          new double[] {
            0,
            56,
            130,
            0,
            age <= 50 ? 38 : 30,
            900,
            90,
            age <= 70 ? 15 : 20,
            15,
            120,
            1.2,
            1.3,
            16,
            age <= 50 ? 1.3 : 1.7,
            400,
            2.4,
            age <= 70 ? 1000 : 1200,
            8,
            age <= 30 ? 400 : 420,
            3400,
            11,
            55,
            2300
          };
    } else {
      values =
          new double[] {
            0,
            46,
            130,
            0,
            age <= 50 ? 25 : 21,
            700,
            75,
            age <= 70 ? 15 : 20,
            15,
            90,
            1.1,
            1.1,
            14,
            age <= 50 ? 1.3 : 1.5,
            400,
            2.4,
            age <= 50 ? 1000 : 1200,
            age <= 50 ? 18 : 8,
            age <= 30 ? 310 : 320,
            2600,
            8,
            55,
            2300
          };
    }
    return values[index] / Nutrients.displayFactor(index);
  }

  public static String basis(int index) {
    if (index == 0 || index == 3) return "";
    if (index == 22) return "CDRR limit";
    return index == 4 || index == 9 || index == 19 ? "AI" : "RDA";
  }

  public static String unit(int index) {
    if (index == 5) return "µg RAE";
    if (index == 12) return "mg NE";
    if (index == 14) return "µg DFE";
    return Nutrients.unit(index);
  }
}
