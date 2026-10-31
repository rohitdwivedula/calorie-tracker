# Adult nutrient reference policy

Policy: `us-dri-adult-v1`, checked against the official sources below on 2026-10-31.
This is a bundled, offline reference calculation over an explicitly entered profile.
It does not estimate requirements from food logs or change intake/expenditure facts.

The profile records age in years, a male/female reference-table category and general,
pregnancy or lactation life stage. Adult ages 19–120 are supported. Pregnancy/lactation
rows cover female ages 19–50; unsupported combinations fail validation. No demographics
are inferred. Age is the entered current age and can be updated in Settings.

RDA and AI are adequacy reference targets, not safety upper limits. Sodium uses the
2019 **CDRR threshold of 2,300 mg/day**, not an RDA, AI or UL. Its statistics count days
within the threshold, whereas RDA/AI statistics count days reaching the reference.
Missing nutrition remains unknown; a partial day's known totals are minima, not zeros.

| Nutrient | Male adult | Female adult | Pregnancy 19–50 | Lactation 19–50 |
|---|---|---|---|---|
| Protein, RDA | 56 g | 46 g | 71 g | 71 g |
| Carbohydrate, RDA | 130 g | 130 g | 175 g | 210 g |
| Total fat | No fixed gram RDA | No fixed gram RDA | No fixed gram RDA | No fixed gram RDA |
| Fiber, AI | 38 g through 50; 30 g after 50 | 25 g through 50; 21 g after 50 | 28 g | 29 g |
| Vitamin A, RDA | 900 µg RAE | 700 µg RAE | 770 µg RAE | 1,300 µg RAE |
| Vitamin C, RDA | 90 mg | 75 mg | 85 mg | 120 mg |
| Vitamin D, RDA | 15 µg through 70; 20 µg after 70 | Same | 15 µg | 15 µg |
| Vitamin E, RDA | 15 mg alpha-tocopherol | Same | 15 mg | 19 mg |
| Vitamin K, AI | 120 µg | 90 µg | 90 µg | 90 µg |
| Thiamin/B1, RDA | 1.2 mg | 1.1 mg | 1.4 mg | 1.4 mg |
| Riboflavin/B2, RDA | 1.3 mg | 1.1 mg | 1.4 mg | 1.6 mg |
| Niacin/B3, RDA | 16 mg NE | 14 mg NE | 18 mg NE | 17 mg NE |
| Vitamin B6, RDA | 1.3 mg through 50; 1.7 mg after 50 | 1.3 mg through 50; 1.5 mg after 50 | 1.9 mg | 2 mg |
| Folate/B9, RDA | 400 µg DFE | Same | 600 µg DFE | 500 µg DFE |
| Vitamin B12, RDA | 2.4 µg | Same | 2.6 µg | 2.8 µg |
| Calcium, RDA | 1,000 mg through 70; 1,200 mg after 70 | 1,000 mg through 50; 1,200 mg after 50 | 1,000 mg | 1,000 mg |
| Iron, RDA | 8 mg | 18 mg through 50; 8 mg after 50 | 27 mg | 9 mg |
| Magnesium, RDA | 400 mg through 30; 420 mg after 30 | 310 mg through 30; 320 mg after 30 | 350 mg through 30; 360 mg after 30 | 310 mg through 30; 320 mg after 30 |
| Potassium, AI | 3,400 mg | 2,600 mg | 2,900 mg | 2,800 mg |
| Zinc, RDA | 11 mg | 8 mg | 11 mg | 12 mg |
| Selenium, RDA | 55 µg | Same | 60 µg | 70 µg |
| Sodium, CDRR threshold | 2,300 mg | Same | Same | Same |

Protein uses the published reference-body-weight gram values (the adult RDA basis is
0.8 g/kg), not an entered body weight or an athletic target. Total fat displays its
adult AMDR of 20–35% of energy rather than inventing a fixed gram RDA. Calories continue
to use the app's existing expenditure/deficit settings, with no generic calorie target.

Reference targets do not adjust automatically for diet, smoking or individual health
circumstances. Existing and new custom targets always override references. Removing an
override returns to the profile's reference, and removing the profile retains overrides.
All historical views use the current reference profile/overrides; food facts are unchanged.

Values use normalized grams internally. Vitamin A references are RAE, folate DFE, niacin
NE and vitamin E alpha-tocopherol; comparison quality depends on the food provider/label
using comparable nutrient forms. This change does not infer chemical forms or perform
unjustified conversions of historical snapshots. Reference units are explicit in the UI.

The optional `settings.nutrientProfile` object includes policy, age, sex and stage. Defaults
are derived at read time and are never copied into the custom `nutrientGoals` map. Settings
validation also validates imported profiles transactionally; the existing schema 4 JSON
backup carries the profile without a SQL schema change. Backups with no profile still work.

## Official sources

- [NIH DRI definitions and source tables](https://ods.od.nih.gov/HealthInformation/nutrientrecommendations.aspx)
- [National Academies macronutrient reference table and AMDRs](https://www.nationalacademies.org/read/10872/chapter/13)
- [2019 sodium/potassium DRI update and CDRR](https://nap.nationalacademies.org/resource/25353/interactive/)
- NIH ODS fact sheets, Recommended Intakes tables:
  [A](https://ods.od.nih.gov/factsheets/VitaminA-HealthProfessional/),
  [C](https://ods.od.nih.gov/factsheets/VitaminC-Consumer/),
  [D](https://ods.od.nih.gov/factsheets/VitaminD-HealthProfessional/),
  [E](https://ods.od.nih.gov/factsheets/VitaminE-HealthProfessional/),
  [K](https://ods.od.nih.gov/factsheets/VitaminK-HealthProfessional/),
  [B1](https://ods.od.nih.gov/factsheets/Thiamin-HealthProfessional/),
  [B2](https://ods.od.nih.gov/factsheets/Riboflavin-HealthProfessional/),
  [B3](https://ods.od.nih.gov/factsheets/Niacin-HealthProfessional/),
  [B6](https://ods.od.nih.gov/factsheets/VitaminB6-HealthProfessional/),
  [B9](https://ods.od.nih.gov/factsheets/Folate-Consumer/),
  [B12](https://ods.od.nih.gov/factsheets/VitaminB12-HealthProfessional/),
  [calcium](https://ods.od.nih.gov/factsheets/Calcium-HealthProfessional/),
  [iron](https://ods.od.nih.gov/factsheets/Iron-HealthProfessional/),
  [magnesium](https://ods.od.nih.gov/factsheets/Magnesium-HealthProfessional/),
  [potassium](https://ods.od.nih.gov/factsheets/Potassium-HealthProfessional/),
  [zinc](https://ods.od.nih.gov/factsheets/Zinc-HealthProfessional/),
  [selenium](https://ods.od.nih.gov/factsheets/Selenium-HealthProfessional/).
