# Label JSON handoff

From **Food nutrition**, tap **JSON**. The app copies a generic transcription
prompt to the clipboard and opens a dialog. Send the prompt and label photo to
another app, then copy its JSON, return and tap **Paste JSON → Fill form**.
Review the populated manual form before saving or adding the food to a dish.
The app itself does not upload photos, send nutrition data, or call an LLM.

This is a food-form import, separate from database backup/restore. It cannot import
record IDs, API credentials, history or settings. Invalid JSON leaves the form
unchanged. A valid import replaces all nutrition fields and serving conversions;
missing values clear prior amounts rather than mixing two labels. A missing/null
name preserves the name already typed. Cancelling does not change the food form.
The open dialog and its text survive activity recreation without copying the
prompt over the returned JSON again.

`LabelJson.prompt` documents the versioned format and all supported nutrient keys.
This synthetic example is nutrition per one 80 g serving:

```json
{
  "schemaVersion": 1,
  "name": "Example ingredient",
  "basis": { "quantity": 1, "unit": "serving", "servingGrams": 80 },
  "nutrients": {
    "kcal": { "amount": 99, "unit": "kcal" },
    "protein": { "amount": 9, "unit": "g" },
    "carbs": { "amount": 0, "unit": "g" },
    "fat": { "amount": 2.5, "unit": "g" },
    "iron": { "percentDV": 10, "standard": "us-current", "form": "unknown" }
  }
}
```

- Basis unit: `serving`, `g` or `ml`; quantity must be positive. `servingGrams`
  and `servingMl` describe one serving only when known. Never infer density.
- Absolute amounts: `kcal` for calories; `g`, `mg`, `ug`/`mcg`/`µg` for mass.
  Vitamin A/E/folate use the same RAE/alpha-tocopherol/DFE meanings as manual entry.
- Vitamin/mineral percentages: use `percentDV` instead of amount/unit, with explicit
  `us-current` or `us-legacy` reference. Legacy forms follow the
  [existing conversion policy](label-daily-values.md). Unknown forms retain the
  original percentage without inventing an amount.
- Missing/null nutrients remain unknown. Required calories/macros must be entered
  explicitly before saving. Values must be numeric, finite and nonnegative.
- Unknown fields, unsupported versions/units, trailing prose and multiple objects
  are rejected. A single Markdown JSON code fence is accepted. Input is limited
  to 100,000 characters.

The pasted response is not archived. Only reviewed food values and percentage
provenance are saved through the usual food/snapshot/backup paths. No database or
backup schema change is needed.
