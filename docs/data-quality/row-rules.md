# Row Rules

Row rules validate entire rows of data using custom logic. Three function types are supported: `"ai"`, `"restEndpoint"`, and `"javascript"`. Rules are defined in the `dataQuality.rowRules` array.

## Configuration

```json
"dataQuality": {
  "rowRules": [
    {
      "function": "ai",
      "parameters": ["the open price must be less than the high price and greater than the low price", "100"],
      "onFailureIsError": true
    },
    {
      "function": "restEndpoint",
      "parameters": ["http://my-service:8080/validate", "batch", "30000"],
      "onFailureIsError": false
    },
    {
      "function": "javascript",
      "parameters": ["validate_data.js"],
      "onFailureIsError": true
    }
  ]
}
```

### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `function` | string | Yes | Rule type: `"ai"`, `"restEndpoint"`, or `"javascript"` |
| `parameters` | array | Yes | Parameters for the rule (see below) |
| `onFailureIsError` | boolean | Yes | `true` = abort on failure, `false` = log as warning |

---

## AI Row Rules

Use `"function": "ai"` to validate cross-column business logic in plain English. This is ideal for rules that are difficult to express in code but easy to describe, such as:

- "the open price must be less than the high price and greater than the low price"
- "the end date must be after the start date"
- "quantity must be positive if status is 'active'"

### Parameters

| Index | Required | Description |
|-------|----------|-------------|
| `parameters[0]` | Yes | Natural language description of the validation rule |
| `parameters[1]` | No | Batch size — number of rows per AI API call (default: `100`) |

### Example

```json
{
  "function": "ai",
  "parameters": [
    "the open price must be less than the high price and greater than the low price",
    "100"
  ],
  "onFailureIsError": true
}
```

### How it works

Rows are converted to key-value maps and sent to the AI model in batches. The AI evaluates each row against the instruction and returns a pass/fail result with a reason for failures. Failures are reported with the row index and the AI's explanation.

Both **CSV/delimited** and **JSON** source formats are supported. For JSON sources the array is parsed directly from the raw data, so no schema-defined rows are required.

### Requirements

- `ai.enabled: true` must be set in `application.yaml`
- The Vault secret for the AI provider must be configured (see [AI Schema Generation](../api-reference/schema-generation-api.md))

### Performance and batch size

Each AI API call has a fixed network round-trip cost regardless of payload size. Sending more rows per call means fewer total calls, which reduces overall validation time for large pipelines. However, rows are full JSON objects — a wide table with many columns produces a much larger payload per row than a column value, so the safe default is lower than for column rules.

The default `batchSize` of `100` is conservative and safe across all supported providers. For narrow tables (few columns, short values) you can increase this significantly. For wide tables with many large string fields, reduce it to stay within token limits. As a rough guide: multiply your average row size in characters by the batch size — if the result exceeds ~300K characters, reduce the batch size.

---

## REST Endpoint Row Rules

Sends data to an external HTTP service for validation. Supports two modes: **row** (per-record) and **batch** (all records at once).

### Parameters

| Index | Required | Description |
|-------|----------|-------------|
| `parameters[0]` | Yes | URL of the REST endpoint |
| `parameters[1]` | No | Mode: `"row"` (default) or `"batch"` |
| `parameters[2]` | No | Timeout in milliseconds (default: `30000`) |

### Example Configuration

```json
{
  "function": "restEndpoint",
  "parameters": ["http://my-service:8080/validate", "batch", "60000"],
  "onFailureIsError": false
}
```

### Row Mode

In `"row"` mode, the endpoint is called once **per row** with this payload:

```json
{
  "pipelineName": "stock_price",
  "pipelineToken": "pt-abc12345-...",
  "row": {
    "symbol": "AAPL",
    "price": "150.25",
    "date": "2026-03-15"
  }
}
```

**Expected response (success):**
```json
{
  "status": "success"
}
```

