# Datris MCP Server

MCP (Model Context Protocol) server for [Datris](https://datris.ai), the data control plane for AI agents. Enables AI agents (Claude Desktop, Claude Code, Cursor, and custom frameworks) to natively interact with the platform — discover data, create pipelines, upload files, monitor jobs, search vector databases, query structured data, and answer questions with AI.

<!-- mcp-name: io.github.datris/datris -->

## Install

```bash
pip install datris-mcp-server
```

## Usage

### stdio mode (Claude Desktop / Claude Code)

```bash
DATRIS_API_URL=http://localhost:8080 datris-mcp-server
```

Or run directly:

```bash
DATRIS_API_URL=http://localhost:8080 python server.py
```

### SSE mode (Docker / remote agents)

```bash
DATRIS_API_URL=http://localhost:8080 datris-mcp-server --sse --port 3000
```

### Docker

The MCP server starts automatically with `docker compose up` in SSE mode on port 3000.

## Configuration

### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "datris": {
      "command": "datris-mcp-server",
      "env": {
        "DATRIS_API_URL": "http://localhost:8080"
      }
    }
  }
}
```

### Claude Code

Add to `.mcp.json` in your project root:

```json
{
  "mcpServers": {
    "datris": {
      "command": "datris-mcp-server",
      "env": {
        "DATRIS_API_URL": "http://localhost:8080"
      }
    }
  }
}
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATRIS_API_URL` | `http://localhost:8080` | Datris REST API server URL |
| `REQUIRE_API_KEY` | `false` | Reject SSE/HTTP sessions that connect without `x-api-key` |

## Authentication

There is no server-side API key. Each connecting agent authenticates per session by sending an `x-api-key` header; mcp-server forwards that key as-is to the Datris REST API on every tool call. The Datris REST API validates against `oss/api-keys` Vault secret (single-tenant) or `api-key-mappings` (multi-tenant). Manage keys in the Configuration UI's Secrets tab.

## Tools

30+ tools across these categories:

- **Pipeline Management** — create, list, get, delete pipelines; upload files; monitor jobs
- **Vector Search** — semantic search across Qdrant, Weaviate, Milvus, Chroma, pgvector
- **Database Query** — read-only SQL queries (PostgreSQL) and MongoDB queries
- **Metadata Discovery** — explore databases, schemas, tables, columns, collections
- **AI** — RAG-powered question answering
- **System** — health checks, version info

See the full documentation at [docs.datris.ai/mcp-server](https://docs.datris.ai/mcp-server).

## License

Apache 2.0
