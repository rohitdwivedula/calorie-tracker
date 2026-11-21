# Label JSON handoff

From **Food nutrition**, tap **JSON → Paste JSON → Fill form** to use a response
already on your clipboard. If you need instructions for your AI chat, tap
**Copy prompt**. The photo can be earlier in the conversation.
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
- Missing/null nutrients remain unknown. Calories are required to save a food. Values must be numeric, finite and nonnegative.
- Unknown fields, unsupported versions/units, trailing prose and multiple objects
  are rejected. A single Markdown JSON code fence is accepted. Input is limited
  to 100,000 characters.

The pasted response is not archived. Only reviewed food values and percentage
provenance are saved through the usual food/snapshot/backup paths. No database or
backup schema change is needed.


### Checking image and conversation context

To check the copied prompt in an AI chat, start with a synthetic order image
that names a dish but has no nutrient amounts. Have the chat report a reference
for one plate in text (for example, 420 kcal and 275 mg sodium). Then paste the
app prompt. Check that the JSON names the dish and includes both amounts for
one plate, even when the image shows an order of two. Repeat in a fresh chat
with just the image: the dish name should survive and unsupported amounts should
stay unknown. A response with neither a name nor nutrient values is rejected
before it can clear the editor.
