# JavaScript Row Transformations

Row functions apply custom JavaScript logic to transform each row in the dataset. Use row functions to add, modify, or remove columns, or to drop rows entirely.

## Configuration

Define row functions in the `rowFunctions` array within the `transformation` block. Each entry specifies the filename of a JavaScript file stored in the MinIO config bucket at `{environment}-config/javascript/{filename}`.

```json
"transformation": {
  "rowFunctions": [
    {
      "function": "javascript",
      "parameters": ["normalize_currency.js"]
    }
  ]
}
```

### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `function` | string | Yes | Must be `"javascript"` |
| `parameters` | array | Yes | First element is the script filename or full MinIO path |

If only a filename is provided, it is resolved from the MinIO config bucket at `{environment}-config/javascript/{filename}`. You can also provide a full `s3://` path (MinIO uses S3-compatible URLs).

## Script Interface

### Input

Each column value from the current row is available as a script variable, using the column name as the variable name. For example, a row with columns `price` and `quantity` makes both `price` and `quantity` available in the script scope.

An additional variable `_pipelinetimestamp` is provided, containing the pipeline execution timestamp.

### Output

The script must return a `HashMap` (key-value map) of column names to values. This map becomes the transformed row.

- **Modify a column:** Return the column name with a new value.
- **Add a column:** Include a new key in the returned map.
- **Remove a column:** Omit the key from the returned map.
- **Drop the row:** Return `null`. The row is excluded from the output and logged at info level.

## Example Script

A JavaScript file stored in MinIO at `{environment}-config/javascript/normalize_currency.js`:

```javascript
// Convert price from cents to dollars and add a processed timestamp.
// Drop rows where price is missing or negative.

var result = new java.util.HashMap();

if (price == null || parseInt(price) < 0) {
    // Returning null drops this row from the dataset.
    result = null;
} else {
    result.put("order_id", order_id);
    result.put("customer_id", customer_id);
    result.put("price_dollars", (parseInt(price) / 100).toFixed(2));
    result.put("currency", "USD");
    result.put("processed_at", _pipelinetimestamp);
}

result;
```

## Behavior

- Row functions run **after** deduplication (if enabled).
- Each row function receives the output of the previous function in the chain, so transformations compose in sequence.
- If a script returns `null`, the row is dropped and the event is logged at info level.
- If a script throws an exception, the pipeline stops processing with an error.

## When to Use

Use row functions for transformations that cannot be expressed through simple configuration, such as conditional logic, value lookups, format conversions, or computed columns.
