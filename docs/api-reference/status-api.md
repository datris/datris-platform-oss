# Dataset Status API

Query job processing status by pipeline token or dataset name.

## Get Status by Pipeline Token

```
GET /api/v1/dataset/status?pipelinetoken={token}
```

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `pipelinetoken` | query | Yes* | Pipeline token from upload response |

**Example:**
```bash
curl "http://localhost:8080/api/v1/dataset/status?pipelinetoken=pt-abc12345-..."
```

**Response:** `200 OK` - an array of status entries, one per processing stage:
```json
[
  {
    "id": 1,
    "dateTime": "2026-03-15T10:00:00Z",
    "dataset": "sales_data",
    "processName": "StreamNotifier",
    "publisherToken": null,
    "pipelineToken": "pt-abc12345-...",
    "filename": "sales_data",
    "state": "begin",
    "code": "begin",
    "description": "Process started",
    "epoch": 1710500400000
  },
  {
    "id": 2,
    "dateTime": "2026-03-15T10:00:03Z",
    "dataset": "sales_data",
    "processName": "PostgresLoader",
    "publisherToken": null,
    "pipelineToken": "pt-abc12345-...",
    "filename": "sales_data",
    "state": "end",
    "code": "end",
    "description": "Process completed",
    "epoch": 1710500403000
  }
]
```

---

## Get Status by Dataset Name

```
GET /api/v1/dataset/status?datasetname={name}&page={page}
```

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `datasetname` | query | Yes* | Dataset name |
| `page` | query | No | Page number (default: 1) |

**Example:**
```bash
curl "http://localhost:8080/api/v1/dataset/status?datasetname=sales_data&page=1"
```

**Response:** `200 OK` - an array of job summaries:
```json
[
  {
    "createdAtTimestamp": "2026-03-15T10:00:00Z",
    "createdAt": 1710500400000,
    "updatedAt": 1710500403000,
    "dataset": "sales_data",
    "pipelineToken": "pt-abc12345-...",
    "process": "PostgresLoader",
    "startTime": "2026-03-15T10:00:00Z",
    "endTime": "2026-03-15T10:00:03Z",
    "totalTime": "3s",
    "status": "end"
  }
]
```

---

*Use either `pipelinetoken` or `datasetname`, not both.

## Status Fields

Each status entry (`DatasetStatus`) contains:

| Field | Description |
|-------|-------------|
| `id` | Entry index (internal) |
| `dateTime` | Human-readable timestamp |
| `dataset` | Dataset name |
| `processName` | Processing stage name (see below) |
| `publisherToken` | Publisher identifier (if provided on upload) |
| `pipelineToken` | Pipeline job token |
| `filename` | Source filename |
| `state` | Stage state: `begin`, `processing`, `end`, or `error` |
| `code` | Same as `state` |
| `description` | Detail message |
| `epoch` | Unix epoch milliseconds |

## State Values

| State | Description |
|-------|-------------|
| `begin` | Processing stage started |
| `processing` | In progress with detail message |
| `end` | Processing stage completed |
| `error` | Processing stage failed |

## Process Names

| Process | Description |
|---------|-------------|
| `FileNotifier` | File intake from MinIO bucket |
| `StreamNotifier` | Direct upload intake |
| `DataQuality` | Data quality validation |
| `Transformation` | Data transformation |
| `JobRunner` | Destination orchestration |
| `PostgresLoader` | PostgreSQL loading |
| `MongoDBLoader` | MongoDB loading |
| `SparkObjectStoreLoader` | Object store writing |
| `KafkaLoader` | Kafka producing |
| `ActiveMQLoader` | ActiveMQ queue writing |
| `RestEndpointRunner` | REST endpoint posting |
