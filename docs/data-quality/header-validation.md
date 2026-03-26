# CSV Header Validation

Header validation uses an AI model to check that the CSV file header matches the expected field names defined in the pipeline schema. The validation is fuzzy — it allows case differences, underscores vs spaces, abbreviations, and minor naming variations. Column order does not matter. All schema columns must be present; extra columns in the file are OK.

## Requirements

- The source file must be CSV format.
- The pipeline must be configured with `header: true` (i.e., the first row contains column names).
- `ai.enabled: true` must be set in `application.yaml`.

## Configuration

Enable header validation by setting `validateFileHeader` to `true` in the `dataQuality` block:

```json
{
  "dataQuality": {
    "validateFileHeader": true
  }
}
```

## Behavior

1. The pipeline reads the first row of the CSV file (the header) and the schema field names.
2. Both are sent to the AI model, which evaluates whether they match.
3. The AI allows fuzzy matching: `"First Name"` matches `"first_name"`, `"qty"` matches `"quantity"`, etc.
4. Column order does not matter — the pipeline uses the header to map columns to schema fields by name.
5. All schema columns must be present in the header. Missing columns fail validation.
6. Extra columns in the header beyond the schema are accepted.
7. If validation fails, a clear error message explains which columns are missing or unmatched.

## When to Use

Use header validation to catch files with missing columns or unexpected column names. Because matching is fuzzy and order-independent, minor formatting differences won't cause false rejections.
