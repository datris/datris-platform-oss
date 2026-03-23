# MCP Server (AI Agent Integration)

The pipeline includes a built-in [MCP (Model Context Protocol)](https://modelcontextprotocol.io) server that lets AI agents interact with the pipeline natively. Any MCP-compatible agent — Claude Desktop, Claude Code, Cursor, or custom agentic frameworks — can upload files, register pipelines, monitor jobs, profile data, search vector databases, and query structured data without custom integration code.

The MCP server is a lightweight Python service that calls the pipeline's existing REST API and connects directly to backend databases for query operations. It runs alongside the pipeline in Docker or locally for development.

## Available Tools

### Pipeline Management

| Tool | Description |
|------|-------------|
| `list_pipelines` | List all registered pipeline configurations |
| `get_pipeline` | Get a specific pipeline configuration by name |
| `create_pipeline` | Register or update a pipeline configuration |
| `delete_pipeline` | Delete a pipeline configuration |
| `upload_file` | Upload a file for processing (returns pipeline token) |
| `get_job_status` | Get job status by pipeline token or pipeline name |
| `kill_job` | Kill a running job by pipeline token |
| `generate_schema` | AI-generate a pipeline config from a file (CSV, JSON, XML) |
| `profile_data` | AI-profile data with summary stats and suggested DQ rules |
| `get_version` | Get pipeline server version |

### Vector Database Search

Semantic search across any of the pipeline's supported vector databases. Each tool takes a natural language query, generates an embedding, and returns the most similar document chunks with scores and metadata.

| Tool | Description |
|------|-------------|
| `search_qdrant` | Search a Qdrant collection |
| `search_weaviate` | Search a Weaviate class |
| `search_milvus` | Search a Milvus collection |
| `search_chroma` | Search a Chroma collection |
| `search_pgvector` | Search a pgvector PostgreSQL table |

### Database Queries

Read-only queries against the pipeline's backend databases. PostgreSQL queries run in a read-only transaction; MongoDB uses find() only.

| Tool | Description |
|------|-------------|
| `query_postgres` | Execute a read-only SQL SELECT query against PostgreSQL |
| `query_mongodb` | Query a MongoDB collection with filter and projection |

## Setup

### Docker (automatic)

The MCP server starts automatically with `docker-compose up` in SSE mode on port 3000. No additional setup required.

### Local (for Claude Desktop / Claude Code)

```bash
cd mcp-server
pip install -r requirements.txt
python server.py          # stdio mode (default)
```

## Transport Modes

| Mode | Use Case | Command |
|------|----------|---------|
| **stdio** | Claude Desktop, Claude Code, local agents | `python server.py` |
| **SSE** | Docker, remote agents, web clients | `python server.py --sse --port 3000` |

## Configuring Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
    "mcpServers": {
        "datris-pipeline": {
            "command": "python",
            "args": ["/path/to/mcp-server/server.py"],
            "env": {
                "PIPELINE_URL": "http://localhost:8080"
            }
        }
    }
}
```

## Configuring Claude Code

Add to `.mcp.json` in your project root:

```json
{
    "mcpServers": {
        "datris-pipeline": {
            "command": "python",
            "args": ["mcp-server/server.py"],
            "env": {
                "PIPELINE_URL": "http://localhost:8080"
            }
        }
    }
}
```

## Environment Variables

### Pipeline

| Variable | Default | Description |
|----------|---------|-------------|
| `PIPELINE_URL` | `http://localhost:8080` | Pipeline server URL |
| `PIPELINE_API_KEY` | (empty) | API key if pipeline has key validation enabled |

### Embedding (required for vector search tools)

| Variable | Default | Description |
|----------|---------|-------------|
| `EMBEDDING_PROVIDER` | `openai` | `openai` or `ollama` |
| `EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding model name |
| `EMBEDDING_ENDPOINT` | `http://localhost:11434` | Ollama endpoint (if using Ollama) |
| `OPENAI_API_KEY` | (empty) | OpenAI API key (if using OpenAI) |

### Vector Databases

| Variable | Default | Description |
|----------|---------|-------------|
| `QDRANT_HOST` | `localhost` | Qdrant host |
| `QDRANT_PORT` | `6333` | Qdrant port |
| `WEAVIATE_HOST` | `localhost` | Weaviate host |
| `WEAVIATE_PORT` | `8079` | Weaviate REST port |
| `WEAVIATE_GRPC_PORT` | `50051` | Weaviate gRPC port |
| `MILVUS_HOST` | `localhost` | Milvus host |
| `MILVUS_PORT` | `19530` | Milvus port |
| `CHROMA_HOST` | `localhost` | Chroma host |
| `CHROMA_PORT` | `8000` | Chroma port |
| `PG_HOST` | `localhost` | pgvector PostgreSQL host |
| `PG_PORT` | `5432` | pgvector PostgreSQL port |
| `PG_DATABASE` | `datris` | pgvector database name |
| `PG_USER` | `postgres` | pgvector username |
| `PG_PASSWORD` | `postgres` | pgvector password |

### Databases

| Variable | Default | Description |
|----------|---------|-------------|
| `MONGO_URI` | `mongodb://localhost:27017` | MongoDB connection URI |
| `MONGO_DATABASE` | `datris` | MongoDB database name |

PostgreSQL query tool reuses the `PG_*` variables above.

## Example Agent Workflows

### Profile and ingest a CSV file

An AI agent could autonomously:

1. **Profile the data** — `profile_data` with the CSV file
2. **Review suggested rules** — agent reads the AI-suggested DQ rules
3. **Create the pipeline** — `create_pipeline` with the profiled config
4. **Upload the file** — `upload_file` to trigger processing
5. **Monitor status** — `get_job_status` to track completion

### Build and query a RAG knowledge base

1. **Create pipeline** — `create_pipeline` with Qdrant/Weaviate/Milvus/pgvector destination
2. **Upload documents** — `upload_file` for each PDF/document
3. **Monitor** — `get_job_status` until all documents are processed
4. **Search** — `search_qdrant` to find relevant chunks
5. **Answer** — agent synthesizes answer from retrieved context

### Cross-database analysis

1. **Search documents** — `search_pgvector` for relevant financial document chunks
2. **Query structured data** — `query_postgres` to get related financial metrics
3. **Combine** — agent merges unstructured + structured data in its response

### Data exploration

1. **Browse configs** — `query_mongodb` to list pipeline configurations
2. **Sample data** — `query_postgres` to preview ingested tables
3. **Summarize** — agent describes what data is available

### Automated data quality monitoring

1. **List pipelines** — `list_pipelines` to discover all registered pipelines
2. **Upload new data** — `upload_file` with latest data files
3. **Check results** — `get_job_status` to see DQ failures
4. **Diagnose** — AI reads error explanations and suggests fixes
