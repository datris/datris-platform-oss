# Milvus Vector Database Destination

Ingest unstructured documents into [Milvus](https://milvus.io), a high-performance open-source vector database built for scalable similarity search. The pipeline extracts text from the document, chunks it using a configurable strategy, generates vector embeddings, and upserts the chunks with metadata into a Milvus collection.

## Configuration

```json
"source": {
    "fileAttributes": {
        "unstructuredAttributes": {
            "fileExtension": "pdf",
            "preserveFilename": true
        }
    }
},
"destination": {
    "milvus": {
        "collectionName": "financial_documents",
        "chunking": {
            "strategy": "recursive",
            "chunkSize": 500,
            "chunkOverlap": 50
        },
        "metadata": {
            "company": "Apple Inc",
            "documentType": "10-Q",
            "filingDate": "2026-01-30"
        },
        "embeddingSecretName": "oss/embedding",
        "milvusSecretName": "oss/milvus"
    }
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `collectionName` | string | (required) | Milvus collection name. Auto-created if it doesn't exist. |
| `chunking` | object | recursive, 500/50 | Chunking strategy configuration (see below). |
| `metadata` | map | `{}` | Static key-value metadata stored as dynamic fields on every chunk. |
| `embeddingSecretName` | string | (required) | Vault secret name for the embedding API configuration. |
| `milvusSecretName` | string | (required) | Vault secret name for Milvus connection. |

## Supported File Types

| Format | Description |
|--------|-------------|
| PDF (`.pdf`) | Text extracted via Apache PDFBox |
| Word (`.doc`) | Text extracted via Apache POI (legacy format) |
| Word (`.docx`) | Text extracted via Apache POI (modern format) |
| PowerPoint (`.ppt`) | Text extracted via Apache POI (legacy format) |
| PowerPoint (`.pptx`) | Text extracted via Apache POI (modern format) |
| Excel (`.xls`, `.xlsx`) | Cell values extracted via Apache POI |
| HTML (`.html`, `.htm`) | Text extracted via JSoup (tags stripped) |
| RTF (`.rtf`) | Text extracted via javax.swing RTF parser |
| Email (`.msg`) | Subject, from, to, and body extracted via Apache POI |
| Email (`.eml`) | Subject, from, and body extracted via Jakarta Mail |
| EPUB (`.epub`) | XHTML content extracted and parsed via JSoup |
| Plain text (`.txt`, `.md`, `.csv`, `.json`, `.xml`) | Content used directly |

For structured data (CSV, JSON, XML), use database destinations (PostgreSQL, MongoDB) instead.

## Vault Secrets

### Milvus connection

```bash
vault kv put secret/oss/milvus \
  host="host.docker.internal" \
  port="19530" \
  apiKey=""
```

| Field | Description |
|-------|-------------|
| `host` | Milvus server hostname. Use `host.docker.internal` for local Milvus. |
| `port` | gRPC port (default 19530). |
| `apiKey` | API key / token for Milvus Cloud. Empty string for local instances. |

### Embedding API

The embedding API is shared with other vector database destinations. See [Qdrant documentation](qdrant.md#embedding-api) for full details.

```bash
vault kv put secret/oss/embedding \
  endpoint="https://api.openai.com/v1/embeddings" \
  model="text-embedding-3-small" \
  apiKey="sk-..."
```

## Chunking Strategies

Documents are split into chunks before embedding. Each chunk becomes a separate entity in the Milvus collection with the document's metadata plus `chunk_index`, `filename`, and `source_dataset` fields.

```json
"chunking": {
    "strategy": "recursive",
    "chunkSize": 500,
    "chunkOverlap": 50
}
```

| Strategy | Description |
|----------|-------------|
| `none` | No chunking — one embedding per document. Only for very short documents. |
| `fixed` | Split by character count. Fast but may cut mid-sentence. |
| `sentence` | Split on sentence boundaries (`.` `!` `?`). Preserves semantic units. |
| `paragraph` | Split on double newlines. Ideal for structured documents with clear sections. |
| `recursive` | Try `\n\n`, then `\n`, then `.`, then space — best general-purpose default. |

- `chunkSize` (default 500): maximum characters per chunk
- `chunkOverlap` (default 50): characters of overlap between consecutive chunks

## Metadata

Static metadata is stored as dynamic fields on every chunk in the Milvus collection:

```json
"metadata": {
    "company": "Apple Inc",
    "documentType": "10-Q",
    "filingDate": "2026-01-30"
}
```

This allows filtered vector search queries combining similarity with metadata predicates.

Every entity automatically includes:
- `id` — deterministic UUID (idempotent upserts)
- `text` — the chunk text
- `chunk_index` — position of the chunk in the document
- `filename` — original uploaded filename
- `source_dataset` — pipeline pipeline name
- `embedding` — float vector for similarity search

## How It Works

1. **Upload** — an unstructured file is uploaded via `POST /api/v1/pipeline/upload`
2. **Extract** — text is extracted from the document
3. **Chunk** — text is split into chunks using the configured strategy
4. **Embed** — each chunk is sent to the embedding API to generate a vector
5. **Upsert** — vectors are inserted into the Milvus collection with metadata
6. **Notify** — a pipeline notification is published on completion

The collection is auto-created on first upsert with a cosine distance index on the embedding field. Dynamic fields are enabled for metadata.

## Running Milvus

Milvus runs outside the pipeline's Docker Compose (like Qdrant and Weaviate). Unlike simpler vector databases, Milvus requires etcd and MinIO as internal dependencies, so a simple `docker run` won't work.

**Option 1 — Milvus standalone script (recommended):**

```bash
curl -sfL https://raw.githubusercontent.com/milvus-io/milvus/master/scripts/standalone_embed.sh -o standalone_embed.sh
bash standalone_embed.sh start
```

To stop: `bash standalone_embed.sh stop`

**Option 2 — Milvus docker-compose:**

```bash
wget https://github.com/milvus-io/milvus/releases/download/v2.4.4/milvus-standalone-docker-compose.yml -O milvus-docker-compose.yml
docker-compose -f milvus-docker-compose.yml up -d
```

This starts Milvus with its required etcd and MinIO services.

For more options, see [milvus.io/docs/install_standalone-docker.md](https://milvus.io/docs/install_standalone-docker.md).

## Verifying

```bash
# Check Milvus is running
curl http://localhost:9091/v1/vector/collections

# List collections
curl http://localhost:9091/v1/vector/collections

# Get collection details
curl http://localhost:9091/v1/vector/collections/describe \
  -H "Content-Type: application/json" \
  -d '{"collectionName": "financial_documents"}'

# Query entities
curl -X POST http://localhost:9091/v1/vector/query \
  -H "Content-Type: application/json" \
  -d '{
    "collectionName": "financial_documents",
    "filter": "chunk_index >= 0",
    "outputFields": ["text", "chunk_index", "filename"],
    "limit": 5
  }'
```
