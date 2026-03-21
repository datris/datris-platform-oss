# Whitespace Trimming

Column trimming removes leading and trailing whitespace from all column values in every row. This normalizes data that may contain inconsistent padding from upstream systems.

## Configuration

Enable whitespace trimming by setting `trimColumnWhitespace` to `true` in the `transformation` block of the dataset configuration.

```json
{
  "datasetName": "contact_list",
  "transformation": {
    "trimColumnWhitespace": true
  }
}
```

## Behavior

- Every string value in every column has leading and trailing whitespace stripped.
- This applies to all columns uniformly; there is no option to target specific columns.
- Interior whitespace (spaces within a value) is not affected.

For example, a value of `"  John Doe  "` becomes `"John Doe"`.

## When to Use

Enable column trimming when source data may include unintended padding, such as fixed-width exports, manually edited files, or systems that pad string fields to a fixed length.
