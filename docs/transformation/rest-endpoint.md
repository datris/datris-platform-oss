# REST Endpoint Transformation

REST endpoint transformations call an external HTTP endpoint to transform row data during pipeline processing. The endpoint receives row data as JSON and returns the transformed data. This enables custom transformation logic in any language or framework, running outside the pipeline.

## Configuration

Define a REST endpoint transformation in the `rowFunctions` array within the `transformation` block:

```json
"transformation": {
  "rowFunctions": [
    {
      "function": "restEndpoint",
      "parameters": ["http://my-service:5600/transform/rest/row", "row", "30000", "", ""]
    }
  ]
}
```

### Parameters

| Index | Name | Required | Default | Description |
|-------|------|----------|---------|-------------|
| 0 | Endpoint URL | Yes | — | The HTTP endpoint to call |
| 1 | Mode | No | `"row"` | `"row"` (per-row) or `"batch"` (all rows at once) |
| 2 | Timeout (ms) | No | `30000` | HTTP request timeout in milliseconds |
| 3 | Bearer Token | No | `""` | Bearer token for authentication (sent as `Authorization: Bearer <token>`) |
| 4 | API Key | No | `""` | API key for authentication (sent as `x-api-key` header) |

## Row Mode

In row mode, the pipeline calls the endpoint once per row. The endpoint receives a single row and returns the transformed row.

### Request

```json
{
  "pipelineName": "stock_prices",
  "pipelineToken": "abc-123",
  "row": {
    "symbol": "AAPL",
    "price": "150.25",
    "date": "2026-01-15"
  }
}
```

### Response — Transformed Row

Return the modified row data. Any field can be changed, added, or removed.

```json
{
  "status": "success",
  "row": {
    "symbol": "AAPL",
    "price": "150.25",
    "date": "01/15/2026",
    "currency": "USD"
  }
}
```

### Response — Remove Row

Return `null` for the row to drop it from the pipeline output.

```json
{
  "status": "success",
  "row": null
}
```

### Response — Error

Return a failure status to stop the pipeline with an error.

```json
{
  "status": "failure",
  "message": "Invalid data format"
}
```

## Batch Mode

In batch mode, the pipeline calls the endpoint once with all rows. The endpoint receives all the data and returns the transformed rows.

### Request

```json
{
  "pipelineName": "stock_prices",
  "pipelineToken": "abc-123",
  "rows": [
    { "symbol": "AAPL", "price": "150.25", "date": "2026-01-15" },
    { "symbol": "GOOG", "price": "2800.00", "date": "2026-01-15" },
    { "symbol": "MSFT", "price": "310.50", "date": "2026-01-15" }
  ]
}
```

### Response — Transformed Rows

Return the transformed rows array. Use `null` entries to remove individual rows.

```json
{
  "status": "success",
  "rows": [
    { "symbol": "AAPL", "price": "150.25", "date": "01/15/2026" },
    null,
    { "symbol": "MSFT", "price": "310.50", "date": "01/15/2026" }
  ]
}
```

In this example, the second row (GOOG) is removed from the output.

### Response — Error

```json
{
  "status": "failure",
  "message": "Batch processing failed"
}
```

## Networking

The endpoint URL depends on where the Datris server and your transformation service are running:

| Datris Server | Transform Service | Endpoint URL |
|---------------|-------------------|-------------|
| Docker | Host machine (Mac/Linux) | `http://host.docker.internal:5600/...` |
| Local | Local | `http://localhost:5600/...` |
| Docker | Docker (same network) | `http://service-name:5600/...` |

When Datris runs in Docker, it cannot reach `localhost` on the host — use `host.docker.internal` instead.

## Numeric Types

JSON does not distinguish between integers and floats. When the pipeline sends numeric fields like `volume: 3498900`, the JSON serialization may deliver them as `3498900.0` to your endpoint. If your endpoint returns them unchanged as floats, the pipeline will convert whole-number floats back to integers automatically (e.g., `3498900.0` → `"3498900"`).

If you want to handle this explicitly in your endpoint code, cast float values that are whole numbers back to integers before returning:

```python
# Python example
if isinstance(value, float) and value == int(value):
    transformed[key] = int(value)
```

## Behavior

- REST endpoint transformations run **after** deduplication and JavaScript row functions (if configured), and **before** AI transformations.
- Multiple row functions can be chained — each receives the output of the previous function.
- In row mode, returning `null` for a row drops it from the output.
- In batch mode, `null` entries in the returned rows array are dropped.
- If the endpoint returns a non-`"success"` status, the pipeline fails with the error message.
- If the endpoint is unreachable or times out, the pipeline fails with an exception.

## Performance: Row vs Batch

**Row mode** makes one HTTP call per row. For large files this can be slow due to network overhead and may hit the timeout. Use row mode for small files or when per-row isolation is important.

**Batch mode** makes a single HTTP call with all rows. This is significantly faster for large files and is the recommended mode for production use. The tradeoff is that your endpoint must handle all the data in memory.

## When to Use

Use REST endpoint transformations when:

- **Custom logic in any language** — your transformation is written in Python, Go, Node.js, etc.
- **External service integration** — you need to call a third-party API to enrich or transform data (e.g., geocoding, currency conversion, entity resolution).
- **Row filtering** — you want to remove rows based on complex business rules that are easier to express in code than in pipeline configuration.
- **Batch processing** — your transformation benefits from seeing all rows at once (e.g., normalization, outlier detection).

## Sample Helper Application

A sample Flask application is provided at `examples/transformation-rest/`:

```bash
cd examples/transformation-rest
pip install -r requirements.txt
python app.py    # runs on port 5600
```

**Endpoints:**
- `POST /transform/rest/row` — Row mode (one row per call)
- `POST /transform/rest/batch` — Batch mode (all rows in one call)

The sample lowercases all string values in each row. See `examples/transformation-rest/app.py` for the full implementation with commented examples for row removal and computed fields.

**Configure the pipeline to use it (Docker):**
```json
"transformation": {
  "rowFunctions": [
    {
      "function": "restEndpoint",
      "parameters": ["http://host.docker.internal:5600/transform/rest/row", "row", "30000"]
    }
  ]
}
```

**Configure the pipeline to use it (local):**
```json
"transformation": {
  "rowFunctions": [
    {
      "function": "restEndpoint",
      "parameters": ["http://localhost:5600/transform/rest/row", "row", "30000"]
    }
  ]
}
```

## UI Configuration

In the pipeline creation wizard (Step 6 — Transformation), enable "REST Endpoint Transformation" and fill in:

- **Endpoint URL** — the full URL to your transformation service
- **Mode** — Row or Batch
- **Timeout (ms)** — default 30000 (increase for slow endpoints or large batches)
- **Bearer Token** — optional, for authenticated endpoints
- **API Key** — optional, sent as `x-api-key` header
