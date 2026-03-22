# Datris — AI Data Platform for Agents and Humans

[datris.ai](https://datris.ai)

Ingest, validate, transform, store, and retrieve your data — whether you're an AI agent talking through MCP or a developer writing config. One platform for both.

Deploy on any cloud provider, on-premise, or locally — with no vendor lock-in. Define your entire data pipeline through simple JSON configuration, or extend it with AI instructions, JavaScript functions, and REST endpoints at every stage of the flow. Built entirely on open-source infrastructure, it runs anywhere Docker does.

### Agent-Ready: Built-In MCP Server

Your AI agents are first-class pipeline operators. Datris ships with a native MCP (Model Context Protocol) server — the first open-source data platform natively accessible to AI agents. Claude, Cursor, OpenClaw, and any MCP-compatible agent can register datasets, upload files, trigger processing, monitor job status, profile data, run semantic searches across vector databases, and query PostgreSQL and MongoDB — all through natural conversation. Supports stdio and SSE transports.

## AI-Powered Features

Intelligence at every stage — from ingestion to delivery, Datris makes data engineering accessible through natural language.

- **MCP server (AI agent integration)** - Built-in [MCP](https://modelcontextprotocol.io) server lets AI agents (Claude, Cursor, OpenClaw, custom frameworks) natively interact with the pipeline — register datasets, upload files, trigger jobs, profile data, run semantic searches, and query databases. Supports stdio and SSE transports
- **AI-powered data quality** - Validate with plain English rules via `aiRule`. The AI model evaluates every row using reasoning and domain knowledge — no regex required. Supports sampling for large files
- **AI transformations** - Describe row transformations in natural language — date format conversion, data categorization, phone number standardization, entity extraction — no code needed
- **AI schema generation** - Upload any CSV, JSON, or XML file and receive a complete, ready-to-register dataset configuration — field names and types inferred automatically
- **AI data profiling** - Upload a file and get summary statistics, quality issues, and suggested validation rules — all powered by AI analysis
- **AI error explanation** - When jobs fail, AI analyzes the error chain and explains the root cause in plain English. No more digging through stack traces
- **AI providers** - Anthropic Claude (Opus 4.6, Sonnet 4.6, Haiku), OpenAI (GPT-5, GPT-4.1, o3, embedding models), or local models via Ollama (Llama, Mistral, Phi). No vendor lock-in — switch providers without changing your pipeline config

## RAG Pipeline

Full RAG pipeline built in. Extract, chunk, embed, and upsert documents into any major vector database — build retrieval-augmented generation workflows without leaving your pipeline.

- **5 vector databases** - Qdrant, Weaviate, Milvus, Chroma, pgvector (PostgreSQL)
- **Chunking strategies** - Fixed-size, sentence, paragraph, recursive
- **Embedding providers** - OpenAI or Ollama (local models)
- **Document extraction** - PDF, Word, PowerPoint, Excel, HTML, email, EPUB, plain text

## Key Features

- **Configuration-driven** - Define datasets entirely through JSON, or extend the pipeline with AI instructions, JavaScript functions, REST endpoints, and preprocessors at every stage of the data flow
- **Multiple ingestion methods** - File upload API, MinIO bucket events, database polling, Kafka streaming
- **Data quality** - AI rules, regex column checks, JavaScript row rules, REST endpoint row rules, JSON/XML schema validation
- **Transformations** - AI transformations, deduplication, whitespace trimming, JavaScript row functions
- **Multiple destinations** - Write to MinIO (Parquet/ORC), PostgreSQL, MongoDB, Kafka, ActiveMQ, REST endpoints, Qdrant, Weaviate, Milvus, Chroma, or pgvector in parallel
- **Event notifications** - Subscribe to dataset processing events via ActiveMQ topics

## Architecture

Push and pull — one platform, two interfaces. AI agents and humans ingest data through the pipeline, store it across databases and vector stores, and retrieve it back — via API or MCP.

Self-hosted on proven open-source infrastructure — no proprietary services, no vendor lock-in, no surprise bills:

| Service | Purpose |
|---------|---------|
| **MinIO** | S3-compatible object store for file staging and data output |
| **MongoDB** | Configuration store, job status tracking, metadata |
| **ActiveMQ** | File notification queue, dataset event notifications |
| **HashiCorp Vault** | Secrets management (database credentials, API keys) |
| **Apache Kafka** | Optional streaming source and destination |
| **Apache Spark** | Local Spark for writing Parquet/ORC to MinIO |

## Processing Flow

```
Source (File Upload / MinIO Event / Database Pull / Kafka)
  |
  v
Preprocessor (optional REST endpoint)
  |
  v
Data Quality (AI rules, header validation, column rules, row rules, schema validation)
  |
  v
Transformation (deduplication, trimming, JavaScript row functions)
  |
  v
Destinations (executed in parallel)
  ├── Object Store (MinIO - Parquet, ORC, CSV)
  ├── PostgreSQL (COPY bulk insert)
  ├── MongoDB (document upsert)
  ├── Kafka (topic producer)
  ├── ActiveMQ (queue)
  ├── REST Endpoint (HTTP POST)
  ├── Qdrant (vector database - chunking, embeddings, RAG)
  ├── Weaviate (vector database - chunking, embeddings, RAG)
  ├── Milvus (vector database - chunking, embeddings, RAG)
  ├── Chroma (vector database - chunking, embeddings, RAG)
  └── pgvector (PostgreSQL vector database - chunking, embeddings, RAG)
  |
  v
Notifications (published to ActiveMQ topic)
```

## Supported Data Formats

| Format | Input | Output |
|--------|-------|--------|
| CSV | Configurable delimiter, header, encoding | Parquet, ORC, database, Kafka, ActiveMQ |
| JSON | Single object or NDJSON (one per line) | MongoDB, Kafka, REST |
| XML | Single document or one per line | Database, Kafka, REST |
| Excel (XLS) | Worksheet selection, auto-CSV conversion | Same as CSV |
| Unstructured | PDF, Word, PowerPoint, Excel, HTML, email, EPUB, text | Object store, Qdrant, Weaviate, pgvector |
| Archives | .zip, .tar, .gz, .jar | Extracted and processed individually |

## Quick Links

- [Installation](installation.md) - Get running with Docker Compose
- [Quick Start](quick-start.md) - End-to-end walkthrough
- [Dataset Configuration](dataset-configuration.md) - Full JSON configuration reference
- [Preprocessor](preprocessor.md) - External preprocessing via REST endpoints
- [API Reference](api-reference/dataset-api.md) - REST API documentation
- [AI Schema Generation](api-reference/schema-generation-api.md) - Generate dataset configs from files using AI
- [AI Configuration](ai-configuration.md) - Configure AI providers (Anthropic, OpenAI, Ollama)
- [AI Data Quality Rules](data-quality/column-rules.md) - Natural language validation with `aiRule`
- [AI Data Profiling](ai-data-profiling.md) - Profile data files and get recommended rules
- [AI Error Explanation](ai-error-explanation.md) - Automatic plain-English error analysis
- [Qdrant Destination](destinations/qdrant.md) - Vector database for RAG with chunking, embeddings, and metadata
- [Weaviate Destination](destinations/weaviate.md) - Vector database for RAG with chunking, embeddings, and metadata
- [Milvus Destination](destinations/milvus.md) - Scalable vector database for RAG
- [Chroma Destination](destinations/chroma.md) - Lightweight vector database for RAG — single container
- [pgvector Destination](destinations/pgvector.md) - PostgreSQL vector database for RAG — no separate server required
- [MCP Server](mcp.md) - AI agent integration via Model Context Protocol
- [Helper Applications](helpers.md) - Vector store chat, Kafka loader, preprocessor, and more