**Expected response (failure):**
```json
{
  "status": "failure",
  "message": "Price exceeds maximum threshold"
}
```

### Batch Mode

In `"batch"` mode, the endpoint is called once with **all rows**:

```json
{
  "pipelineName": "stock_price",
  "pipelineToken": "pt-abc12345-...",
  "rows": [
    {"symbol": "AAPL", "price": "150.25", "date": "2026-03-15"},
    {"symbol": "GOOG", "price": "2800.00", "date": "2026-03-15"}
  ],
  "rawData": "..."
}
```

The `rawData` field is included when the source data is JSON or XML.

**Expected response (success, no failures):**
```json
{
  "status": "success"
}
```

**Expected response (success with individual row failures):**
```json
{
  "status": "success",
  "failures": [
    {"row": 1, "description": "Price exceeds maximum threshold"}
  ]
}
```

Row numbers in the `failures` array are zero-indexed.

If `status` is not `"success"`, the entire batch is treated as a failure and processing is aborted.

---

## JavaScript Row Rules

Executes a JavaScript script against each row individually.

### Parameters

| Index | Description |
|-------|-------------|
| `parameters[0]` | Filename or full path to the JavaScript file in MinIO |

If only a filename is provided, it is resolved from the MinIO config bucket at `{environment}-config/javascript/{filename}`. You can also provide a full `s3://` path (MinIO uses S3-compatible URLs).

### Script Interface

**Input:** Each column value is available as a script variable using the column name (lowercase).

**Output:** Return `null` if the row is valid. Return a string error message if the row fails validation.

### Example Configuration

```json
{
  "function": "javascript",
  "parameters": ["validate_stock_price.js"],
  "onFailureIsError": true
}
```

### Example JavaScript

```javascript
// validate_stock_price.js
// Ensure price is positive and symbol is not empty
var result = null;
if (parseFloat(price) <= 0) {
    result = "Price must be positive, got: " + price;
}
if (symbol === null || symbol === "") {
    result = "Symbol cannot be empty";
}
result;
```

---

## Example: REST Endpoint Service

A complete working example of a data quality REST endpoint is provided in [`examples/data-quality-rest/app.py`](../../examples/data-quality-rest/app.py). This is a Python Flask application that implements both `row` and `batch` mode endpoints:

```python
# Row mode endpoint - validates one row at a time
@app.route('/dataquality/rest/row', methods=['POST'])
def dataquality_rest_row():
    payload = request.get_json()
    row = payload.get('row')

    # Your validation logic here
    # Return {'status': 'failure', 'message': '...'} on failure

    return jsonify({'status': 'success'})

# Batch mode endpoint - validates all rows at once
@app.route('/dataquality/rest/batch', methods=['POST'])
def dataquality_rest_batch():
    payload = request.get_json()
    rows = payload.get('rows')
    raw_data = payload.get('rawData')  # Present for JSON/XML sources

    failures = []
    # for i, row in enumerate(rows):
    #     if not valid(row):
    #         failures.append({'row': i, 'description': 'Validation failed'})

    return jsonify({'status': 'success', 'failures': failures})
```

To run the example:

```bash
cd examples/data-quality-rest
python3 -m venv my-env
source my-env/bin/activate
pip install flask
python app.py  # Starts on port 5500
```

Then configure the row rule to point to it:

```json
{
  "function": "restEndpoint",
  "parameters": ["http://localhost:5500/dataquality/rest/batch", "batch", "30000"],
  "onFailureIsError": true
}
```

---

## Error Handling

- When `onFailureIsError` is `true`: any failure aborts processing immediately
- When `onFailureIsError` is `false`: failures are logged as warnings and processing continues
- Processing aborts after more than **100 errors** regardless of `onFailureIsError` setting
- If any errors exist (even fewer than 100), processing is aborted after all rules complete
- If any warnings occurred during processing, the pipeline status is set to `warning` instead of `success`
