# REST Endpoint Transformation (Deprecated)

REST endpoint transformations (`transformation.rowFunctions` with `function: "restEndpoint"`) are deprecated in v1.4.0. Use [AI Transformation (CodeGen)](ai-transformation.md) instead, or use a [preprocessor](../preprocessor.md) to call external services before transformation.

REST endpoint transformations still work in the pipeline server for backward compatibility with existing pipeline configurations, but are no longer exposed in the UI or CLI.
