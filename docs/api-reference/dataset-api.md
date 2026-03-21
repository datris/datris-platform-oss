# Dataset Configuration API

Manage dataset configurations through CRUD operations.

## Get a Dataset

```
GET /api/v1/dataset?dataset={name}
```

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dataset` | query | Yes | Dataset name |

**Example:**
```bash
curl "http://localhost:8080/api/v1/dataset?dataset=sales_data"
```

**Response:** `200 OK` with the full DatasetConfig JSON.

---

## List All Datasets

```
GET /api/v1/datasets
```

**Example:**
```bash
curl http://localhost:8080/api/v1/datasets
```

**Response:** `200 OK` with an array of all DatasetConfig objects.

---

## Create or Update a Dataset

```
POST /api/v1/dataset
Content-Type: application/json
```

**Body:** Full DatasetConfig JSON (see [Dataset Configuration](../dataset-configuration.md)).

**Example:**
```bash
curl -X POST http://localhost:8080/api/v1/dataset \
  -H "Content-Type: application/json" \
  -d '{
    "name": "sales_data",
    "source": {
      "schemaProperties": {
        "fields": [
          {"name": "id", "type": "int"},
          {"name": "amount", "type": "double"}
        ]
      },
      "fileAttributes": {
        "csvAttributes": {"delimiter": ",", "header": true, "encoding": "UTF-8"}
      }
    },
    "destination": {
      "database": {
        "dbName": "mydb",
        "schema": "public",
        "table": "sales",
        "usePostgres": true
      }
    }
  }'
```

**Response:** `200 OK`

**Validation:** The configuration is validated before saving. Invalid configurations return `500` with error details. See [Dataset Configuration](../dataset-configuration.md) for all constraints.

---

## Delete a Dataset

```
DELETE /api/v1/dataset?dataset={name}
```

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dataset` | query | Yes | Dataset name |

**Example:**
```bash
curl -X DELETE "http://localhost:8080/api/v1/dataset?dataset=sales_data"
```

**Response:** `200 OK`

---

## AI Schema Generation

To generate a dataset configuration automatically from an uploaded file, see the [AI Schema Generation API](schema-generation-api.md).

---

## Authentication

If `useApiKeys` is enabled, all requests must include the `x-api-key` header:

```bash
curl -H "x-api-key: your-api-key" http://localhost:8080/api/v1/datasets
```

API keys are stored in Vault under the secret specified by `secrets.apiKeysSecretName`.
