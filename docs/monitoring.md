# Monitoring

## Pipeline Tokens

Every pipeline processing job is assigned a unique **pipeline token** (e.g., `pt-abc12345-6789-...`). This token is used to track the job through its lifecycle.

Pipeline tokens are returned:
- In the response body of `POST /api/v1/pipeline/upload` (for uncompressed files)
- In the job status when querying by pipeline name

## Job Status

### Query by Pipeline Token

```bash
curl "http://localhost:8080/api/v1/pipeline/status?pipelinetoken=pt-abc12345-..."
```

Returns an array of status entries for the job, one per processing stage:

```json
[
  {
    "id": 1,
    "dateTime": "2026-03-15T10:00:00Z",
    "pipeline": "sales_data",
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
    "dateTime": "2026-03-15T10:00:01Z",
    "pipeline": "sales_data",
    "processName": "DataQuality",
    "publisherToken": null,
    "pipelineToken": "pt-abc12345-...",
    "filename": "sales_data",
    "state": "processing",
    "code": "processing",
    "description": "Running CodeGen data quality rule",
    "epoch": 1710500401000
  },
  {
    "id": 3,
    "dateTime": "2026-03-15T10:00:03Z",
    "pipeline": "sales_data",
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

### Query by Pipeline Name

```bash
curl "http://localhost:8080/api/v1/pipeline/status?pipelinename=sales_data&page=1"
```

Returns an array of job summaries for the pipeline (20 per page):

```json
[
  {
    "createdAtTimestamp": "2026-03-15T10:00:00Z",
    "createdAt": 1710500400000,
    "updatedAt": 1710500403000,
    "pipeline": "sales_data",
    "pipelineToken": "pt-abc12345-...",
    "process": "PostgresLoader",
    "startTime": "2026-03-15T10:00:00Z",
    "endTime": "2026-03-15T10:00:03Z",
    "totalTime": "3s",
    "status": "end"
  }
]
```

## Job Lifecycle

Jobs progress through three states:

| State | Description |
|-------|-------------|
| `INITIALIZED` | Job created, queued for processing |
| `PROCESSING` | Running in a dedicated thread |
| `COMPLETED` | Finished (check status messages for success or error) |

## Processing Stages

Each job logs status messages as it progresses through stages:

1. **FileNotifier** / **StreamNotifier** - Initial file or stream intake
2. **DataQuality** - Validation (if configured)
3. **Transformation** - Data transformation (if configured)
4. **JobRunner** - Orchestration of destination loaders
5. **[LoaderName]** - Each destination loader (e.g., `PostgresLoader`, `SparkObjectStoreLoader`)

Each stage logs `begin`, `processing` (with details), and `end` messages.

## Status Storage

Job statuses are stored in MongoDB in the `{environment}-pipeline-status` collection. Each entry contains the pipeline token, process name, status, message, and timestamp.

## Concurrent Job Handling

- All destination loaders for a single job execute in parallel on a 20-thread pool
- Jobs targeting the same database table are serialized (only one runs at a time)
- Multiple jobs for different pipelines run concurrently
