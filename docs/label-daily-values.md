# Manual label percentages

The food editor accepts vitamins/minerals as an amount or **% DV**. Values apply to
the food's nutrition basis, not the quantity subsequently eaten. Entering an amount
explicitly replaces that nutrient's percentage provenance. Core calories/macros
require explicit values; blank optional nutrients remain unknown.

Choose **Current US** or **Older US** to match the printed label. Older formats often
include “Calories from Fat.” The choice is remembered while editing that food.
References are the general US food-label values for adults/children 4+, independent
of the user's age/sex-specific RDA/AI goals. Non-US and infant labels are not supported.

Current reference values come from the [FDA Daily Value table](https://www.fda.gov/food/nutrition-facts-label/daily-value-nutrition-and-supplement-facts-labels).
Older reference values come from the [FDA nutrition-labeling database guide](https://www.fda.gov/regulatory-information/search-fda-guidance-documents/guidance-industry-guide-developing-and-using-data-bases-nutrition-labeling).
The versioned constants live in `LabelDailyValues.java`.

Percentage × reference / 100 yields the amount before normalization to grams.
Older vitamin D uses IU; A, E and folate also require the nutrient's form to convert
to the app's RAE, alpha-tocopherol and DFE amounts. The dialog supports specific
known forms and otherwise leaves the amount unknown. Mixed forms are not guessed.
Zero percent is a known zero regardless of form. Conversion sources:

- [FDA unit-conversion guidance](https://www.fda.gov/media/129863/download)
- [NIH vitamin A](https://ods.od.nih.gov/factsheets/VitaminA-HealthProfessional/)
- [NIH vitamin E](https://ods.od.nih.gov/factsheets/VitaminE-HealthProfessional/)
- [NIH folate](https://ods.od.nih.gov/factsheets/Folate-HealthProfessional/)

Each optional `labelPercentages` entry stores the nutrient key, original percent,
reference identifier, conversion version and chosen form. Canonical nutrient
amounts remain separate. Percentages are rounded label information, so converted
amounts are approximations. Raw entries survive snapshots and export/restore even
when no amount can be calculated. Imports validate the metadata but do not silently
recalculate recorded amounts. Existing SQLite version 4 and backup versions 1–5
remain supported; no schema migration or data replacement is required.
