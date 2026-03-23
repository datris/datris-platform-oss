# Schema Definition & Auto-Generation

Schemas define the structure of data flowing through the pipeline. Every pipeline requires a source schema that describes the incoming fields and their types. Optionally, a destination schema can override types or rename fields when writing to a target.

## Defining Schemas

Schemas are declared in the `source.schemaProperties.fields` array of a pipeline configuration. Each field entry specifies a name and a data type.

```json
{
  "source": {
    "schemaProperties": {
      "fields": [
        { "name": "id", "type": "bigint" },
        { "name": "email", "type": "varchar(255)" },
        { "name": "signup_date", "type": "date" },
        { "name": "balance", "type": "decimal(12,2)" },
        { "name": "is_active", "type": "boolean" },
        { "name": "notes", "type": "string" }
      ]
    }
  }
}
```

## Supported Data Types

| Type | Description |
|---|---|
| `boolean` | True/false value |
| `int` | 32-bit signed integer |
| `tinyint` | 8-bit signed integer |
| `smallint` | 16-bit signed integer |
| `bigint` | 64-bit signed integer |
| `float` | 32-bit floating point |
| `double` | 64-bit floating point |
| `decimal(p,s)` | Fixed-precision decimal with `p` total digits and `s` scale digits |
| `string` | Variable-length text, no upper bound |
| `varchar(n)` | Variable-length text with maximum length `n` |
| `char(n)` | Fixed-length text of exactly `n` characters |
| `date` | Calendar date (no time component) |
| `timestamp` | Date and time with microsecond precision |

Refer to [data-types](ingestion/data-types.md) for type mappings to PostgreSQL and Spark.

## Auto-Generating a Schema

If you have a representative CSV file, the pipeline can infer a schema automatically. POST the file to the `/api/v1/pipeline/generate` endpoint:

```bash
curl -X POST "http://localhost:9000/api/v1/pipeline/generate" \
  -F "file=@sample.csv"
```

The response contains the inferred field definitions:

```json
{
  "fields": [
    { "name": "id", "type": "bigint" },
    { "name": "email", "type": "varchar(255)" },
    { "name": "signup_date", "type": "date" },
    { "name": "balance", "type": "decimal(12,2)" },
    { "name": "is_active", "type": "boolean" }
  ]
}
```

The generator examines every value in each column and selects the narrowest type that accommodates all values. Empty columns default to `string`. You can edit the output before saving it to a pipeline configuration.

## Source vs Destination Schemas

A **source schema** describes the data as it arrives (CSV columns, JSON keys, database columns). It is always required.

A **destination schema** describes the data as it should be written to the target system. It is optional. When omitted, the destination inherits the source schema unchanged.

Use a destination schema when you need to:

- Widen a type (e.g., `int` to `bigint`) for the target table.
- Rename a field between ingestion and storage.
- Drop fields that should not reach the destination.

```json
{
  "source": {
    "schemaProperties": {
      "fields": [
        { "name": "user_id", "type": "int" },
        { "name": "full_name", "type": "varchar(100)" }
      ]
    }
  },
  "destination": {
    "schemaProperties": {
      "fields": [
        { "name": "user_id", "type": "bigint" },
        { "name": "full_name", "type": "varchar(200)" }
      ]
    }
  }
}
```

When both schemas are present, the pipeline maps source fields to destination fields by position. Ensure the field count matches or use a transformation step to reconcile differences.
