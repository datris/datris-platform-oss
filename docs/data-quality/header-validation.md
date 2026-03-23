# CSV Header Validation

Header validation checks that the first row of a CSV file matches the expected field names defined in the pipeline schema. When enabled, the pipeline compares each header value against the corresponding schema field name, in order. If any mismatch is found, the file is rejected before processing begins.

## Requirements

- The source file must be CSV format.
- The pipeline must be configured with `header: true` (i.e., the first row contains column names).

## Configuration

Enable header validation by setting `validateFileHeader` to `true` in the `dataQuality` block of the pipeline configuration.

```json
{
  "pipelineName": "customer_orders",
  "sourceFileFormat": "CSV",
  "header": true,
  "schema": {
    "fields": [
      { "name": "order_id", "type": "string" },
      { "name": "customer_id", "type": "string" },
      { "name": "amount", "type": "decimal" },
      { "name": "order_date", "type": "date" }
    ]
  },
  "dataQuality": {
    "validateFileHeader": true
  }
}
```

## Behavior

1. The pipeline reads the first row of the CSV file.
2. Each header value is compared to the `name` of the corresponding field in the schema, in positional order.
3. If all header values match, processing continues normally.
4. If any header value does not match the expected field name, the file fails validation and is not processed.

## When to Use

Use header validation to catch files that have been delivered with columns in the wrong order, with unexpected column names, or with missing or extra columns. This is especially useful when upstream systems change their export format without notice.
