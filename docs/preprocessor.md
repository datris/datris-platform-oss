# Preprocessor

A preprocessor is an optional REST endpoint that is called **before** data quality and transformation. It receives the ingested data and can modify, enrich, filter, or replace it before the pipeline continues processing.

Use a preprocessor when you need to call out to an external system for data enrichment, format conversion, or custom logic that runs before AI-powered data quality and transformation.

## Processing Flow

```
Source Data Ingested
  |
  v
Preprocessor (optional) <--- you are here
  |
  v
Data Quality
  |
  v
Transformation
  |
  v
Destinations
```

## Configuration

Add a `preprocessor` section to the pipeline configuration:

```json
{
  "name": "stock_price",
  "source": { ... },
  "preprocessor": {
    "endpoint": "http://my-service:5500/preprocess/sync",
    "async": false,
    "bearerToken": null,
    "timeoutMs": 300000
  },
  "destination": { ... }
}
```

### Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `endpoint` | string | Required | URL of the preprocessing service |
| `async` | boolean | `false` | Synchronous or asynchronous mode |
| `bearerToken` | string | `null` | Optional `Authorization: Bearer` token |
| `timeoutMs` | int | `300000` | Request timeout in milliseconds |

## Synchronous Mode

In synchronous mode (`async: false`), the pipeline POSTs the data to the endpoint and waits for the response. The response replaces the pipeline data.

### Request Payload

```json
{
  "pipelineToken": "pt-abc12345-...",
  "pipelineName": "stock_price",
  "data": {
    "size": 1024,
    "header": ["symbol", "price", "date"],
    "rows": [
      "AAPL,150.25,2026-03-15",
      "GOOG,2800.00,2026-03-15"
    ],
    "rawData": null
  }
}
```

For JSON/XML sources, `rows` is null and `rawData` contains the raw content.

### Expected Response

Return the (optionally modified) data in the same format:

```json
{
  "data": {
    "size": 1024,
    "header": ["symbol", "price", "date"],
    "rows": [
      "AAPL,150.25,2026-03-15",
      "GOOG,2800.00,2026-03-15"
    ],
    "rawData": null
  }
}
```

You can modify, add, or remove rows. The pipeline continues with whatever data is returned. If you return an `error` field, processing is aborted:

```json
{
  "error": "Data validation failed: missing required field"
}
```

## Asynchronous Mode

In asynchronous mode (`async: true`), the pipeline POSTs the data and then **waits for a callback** rather than using the response directly. This is useful for long-running preprocessing tasks.

### Flow

1. Pipeline POSTs data to the preprocessor endpoint
2. Preprocessor returns immediately (e.g., `200 OK`)
3. Preprocessor processes data in the background
4. Preprocessor POSTs the result back to the pipeline's callback endpoint
5. Pipeline resumes with the returned data

### Callback Endpoint

The preprocessor sends the result to:

```
POST http://{pipeline-host}:8080/api/v1/restendpoint/callback
```

Callback payload:

```json
{
  "pipelineToken": "pt-abc12345-...",
  "pipelineName": "stock_price",
  "data": {
    "size": 1024,
    "header": ["symbol", "price", "date"],
    "rows": ["AAPL,150.25,2026-03-15"],
    "rawData": null
  }
}
```

The `pipelineToken` must match the token from the original request so the pipeline can correlate the callback with the waiting job.

If the callback is not received within `timeoutMs` milliseconds, the pipeline aborts with a timeout error.

## Example: Preprocessor Service

A complete working example is provided in [`examples/preprocessor/app.py`](../examples/preprocessor/app.py). This Python Flask application implements both synchronous and asynchronous preprocessing:

```python
# Synchronous - process and return immediately
@app.route('/preprocess/sync', methods=['POST'])
def preprocess_sync():
    payload = request.get_json()
    data = payload.get('data')

    # Modify data here if needed

    return jsonify({
        'pipelineToken': payload.get('pipelineToken'),
        'pipelineName': payload.get('pipelineName'),
        'data': data
    })

# Asynchronous - return immediately, send callback later
@app.route('/preprocess/async', methods=['POST'])
def preprocess_async():
    payload = request.get_json()
    threading.Thread(
        target=send_callback,
        args=(payload.get('pipelineToken'),
              payload.get('pipelineName'),
              payload.get('data'))
    ).start()
    return jsonify({'status': 'accepted'}), 200

def send_callback(pipeline_token, pipeline_name, data):
    callback_url = 'http://localhost:8080/api/v1/restendpoint/callback'
    requests.post(callback_url, json={
        'pipelineToken': pipeline_token,
        'pipelineName': pipeline_name,
        'data': data
    })
```

To run:

```bash
cd examples/preprocessor
python3 -m venv my-env
source my-env/bin/activate
pip install flask requests
python app.py  # Starts on port 5500
```

Then configure the pipeline:

```json
"preprocessor": {
  "endpoint": "http://localhost:5500/preprocess/sync",
  "async": false,
  "timeoutMs": 300000
}
```
