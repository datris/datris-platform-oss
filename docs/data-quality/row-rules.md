# Row Rules (Deprecated)

Row rules (`dataQuality.rowRules`) were removed in v1.4.0. All data quality validation is now handled by the [CodeGen AI Rule](column-rules.md), which generates a Python validation script from a plain-English instruction and runs it locally.

For REST endpoint validation, use a [preprocessor](../preprocessor.md) to call an external service before data quality checks run.
