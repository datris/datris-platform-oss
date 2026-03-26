# MCP Server Test

**Location:** `examples/test-mcp-server/`

An integration test script that exercises the MCP server tools end-to-end. Tests the full round-trip: create pipeline from sample data, upload data, wait for ingestion, then query it back.

## Test Flow

1. **CSV → PostgreSQL** — create pipeline (auto-detects schema from sample data), upload CSV, wait, query back with SQL
2. **JSON → MongoDB** — create pipeline, upload JSON, wait, query back with find()
3. **PDF → pgvector** — create pipeline, upload PDF, wait for chunking/embedding, semantic search
4. **Cleanup** — delete all test pipelines (destination data is cleaned up automatically)

## Setup

```bash
cd examples/test-mcp-server
pip install requests python-dotenv psycopg2-binary pymongo openai
```

## Run

```bash
python app.py
```

Requires Datris running via `docker compose up`. Vector search requires an OpenAI API key for embedding generation.
