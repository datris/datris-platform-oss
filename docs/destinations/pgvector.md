# pgvector (PostgreSQL) Vector Database Destination

Ingest unstructured documents into PostgreSQL with the [pgvector](https://github.com/pgvector/pgvector) extension for retrieval-augmented generation (RAG) and semantic search. The pipeline extracts text from the document, chunks it using a configurable strategy, generates vector embeddings, and upserts the chunks with metadata into a PostgreSQL table with a `vector` column.

pgvector uses your existing PostgreSQL infrastructure — no separate vector database server required. Standard SQL can be used to combine vector similarity search with traditional filters.

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
    "pgvector": {
        "tableName": "financial_documents",
        "schemaName": "public",
        "chunking": {
            "strategy": "recursive",
            "chunkSize": 500,
            "chunkOverlap": 50
        },
        "metadata": {
            "company": "Apple Inc",
            "document_type": "10-Q",
            "filing_date": "2026-01-30"
        },
        "embeddingSecretName": "oss/embedding",
        "postgresSecretName": "oss/pgvector"
    }
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `tableName` | string | (required) | PostgreSQL table name. Auto-created with vector column if it doesn't exist. |
| `schemaName` | string | `"public"` | PostgreSQL schema name. Auto-created if it doesn't exist. |
| `chunking` | object | recursive, 500/50 | Chunking strategy configuration (see below). |
| `metadata` | map | `{}` | Static key-value metadata stored as columns on every chunk. Use snake_case for column names. |
| `embeddingSecretName` | string | (required) | Vault secret name for the embedding API configuration. |
| `postgresSecretName` | string | (required) | Vault secret name for PostgreSQL connection. |

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

For structured data (CSV, JSON, XML), use the standard PostgreSQL database destination instead.

## Vault Secrets

### PostgreSQL connection (for pgvector)

```bash
vault kv put secret/oss/pgvector \
  jdbcUrl="jdbc:postgresql://postgres:5432/datris" \
  username="postgres" \
  password="postgres"
```

| Field | Description |
|-------|-------------|
| `jdbcUrl` | JDBC URL for the PostgreSQL database with pgvector extension installed. |
| `username` | PostgreSQL username. |
| `password` | PostgreSQL password. |

This is separate from the pipeline's standard `oss/postgres` secret so the vector store can target a different database or server.

### Embedding API

The embedding API is shared with other vector database destinations (e.g., Qdrant, Weaviate). See [Qdrant documentation](qdrant.md#embedding-api) for full details.

```bash
vault kv put secret/oss/embedding \
  endpoint="https://api.openai.com/v1/embeddings" \
  model="text-embedding-3-small" \
  apiKey="sk-..."
```

## Chunking Strategies

Documents are split into chunks before embedding. Each chunk becomes a row in the PostgreSQL table with the document's metadata columns plus `chunk_index`, `filename`, and `source_pipeline`.

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

Static metadata is stored as dedicated columns in the PostgreSQL table:

```json
"metadata": {
    "company": "Apple Inc",
    "document_type": "10-Q",
    "filing_date": "2026-01-30"
}
```

Use snake_case for metadata keys since they become PostgreSQL column names. This enables powerful combined queries:

```sql
SELECT text FROM financial_documents
WHERE company = 'Apple Inc' AND document_type = '10-Q'
ORDER BY embedding <=> '[query_vector]'
LIMIT 5;
```

Every row automatically includes:
- `id` — deterministic UUID (idempotent upserts)
- `text` — the chunk text
- `chunk_index` — position of the chunk in the document
- `filename` — original uploaded filename
- `source_pipeline` — pipeline name
- `embedding` — vector column for similarity search

## How It Works

1. **Upload** — an unstructured file is uploaded via `POST /api/v1/pipeline/upload`
2. **Extract** — text is extracted from the document
3. **Chunk** — text is split into chunks using the configured strategy
4. **Embed** — each chunk is sent to the embedding API to generate a vector
5. **Upsert** — chunks are upserted into PostgreSQL with `INSERT ... ON CONFLICT DO UPDATE`
6. **Notify** — a pipeline notification is published on completion

The pgvector extension and table are auto-created on first upsert. Vector dimension is detected from the embedding model.

## Running PostgreSQL with pgvector

The standard PostgreSQL Docker image does not include pgvector. Use the pgvector image:

```bash
docker run -p 5433:5432 -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg16
```

Port 5433 avoids conflict with the pipeline's PostgreSQL instance on 5432.

To add pgvector to an existing PostgreSQL instance:
```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

## Verifying

```bash
# Check pgvector extension is installed
psql -h localhost -p 5433 -U postgres -d datris -c "SELECT extname FROM pg_extension WHERE extname = 'vector';"

# List chunks
psql -h localhost -p 5433 -U postgres -d datris -c "SELECT id, chunk_index, filename, company FROM public.financial_documents LIMIT 5;"

# Similarity search (replace [...] with your query vector)
psql -h localhost -p 5433 -U postgres -d datris -c "
  SELECT text, 1 - (embedding <=> '[...]') AS similarity
  FROM public.financial_documents
  ORDER BY embedding <=> '[...]'
  LIMIT 5;
"

# Combined filter + similarity search
psql -h localhost -p 5433 -U postgres -d datris -c "
  SELECT text, chunk_index
  FROM public.financial_documents
  WHERE company = 'Apple Inc'
  ORDER BY embedding <=> '[...]'
  LIMIT 5;
"
```

## Advantages Over Dedicated Vector Databases

- **No separate server** — uses your existing PostgreSQL infrastructure
- **Standard SQL** — combine vector search with traditional WHERE clauses, JOINs, aggregations
- **ACID transactions** — full transactional guarantees on vector data
- **Familiar tooling** — use psql, pgAdmin, any PostgreSQL client
- **No new dependencies** — uses the existing PostgreSQL JDBC driver
