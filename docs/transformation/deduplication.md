# Row Deduplication

Deduplication removes exact duplicate rows from the dataset. Two rows are considered duplicates when every column value is identical. Only one copy of each unique row is retained.

## Configuration

Enable deduplication by setting `deduplicate` to `true` in the `transformation` block of the dataset configuration.

```json
{
  "datasetName": "event_log",
  "transformation": {
    "deduplicate": true
  }
}
```

## Execution Order

Deduplication runs **before** row functions. This means:

1. Duplicate rows are removed first.
2. Row functions (JavaScript transformations) are then applied to the deduplicated set.

This ordering avoids running transformation logic on rows that would have been discarded anyway.

## Behavior

- The pipeline compares all column values across rows.
- If two or more rows are identical across every column, only one instance is kept.
- The number of removed duplicate rows is logged for auditing purposes.

## When to Use

Enable deduplication when upstream systems may deliver the same record more than once, such as retry-based delivery mechanisms or systems that do not guarantee exactly-once semantics.
