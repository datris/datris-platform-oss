# Installation

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/install/)

## Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/datris/datris-platform-oss.git
cd datris-platform-oss
```

### 2. Set your API keys

```bash
cp .env.example .env
```

Edit `.env` and add your API keys (at least one required for AI features):

```
ANTHROPIC_API_KEY=sk-ant-...
OPENAI_API_KEY=sk-proj-...
```

### 3. Start all services

```bash
docker compose up -d
```

Docker Compose pulls the pre-built images from Docker Hub and starts the full stack. On first run, `vault-init` automatically seeds your API keys into Vault.

### 4. Verify

```bash
curl http://localhost:8080/api/v1/version
```

That's it. The platform is running.

## Upgrading

If you already have Datris installed and want to upgrade to the latest version:

```bash
cd datris-platform-oss
git pull origin main
docker compose pull
docker compose up -d
```

This pulls the latest pre-built images from Docker Hub and restarts the services. No build tools required.

## Services

| Service | Port | Purpose |
|---------|------|---------|
| Pipeline Server | 8080 | REST API and data processing |
| Pipeline UI | 4200 | Web dashboard |
| MCP Server | 3000 | AI agent integration (MCP protocol) |
| MinIO | 9000 (API), 9001 (Console) | Object storage |
| MongoDB | 27017 | Configuration and status store |
| ActiveMQ | 61616 (broker), 8161 (console) | Message queue and notifications |
| Vault | 8200 | Secrets management |
| Kafka | 9092 | Streaming (optional) |
| Kafka UI | 8085 | Kafka topic browser |
| PostgreSQL | 5432 | Database destination + pgvector |
| Zookeeper | 2181 | Kafka coordination |

## Web UIs

| UI | URL | Credentials |
|----|-----|-------------|
| **Pipeline UI** | [http://localhost:4200](http://localhost:4200) | none |
| **Pipeline API** | [http://localhost:8080](http://localhost:8080) | none |
| **MCP Server (SSE)** | [http://localhost:3000/sse](http://localhost:3000/sse) | none |
| **MinIO Console** | [http://localhost:9001](http://localhost:9001) | `minioadmin` / `minioadmin` |
| **ActiveMQ Console** | [http://localhost:8161](http://localhost:8161) | `admin` / `admin` |
| **Kafka UI** | [http://localhost:8085](http://localhost:8085) | none |
| **Vault UI** | [http://localhost:8200](http://localhost:8200) | Token: `root-token` |

## API Keys and AI Providers

Datris supports three AI providers. Set your keys in `.env`:

| Provider | Environment Variable | Used For |
|----------|---------------------|----------|
| **Anthropic Claude** | `ANTHROPIC_API_KEY` | AI data quality, transformations, error explanation, schema generation, profiling |
| **OpenAI** | `OPENAI_API_KEY` | Same as above, plus embeddings for vector database / RAG |
| **Ollama** (local) | `OLLAMA_MODEL` | Same as above — no API key needed, runs locally |

At least one AI provider key is required for AI features. The embedding provider for RAG defaults to OpenAI but can be changed via `EMBEDDING_PROVIDER` in `.env`.

## Infrastructure Details

### MinIO

The `minio-init` container automatically creates the required buckets:

- `oss-raw` - File upload staging
- `oss-raw-plus` - Processed file staging
- `oss-temp` - Temporary processing files
- `oss-data` - Pipeline output (object store destination)
- `oss-config` - Configuration files (JavaScript scripts, validation schemas)

### Vault

The `vault-init` container seeds Vault with default secrets for all services (MinIO, ActiveMQ, MongoDB, PostgreSQL, Kafka) plus your AI provider API keys from `.env`. Vault runs in dev mode with root token `root-token`.

### Vector Databases

pgvector is included by default via the PostgreSQL service. To add other vector databases, uncomment the relevant sections in `docker-compose.yml`:

- **Qdrant** — high-performance vector database
- **Weaviate** — open-source vector database
- **Chroma** — lightweight, single container
- **Milvus** — scalable vector database (requires separate setup)

## Configuration

The pipeline server reads configuration from `application.yaml`, mounted from `docker/config/application.yaml`.

See [Configuration Reference](configuration-reference.md) for the full list of properties.

## Building from Source

For development or contributing:

### Prerequisites

- Java 17+
- [SBT](https://www.scala-sbt.org/download.html)

### Build and run

```bash
# Build the server JAR
sbt clean assembly

# Start with local builds (edit docker-compose.yml to uncomment build: lines)
docker compose up --build
```

In `docker-compose.yml`, uncomment the `build:` lines and comment out the `image:` lines for the services you want to build locally:

```yaml
datris:
  # image: datris/datris-server:latest
  build: .  # Build from source
```
