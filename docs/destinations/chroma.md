# Chroma Vector Database Destination

Ingest unstructured documents into [Chroma](https://www.trychroma.com), a lightweight, developer-friendly open-source vector database. The pipeline extracts text from the document, chunks it using a configurable strategy, generates vector embeddings, and upserts the chunks with metadata into a Chroma collection via its REST API.

Chroma is the simplest vector database to set up — a single Docker container with no external dependencies.

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
    "chroma": {
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
        "chromaSecretName": "oss/chroma"
    }
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `collectionName` | string | (required) | Chroma collection name. Auto-created with cosine distance if it doesn't exist. |
| `chunking` | object | recursive, 500/50 | Chunking strategy configuration (see below). |
| `metadata` | map | `{}` | Static key-value metadata stored on every chunk. Used for filtered search. |
| `embeddingSecretName` | string | (required) | Vault secret name for the embedding API configuration. |
| `chromaSecretName` | string | (required) | Vault secret name for Chroma connection. |

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

### Chroma connection

```bash
vault kv put secret/oss/chroma \
  host="host.docker.internal" \
  port="8000"
```

| Field | Description |
|-------|-------------|
| `host` | Chroma server hostname. Use `host.docker.internal` for local Chroma. |
| `port` | REST API port (default 8000). |

Chroma does not require authentication by default. For Chroma Cloud or authenticated instances, add token-based auth to the loader as needed.

### Embedding API

The embedding API is shared with other vector database destinations. See [Qdrant documentation](qdrant.md#embedding-api) for full details.

```bash
vault kv put secret/oss/embedding \
  endpoint="https://api.openai.com/v1/embeddings" \
  model="text-embedding-3-small" \
  apiKey="sk-..."
```

## Chunking Strategies

Documents are split into chunks before embedding. Each chunk becomes a separate entry in the Chroma collection with the document's metadata plus `chunk_index`, `filename`, and `source_dataset` fields.

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

Static metadata is stored on every chunk in the Chroma collection:

```json
"metadata": {
    "company": "Apple Inc",
    "documentType": "10-Q",
    "filingDate": "2026-01-30"
}
```

This allows filtered queries combining similarity with metadata predicates using Chroma's `where` clause.

Every entry automatically includes:
- `text` — the chunk text (stored as Chroma document)
- `chunk_index` — position of the chunk in the document
- `filename` — original uploaded filename
- `source_dataset` — pipeline dataset name

## How It Works

1. **Upload** — an unstructured file is uploaded via `POST /api/v2/dataset/upload`
2. **Extract** — text is extracted from the document
3. **Chunk** — text is split into chunks using the configured strategy
4. **Embed** — each chunk is sent to the embedding API to generate a vector
5. **Upsert** — vectors are upserted into the Chroma collection via REST API with metadata
6. **Notify** — a dataset notification is published on completion

The collection is auto-created on first upsert with cosine distance. No Java client library is needed — the loader communicates with Chroma's REST API directly via HTTP.

## Running Chroma

Chroma runs outside Docker (like the other vector databases). Start locally with a single command:

```bash
docker run -d --name chroma -p 8000:8000 chromadb/chroma:latest
```

That's it — no external dependencies like etcd or MinIO. Chroma is the simplest vector database to run.

## Verifying

```bash
# Check Chroma is running
curl http://localhost:8000/api/v2/heartbeat

# List collections
curl http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections

# Get collection details
curl http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections/financial_documents

# Count entries in collection (replace COLLECTION_ID with the actual UUID)
curl http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections/COLLECTION_ID/count
```
