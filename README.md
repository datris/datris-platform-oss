# Datris — The First AI Agent-Native Data Platform

[![PyPI](https://img.shields.io/pypi/v/datris-mcp-server)](https://pypi.org/project/datris-mcp-server/)
[![MCP Registry](https://img.shields.io/badge/MCP_Registry-io.github.datris%2Fdatris-blue)](https://registry.modelcontextprotocol.io/servers/io.github.datris/datris)
[![Docker Hub](https://img.shields.io/docker/v/datrisai/datris-server?label=Docker%20Hub)](https://hub.docker.com/u/datrisai)
[![License](https://img.shields.io/github/license/datris/datris-platform-oss)](LICENSE)

[datris.ai](https://datris.ai) · [Try Hosted Free](https://datris.ai/signup) · [Documentation](https://docs.datris.ai) · [MCP Registry](https://registry.modelcontextprotocol.io/servers/io.github.datris/datris) · [PyPI](https://pypi.org/project/datris-mcp-server/)

Ingest, validate, transform, store, and retrieve your data — whether you're an AI agent talking through MCP or a developer writing config. One platform for both.

## Why Datris?

- **Agent-native** — Built-in MCP server with 35+ tools. Claude, Cursor, OpenClaw, and any MCP-compatible agent can operate pipelines through natural conversation
- **Taps** — AI-generated Python scripts that fetch data from external sources (APIs, web scraping, databases) and push it into pipelines. Describe what you want, Datris generates the script. Includes AI diagnosis, CRON scheduling, and credentials via Vault
- **AI at every stage** — AI data quality, AI transformations, AI schema generation, AI profiling, AI error explanation, natural language queries, RAG
- **No vendor lock-in** — 100% open-source infrastructure (MinIO, PostgreSQL, MongoDB, Kafka, Vault). Runs anywhere Docker does
- **Configuration-driven** — Define pipelines through JSON. No code required

## Quick Start

```bash
git clone https://github.com/datris/datris-platform-oss.git
cd datris-platform-oss
cp .env.example .env       # Add your ANTHROPIC_API_KEY and/or OPENAI_API_KEY
docker compose up -d
```

**UI**: [http://localhost:4200](http://localhost:4200) · **API**: [http://localhost:8080](http://localhost:8080)

### Connect an AI Agent

Add to your MCP client config (Claude Desktop, Cursor, etc.):

```json
{
  "mcpServers": {
    "datris": {
      "command": "uvx",
      "args": ["datris-mcp-server"],
      "env": {
        "PIPELINE_URL": "http://localhost:8080"
      }
    }
  }
}
```

### CLI

```bash
brew tap datris/tap
brew install datris
datris ingest data.csv --dest postgres
datris ingest sales.csv --ai-validate "prices > 0" --ai-transform "convert dates to YYYY/MM/DD"
datris query "SELECT * FROM sales"
datris search "quarterly revenue" --store pgvector
datris tap create "Fetch S&P 500 daily prices from yfinance" --pipeline stocks
datris taps
```

## What It Does

```
Source (File Upload / MinIO Event / Database Pull / Kafka)
  → Preprocessor (optional REST endpoint)
  → Data Quality (AI rules, header validation, schema validation)
  → Transformation (AI transformation, destination schema)
  → Destinations (in parallel):
      PostgreSQL, MongoDB, MinIO (Parquet/ORC), Kafka, ActiveMQ,
      REST Endpoint, Qdrant, Weaviate, Milvus, Chroma, pgvector
  → Notifications (ActiveMQ topic)
```

### AI-Powered Features

| Feature | Description |
|---------|-------------|
| **MCP Server** | 30+ tools for AI agents — pipeline CRUD, upload, query, search, profiling |
| **AI Data Quality** | Plain English validation rules — AI generates and runs a validation script |
| **AI Transformation** | Plain English transformations — AI generates and runs a transformation script |
| **AI Schema Generation** | Upload a file, get a complete pipeline config |
| **AI Data Profiling** | Upload a file, get statistics + suggested validation rules |
| **AI Error Explanation** | Job failures explained in plain English |
| **Natural Language Query** | Ask questions in English, get SQL results |
| **RAG Pipeline** | Chunk, embed, and search across 5 vector databases |

### Supported Formats

CSV, JSON, XML, Excel, PDF, Word, PowerPoint, HTML, email, EPUB, plain text, .zip/.tar/.gz archives

### AI Providers

Anthropic Claude (Opus 4.6, Sonnet 4.6, Haiku) · OpenAI (GPT-5, GPT-4.1, o3) · Ollama (local models)

## Architecture

| Service | Purpose |
|---------|---------|
| **MinIO** | S3-compatible object store for file staging and data output |
| **MongoDB** | Configuration store, job status tracking, metadata |
| **ActiveMQ** | File notification queue, pipeline event notifications |
| **HashiCorp Vault** | Secrets management (database credentials, API keys) |
| **Apache Kafka** | Optional streaming source and destination |
| **Apache Spark** | Local Spark for writing Parquet/ORC to MinIO |

## Documentation

Full documentation at [docs.datris.ai](https://docs.datris.ai) or locally at `docs/`.

## License

[AGPL-3.0](LICENSE)
