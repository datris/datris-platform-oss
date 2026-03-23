# Data Ingestion API

## Upload a File

Upload a data file for processing by a configured pipeline.

```
POST /api/v1/pipeline/upload
Content-Type: multipart/form-data
```

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | form-data | Yes | The file to upload |
| `pipeline` | form-data | Yes | Target pipeline name |
| `publishertoken` | form-data | No | Publisher identifier for tracking |

**Behavior:**
- **Compressed files** (`.zip`, `.gz`, `.tar`, `.jar`): Staged to MinIO raw bucket for asynchronous processing
- **Uncompressed files**: Processed immediately in-memory

**Example:**
```bash
curl -X POST http://localhost:8080/api/v1/pipeline/upload \
  -F "file=@/path/to/data.csv" \
  -F "pipeline=sales_data" \
  -F "publishertoken=batch-001"
```

**Response:** `200 OK` with the pipeline token (for uncompressed files):
```
pt-abc12345-6789-...
```

For compressed files, the response is `200 OK` with no body. The file is processed asynchronously when the pipeline detects it in the raw bucket.

---

## Generate Pipeline Schema

Upload a CSV file to automatically infer the schema and generate a partial pipeline configuration.

```
POST /api/v1/pipeline/generate
Content-Type: multipart/form-data
```

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | form-data | Yes | CSV file to analyze |
| `pipeline` | form-data | Yes | Pipeline name |
| `delimiter` | form-data | No | CSV delimiter (default: auto-detect) |
| `header` | form-data | No | Whether file has header row (default: `true`) |

**Example:**
```bash
curl -X POST http://localhost:8080/api/v1/pipeline/generate \
  -F "file=@/path/to/sample.csv" \
  -F "pipeline=my_pipeline"
```

**Response:** `200 OK` with a partial PipelineConfig JSON:

```json
{
  "name": "my_pipeline",
  "source": {
    "fileAttributes": {
      "csvAttributes": {
        "delimiter": ",",
        "header": true
      }
    },
    "schemaProperties": {
      "fields": [
        {"name": "id", "type": "int"},
        {"name": "name", "type": "string"},
        {"name": "amount", "type": "double"},
        {"name": "created_at", "type": "string"}
      ]
    }
  },
  "destination": {
    "database": {
      "dbName": "DATABASE_NAME",
      "schema": "SCHEMA_NAME",
      "table": "TABLE_NAME",
      "redshift": {
        "_comment": "remove redshift section if not used",
        "keyFields": ["KEY_FIELD1", "KEY_FIELD2"]
      }
    }
  }
}
```

**Inferred types:** `int`, `bigint`, `float`, `double`, `char`, `string`

**Note:** This endpoint only analyzes CSV files. JSON and XML files return null for the generated config. Edit the generated JSON to add your destination configuration before registering it with `POST /pipeline`.
