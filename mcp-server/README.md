# Datris MCP Server

MCP (Model Context Protocol) server for the [Datris AI Agent-Native Data Platform](https://datris.ai). Enables AI agents (Claude Desktop, Claude Code, Cursor, and custom frameworks) to natively interact with the platform — discover data, create pipelines, upload files, monitor jobs, search vector databases, query structured data, and answer questions with AI.

<!-- mcp-name: io.github.datris/datris -->

## Install

```bash
pip install datris-mcp-server
```

## Usage

### stdio mode (Claude Desktop / Claude Code)

```bash
PIPELINE_URL=http://localhost:8080 datris-mcp-server
```

Or run directly:

```bash
PIPELINE_URL=http://localhost:8080 python server.py
```

### SSE mode (Docker / remote agents)

```bash
PIPELINE_URL=http://localhost:8080 datris-mcp-server --sse --port 3000
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
        "PIPELINE_URL": "http://localhost:8080"
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
        "PIPELINE_URL": "http://localhost:8080"
      }
    }
  }
}
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PIPELINE_URL` | `http://localhost:8080` | Datris pipeline server URL |
| `PIPELINE_API_KEY` | (empty) | API key if pipeline has key validation enabled |

## Tools

30+ tools across these categories:

- **Pipeline Management** — create, list, get, delete pipelines; upload files; monitor jobs
- **Vector Search** — semantic search across Qdrant, Weaviate, Milvus, Chroma, pgvector
- **Database Query** — read-only SQL queries (PostgreSQL) and MongoDB queries
- **Metadata Discovery** — explore databases, schemas, tables, columns, collections
- **AI** — RAG-powered question answering
- **System** — health checks, version info

See the full documentation at [docs/mcp.md](https://github.com/datris/datris-platform-oss/blob/main/docs/mcp.md).

## License

Apache 2.0
