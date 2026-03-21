# Datris - Open Source Edition

Datris is an AI-enhanced, cloud-agnostic, open-source data pipeline that makes it effortless to ingest, validate, transform, and load your data into object stores, databases, message queues, vector databases, and REST endpoints. Use AI to automatically generate dataset schemas, validate data quality using plain English rules, transform data without writing code, and ingest unstructured documents into vector databases for retrieval-augmented generation (RAG) — all powered by Anthropic Claude, OpenAI, or local models via Ollama.

Deploy on any cloud provider, on-premise, or locally — with no vendor lock-in. Define your entire data pipeline through simple JSON configuration, or extend it with AI instructions, JavaScript functions, and REST endpoints at every stage of the flow. Built entirely on open-source infrastructure, it runs anywhere Docker does.

### Agent-Ready: Built-In MCP Server

The pipeline also ships with a built-in MCP (Model Context Protocol) server, making it the first open-source data pipeline natively accessible to AI agents. Claude, Cursor, OpenClaw, and any MCP-compatible agent can register datasets, upload files, trigger processing, monitor job status, profile data, run semantic searches across vector databases, and query PostgreSQL and MongoDB — all through natural conversation. This means your AI agents can autonomously build data pipelines, ingest documents into vector stores for RAG, answer questions from ingested data, and diagnose data quality issues without writing integration code.

## Key Features

- **Configuration-driven** - Define datasets entirely through JSON, or extend the pipeline with AI instructions, JavaScript functions, REST endpoints, and preprocessors at every stage of the data flow
- **Vector database (RAG)** - Ingest unstructured documents (PDFs, Word, PowerPoint, Excel, HTML, email, EPUB, text) into Qdrant, Weaviate, Milvus, Chroma, or pgvector (PostgreSQL) for retrieval-augmented generation. The pipeline extracts text, chunks it using configurable strategies (fixed, sentence, paragraph, recursive), generates embeddings via OpenAI or Ollama, and upserts into the vector database with metadata for filtered search
- **AI-powered data quality** - Define validation rules in plain English using `aiRule`. The AI model receives the full file and evaluates every row against your instruction — catching issues that require reasoning, domain knowledge, or cross-column logic. Supports sampling for large files
- **AI transformations** - Describe row transformations in plain English — date format conversion, data categorization, phone number standardization, entity extraction — without writing code
- **AI schema generation** - Upload any CSV, JSON, or XML file to `POST /api/v1/dataset/generate` and receive a complete, ready-to-register dataset configuration — field names and types inferred automatically
- **AI data profiling** - Upload any file to `POST /api/v1/dataset/profile` and receive an AI-generated profile — summary statistics, quality issues, and a ready-to-use `dataQuality` configuration with suggested regex and AI rules
- **AI error explanation** - When a pipeline job fails, the AI automatically analyzes the error and provides a plain-English explanation of the root cause and suggested fix
- **AI providers** - All AI features work with Anthropic Claude, OpenAI, or local models via Ollama
- **MCP server (AI agent integration)** - Built-in [MCP](https://modelcontextprotocol.io) server lets AI agents (Claude, Cursor, custom frameworks) natively interact with the pipeline — upload files, register datasets, monitor jobs, profile data, and manage configurations. Supports stdio and SSE transports
- **Multiple ingestion methods** - File upload API, MinIO bucket events, database polling, Kafka streaming
- **Data quality** - AI rules, regex column checks, JavaScript row rules, REST endpoint row rules, JSON/XML schema validation
- **Transformations** - AI transformations, deduplication, whitespace trimming, JavaScript row functions
- **Multiple destinations** - Write to MinIO (Parquet/ORC), PostgreSQL, MongoDB, Kafka, ActiveMQ, REST endpoints, Qdrant, Weaviate, Milvus, Chroma, or pgvector in parallel
- **Event notifications** - Subscribe to dataset processing events via ActiveMQ topics

## Architecture

The pipeline runs as a Spring Boot application backed by self-hosted open-source infrastructure:

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
