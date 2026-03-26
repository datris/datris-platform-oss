# Data Quality REST

**Location:** `examples/data-quality-rest/`

A Flask REST API for external data quality validation. The pipeline can call this endpoint as part of its data quality rules, enabling custom validation logic in any language outside the pipeline.

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

## Usage

Configure a pipeline's `dataQuality.rowRules` with `function: "restEndpoint"` pointing to `http://host.docker.internal:5500/dataquality/rest/row` (row mode) or `/dataquality/rest/batch` (batch mode).

See [Row Rules](../data-quality/row-rules.md) for the full DQ REST endpoint protocol.
