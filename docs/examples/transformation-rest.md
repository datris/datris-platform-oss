# Transformation REST (Legacy Example)

**Location:** `examples/transformation-rest/`

A Flask REST API for external data transformation. This example demonstrates calling an external endpoint for transformation.

> **Note:** REST endpoint transformations (`transformation.rowFunctions` with `function: "restEndpoint"`) are deprecated in v1.4.0. For transformations, use [AI Transformation (CodeGen)](../transformation/ai-transformation.md) which generates a Python script from a plain-English instruction. For external service calls, use a [preprocessor](../preprocessor.md).

## Endpoints

- `POST /transform/rest/row` — Transform a single row
- `POST /transform/rest/batch` — Transform a batch of rows

## Setup

```bash
cd examples/transformation-rest
pip install flask
```

## Run

```bash
python app.py    # runs on port 5600
```
