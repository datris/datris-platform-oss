# Data Ingestion API

## Upload a File

Upload a data file for processing by a configured dataset.

```
POST /api/v1/dataset/upload
Content-Type: multipart/form-data
```

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | form-data | Yes | The file to upload |
| `dataset` | form-data | Yes | Target dataset name |
| `publishertoken` | form-data | No | Publisher identifier for tracking |

**Behavior:**
- **Compressed files** (`.zip`, `.gz`, `.tar`, `.jar`): Staged to MinIO raw bucket for asynchronous processing
- **Uncompressed files**: Processed immediately in-memory

**Example:**
```bash
curl -X POST http://localhost:8080/api/v1/dataset/upload \
  -F "file=@/path/to/data.csv" \
  -F "dataset=sales_data" \
  -F "publishertoken=batch-001"
```

**Response:** `200 OK` with the pipeline token (for uncompressed files):
```
pt-abc12345-6789-...
```

For compressed files, the response is `200 OK` with no body. The file is processed asynchronously when the pipeline detects it in the raw bucket.

---

## Generate Dataset Schema

Upload a CSV file to automatically infer the schema and generate a partial dataset configuration.

```
POST /api/v1/dataset/generate
Content-Type: multipart/form-data
```

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | form-data | Yes | CSV file to analyze |
| `dataset` | form-data | Yes | Dataset name |
| `delimiter` | form-data | No | CSV delimiter (default: auto-detect) |
| `header` | form-data | No | Whether file has header row (default: `true`) |

**Example:**
```bash
curl -X POST http://localhost:8080/api/v1/dataset/generate \
  -F "file=@/path/to/sample.csv" \
  -F "dataset=my_dataset"
```

**Response:** `200 OK` with a partial DatasetConfig JSON:

```json
{
  "name": "my_dataset",
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

**Note:** This endpoint only analyzes CSV files. JSON and XML files return null for the generated config. Edit the generated JSON to add your destination configuration before registering it with `POST /dataset`.
