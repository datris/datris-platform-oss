# Datris — The First AI Agent-Native Data Platform

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

## Retrieval Flow

```
Client (AI Agent via MCP / Developer via REST API)
  |
  v
Interface
  ├── MCP Server (stdio or SSE)
  └── REST API (POST /api/v1/query/* and /api/v1/search/*)
  |
  v
Query Source
  ├── PostgreSQL (read-only SQL SELECT queries)
  ├── MongoDB (document queries with filters and projections)
  ├── Qdrant (semantic search)
  ├── Weaviate (semantic search)
  ├── Milvus (semantic search)
  ├── Chroma (semantic search)
  ├── pgvector (semantic search via PostgreSQL)
  ├── Dataset Configurations (list/get)
  ├── Job Status (by pipeline token or dataset name)
  ├── AI Schema Generation (from uploaded files)
  └── AI Data Profiling (statistics, quality issues, suggested rules)
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

## Getting Started

```bash
git clone https://github.com/datris/datris-platform-oss.git
cd datris-platform-oss
cp .env.example .env       # Add your ANTHROPIC_API_KEY and/or OPENAI_API_KEY
docker compose up -d
```

The platform is running at [http://localhost:4200](http://localhost:4200) (UI) and [http://localhost:8080](http://localhost:8080) (API).

See [Installation](docs/installation.md) for details on API keys, vector databases, and building from source.

## Documentation

- [Installation](docs/installation.md) - Get running with Docker Compose
- [Quick Start](docs/quick-start.md) - End-to-end walkthrough
- [Dataset Configuration](docs/dataset-configuration.md) - Full JSON configuration reference
- [Schemas](docs/schemas.md) - Schema definition and auto-generation
- **Ingestion**
  - [File Upload](docs/ingestion/file-upload.md) - API file upload
  - [Object Store](docs/ingestion/object-store.md) - MinIO bucket notifications
  - [Database Pull](docs/ingestion/database-pull.md) - PostgreSQL, MySQL, MSSQL scheduled pulls
  - [Kafka](docs/ingestion/kafka.md) - Kafka topic consumption
  - [Data Types](docs/ingestion/data-types.md) - Supported data types
- [Preprocessor](docs/preprocessor.md) - External preprocessing via REST endpoints
- **Data Quality**
  - [Header Validation](docs/data-quality/header-validation.md) - CSV header matching
  - [Column Rules & AI Rules](docs/data-quality/column-rules.md) - Regex column validation and AI-powered natural language rules
  - [Row Rules](docs/data-quality/row-rules.md) - JavaScript and REST endpoint rules
  - [Schema Validation](docs/data-quality/schema-validation.md) - JSON/XML schema validation
- **Transformations**
  - [Deduplication](docs/transformation/deduplication.md) - Row deduplication
  - [Column Trimming](docs/transformation/column-trimming.md) - Whitespace trimming
  - [Dropping Columns](docs/transformation/dropping-columns.md) - Column filtering via destination schema
  - [AI Transformation](docs/transformation/ai-transformation.md) - Natural language row transformations
  - [Row Functions](docs/transformation/row-functions.md) - JavaScript row transformations
- **Destinations**
  - [Object Store](docs/destinations/object-store.md) - Spark writes to MinIO (Parquet, ORC)
  - [PostgreSQL](docs/destinations/postgres.md) - COPY bulk insert
  - [MongoDB](docs/destinations/mongodb.md) - Document upserts
  - [Kafka](docs/destinations/kafka.md) - Topic producer
  - [ActiveMQ](docs/destinations/activemq.md) - Queue destination
  - [REST Endpoint](docs/destinations/rest-endpoint.md) - HTTP POST destination
  - [Qdrant](docs/destinations/qdrant.md) - Vector database for RAG — ingest PDFs, text docs with chunking and embeddings
  - [Weaviate](docs/destinations/weaviate.md) - Vector database for RAG — ingest PDFs, Word docs, text with chunking and embeddings
  - [Milvus](docs/destinations/milvus.md) - Vector database for RAG — scalable similarity search
  - [Chroma](docs/destinations/chroma.md) - Vector database for RAG — lightweight, single container
  - [pgvector](docs/destinations/pgvector.md) - PostgreSQL vector database for RAG — no separate server required
- [Notifications](docs/notifications.md) - Dataset event notifications and subscriptions
- [Monitoring](docs/monitoring.md) - Job status and pipeline tokens
- **API Reference**
  - [Dataset API](docs/api-reference/dataset-api.md) - CRUD for dataset configurations
  - [Ingestion API](docs/api-reference/ingestion-api.md) - File upload and generation
  - [AI Schema Generation](docs/api-reference/schema-generation-api.md) - Generate dataset configs from files using AI
  - [Status API](docs/api-reference/status-api.md) - Job status and monitoring
  - [Query API](docs/api-reference/query-api.md) - Query PostgreSQL and MongoDB
  - [Search API](docs/api-reference/search-api.md) - Semantic search across vector databases
- [AI Data Profiling](docs/ai-data-profiling.md) - Profile data files and get recommended rules
- [AI Error Explanation](docs/ai-error-explanation.md) - Automatic plain-English error analysis
- [AI Configuration](docs/ai-configuration.md) - Configure AI providers (Anthropic, OpenAI, Ollama)
  - [Version API](docs/api-reference/version-api.md) - Version endpoint
- [Configuration Reference](docs/configuration-reference.md) - Full application.yaml reference
- [MCP Server](docs/mcp.md) - AI agent integration via Model Context Protocol
- [Helper Applications](docs/helpers.md) - Vector store chat, Kafka loader, preprocessor, and more
