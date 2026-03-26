# Transformation REST

**Location:** `examples/transformation-rest/`

A Flask REST API for external data transformation. The pipeline calls this endpoint as part of its transformation row functions, enabling custom transformation logic in any language. The sample implementation lowercases all string values.

## Endpoints

- `POST /transform/rest/row` — Transform a single row (returns transformed row or `null` to remove)
- `POST /transform/rest/batch` — Transform a batch of rows (returns transformed rows, `null` entries removed)

## Setup

```bash
cd examples/transformation-rest
pip install flask
```

## Run

```bash
python app.py    # runs on port 5600
```

## Usage

Configure a pipeline's `transformation.rowFunctions` with `function: "restEndpoint"` pointing to `http://host.docker.internal:5600/transform/rest/row` (row mode) or `/transform/rest/batch` (batch mode).

See [REST Endpoint Transformation](../transformation/rest-endpoint.md) for the full protocol reference.
