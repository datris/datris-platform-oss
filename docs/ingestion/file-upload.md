# API File Upload

Upload data files directly to the pipeline through the REST API. The pipeline accepts CSV, JSON, and XML files, handles decompression automatically, and returns a tracking token.

## Endpoint

```
POST /api/v1/dataset/upload
```

## Request

The request uses `multipart/form-data` encoding with the following parts:

| Part | Required | Description |
|---|---|---|
| `file` | Yes | The data file to upload |
| `dataset` | Yes | Name of the target dataset configuration |
| `publishertoken` | No | Opaque token identifying the data publisher; used for lineage tracking |

### Example

```bash
curl -X POST "http://localhost:9000/api/v1/dataset/upload" \
  -F "file=@transactions_2025.csv" \
  -F "dataset=transactions" \
  -F "publishertoken=finance-team-a"
```

## Response

A successful upload returns HTTP 200 with a JSON body containing a `pipelineToken`:

```json
{
  "pipelineToken": "d4f8e1a2-7b3c-4e9f-a5d6-1c2b3e4f5a6b",
  "status": "ACCEPTED"
}
```

Use the `pipelineToken` to query processing status downstream.

## Compressed Files

When the uploaded file has a compressed extension, the pipeline stages it to the MinIO raw bucket before processing:

| Extension | Handling |
|---|---|
| `.zip` | Staged to MinIO raw bucket, then extracted |
| `.gz` | Staged to MinIO raw bucket, then decompressed |
| `.tar` | Staged to MinIO raw bucket, then extracted |
| `.jar` | Staged to MinIO raw bucket, then extracted |

Compressed archives may contain multiple data files. Each file inside the archive is processed as a separate unit against the same dataset configuration.

## Uncompressed Files

Files without a recognized compressed extension (e.g., plain `.csv`, `.json`, `.xml`) are processed in-memory directly. They are not staged to MinIO.

## Processing Flow

1. The client sends the multipart request.
2. The pipeline inspects the file extension.
3. **Compressed path**: the file is written to the MinIO raw bucket under `raw/{dataset}/{filename}`. A background job picks it up, decompresses it, and feeds each inner file into the ingestion pipeline.
4. **Uncompressed path**: the file contents are read into memory and passed directly to the ingestion pipeline.
5. The pipeline returns the `pipelineToken` immediately. Processing continues asynchronously.

## Error Responses

| HTTP Status | Cause |
|---|---|
| 400 | Missing `dataset` parameter or empty file |
| 404 | Dataset configuration not found |
| 413 | File exceeds the configured upload size limit |
| 500 | Internal error during staging or processing |

## Size Limits

The maximum upload size is controlled by the `pipeline.upload.maxFileSize` property in `application.yaml`. The default is 500 MB. Adjust this value if your files regularly exceed the limit:

```yaml
pipeline:
  upload:
    maxFileSize: 1073741824  # 1 GB
```
