# Data Quality REST (Legacy Example)

**Location:** `examples/data-quality-rest/`

A Flask REST API for external data quality validation. This example demonstrates calling an external endpoint for validation.

> **Note:** REST endpoint row rules (`dataQuality.rowRules`) were removed in v1.4.0. For data quality validation, use the [CodeGen AI Rule](../data-quality/column-rules.md) which generates a Python validation script from a plain-English instruction. For external service calls, use a [preprocessor](../preprocessor.md).

## Endpoints

- `POST /dataquality/rest/row` — Validate a single row
- `POST /dataquality/rest/batch` — Validate a batch of rows

## Setup

```bash
cd examples/data-quality-rest
pip install flask
```

## Run

```bash
python app.py    # runs on port 5500
```
