# Search API

The Search API provides REST endpoints for semantic search across vector databases. Each endpoint converts a natural language query into an embedding vector, searches the specified vector database, and returns the most similar results with relevance scores.

All search endpoints require embedding and vector database connection details stored in HashiCorp Vault.

## Common Parameters

All search endpoints share these parameters:

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `query` | string | Yes | | Natural language search query |
| `embeddingSecretName` | string | Yes | | Vault secret name for the embedding provider (must contain `endpoint`, `model`, and optionally `apiKey`) |
| `topK` | integer | No | 5 | Number of results to return |

## Common Response Format

All search endpoints return:

```json
{
  "results": [
    {
      "text": "document chunk content",
      "chunk_index": 0,
      "source_dataset": "dataset_name",
      "filename": "document.pdf",
      "_score": 0.89
    }
  ],
  "count": 1
}
```

The `_score` field indicates relevance (higher is more similar, normalized to 0–1 where applicable).

---

## Search Qdrant

```
POST /api/v1/search/qdrant
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `collection` | string | No | `financial_documents` | Qdrant collection name |
| `qdrantSecretName` | string | Yes | | Vault secret (must contain `host`, optionally `port`, `apiKey`) |

```bash
curl -X POST http://localhost:8080/api/v1/search/qdrant \
  -H "Content-Type: application/json" \
  -d '{
    "query": "quarterly revenue projections",
    "collection": "financial_documents",
    "embeddingSecretName": "oss/openai-embedding",
    "qdrantSecretName": "oss/qdrant",
    "topK": 5
  }'
```

---

## Search Weaviate

```
POST /api/v1/search/weaviate
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `className` | string | No | `FinancialDocuments` | Weaviate class name (PascalCase) |
| `weaviateSecretName` | string | Yes | | Vault secret (must contain `host`, optionally `port`, `scheme`, `apiKey`) |

```bash
curl -X POST http://localhost:8080/api/v1/search/weaviate \
  -H "Content-Type: application/json" \
  -d '{
    "query": "quarterly revenue projections",
    "className": "FinancialDocuments",
    "embeddingSecretName": "oss/openai-embedding",
    "weaviateSecretName": "oss/weaviate",
    "topK": 5
  }'
```

---

## Search Milvus

```
POST /api/v1/search/milvus
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `collection` | string | No | `financial_documents` | Milvus collection name |
| `milvusSecretName` | string | Yes | | Vault secret (must contain `host`, optionally `port`, `apiKey`) |

```bash
curl -X POST http://localhost:8080/api/v1/search/milvus \
  -H "Content-Type: application/json" \
  -d '{
    "query": "quarterly revenue projections",
    "collection": "financial_documents",
    "embeddingSecretName": "oss/openai-embedding",
    "milvusSecretName": "oss/milvus",
    "topK": 5
  }'
```

---

## Search Chroma

```
POST /api/v1/search/chroma
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `collection` | string | No | `financial_documents` | Chroma collection name |
| `chromaSecretName` | string | Yes | | Vault secret (must contain `host`, optionally `port`) |

```bash
curl -X POST http://localhost:8080/api/v1/search/chroma \
  -H "Content-Type: application/json" \
  -d '{
    "query": "quarterly revenue projections",
    "collection": "financial_documents",
    "embeddingSecretName": "oss/openai-embedding",
    "chromaSecretName": "oss/chroma",
    "topK": 5
  }'
```

---

## Search pgvector

```
POST /api/v1/search/pgvector
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `table` | string | No | `financial_documents` | PostgreSQL table name |
| `schema` | string | No | `public` | PostgreSQL schema |
| `postgresSecretName` | string | Yes | | Vault secret (must contain `jdbcUrl`, optionally `username`, `password`) |

```bash
curl -X POST http://localhost:8080/api/v1/search/pgvector \
  -H "Content-Type: application/json" \
  -d '{
    "query": "quarterly revenue projections",
    "table": "financial_documents",
    "schema": "public",
    "embeddingSecretName": "oss/openai-embedding",
    "postgresSecretName": "oss/pgvector",
    "topK": 5
  }'
```

---

## Vault Secret Structure

### Embedding Secret

```json
{
  "endpoint": "https://api.openai.com/v1/embeddings",
  "model": "text-embedding-3-small",
  "apiKey": "sk-..."
}
```

### Vector Database Secrets

**Qdrant:**
```json
{ "host": "localhost", "port": "6334", "apiKey": "" }
```

**Weaviate:**
```json
{ "host": "localhost", "port": "8079", "scheme": "http", "apiKey": "" }
```

**Milvus:**
```json
{ "host": "localhost", "port": "19530", "apiKey": "" }
```

**Chroma:**
```json
{ "host": "localhost", "port": "8000" }
```

**pgvector:**
```json
{ "jdbcUrl": "jdbc:postgresql://localhost:5432/idata", "username": "postgres", "password": "postgres" }
```
