# Installation

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/install/)
- [SBT](https://www.scala-sbt.org/download.html) (for building from source)
- Java 17+

## Docker Compose (Recommended)

The fastest way to get the full stack running:

### 1. Build the application

```bash
sbt clean assembly
```

### 2. Start all services

```bash
docker-compose up --build
```

This starts the following services:

| Service | Port | Purpose |
|---------|------|---------|
| Pipeline Server | 8080 | REST API and data processing |
| Pipeline UI | 4200 | Web dashboard |
| MinIO | 9000 (API), 9001 (Console) | Object storage |
| MongoDB | 27017 | Configuration and status store |
| ActiveMQ | 61616 (broker), 8161 (console) | Message queue and notifications |
| Vault | 8200 | Secrets management |
| Kafka | 9092 | Streaming (optional) |
| Kafka UI | 8085 | Kafka topic browser |
| MCP Server | 3000 | AI agent integration (MCP protocol) |
| Zookeeper | 2181 | Kafka coordination |

### 3. Verify

Once started, check the server is running:

```bash
curl http://localhost:8080/api/v1/version
```

Expected response:
```json
{"version": "latest-version-here"}
```

## Web UIs

Once the stack is running, the following web interfaces are available:

| UI | URL | Credentials |
|----|-----|-------------|
| **Pipeline UI** | [http://localhost:4200](http://localhost:4200) | none |
| **Pipeline API** | [http://localhost:8080](http://localhost:8080) | none |
| **MinIO Console** | [http://localhost:9001](http://localhost:9001) | `minioadmin` / `minioadmin` |
| **ActiveMQ Console** | [http://localhost:8161](http://localhost:8161) | `admin` / `admin` |
| **Kafka UI** | [http://localhost:8085](http://localhost:8085) | none |
| **Vault UI** | [http://localhost:8200](http://localhost:8200) | Token: `root-token` |
| **MCP Server (SSE)** | [http://localhost:3000/sse](http://localhost:3000/sse) | none |

## Infrastructure Details

### MinIO

The `minio-init` container automatically creates the required buckets:

- `oss-raw` - File upload staging
- `oss-raw-plus` - Processed file staging
- `oss-temp` - Temporary processing files
- `oss-data` - Dataset output (object store destination)
- `oss-config` - Configuration files (JavaScript scripts, validation schemas)

Access the MinIO console at http://localhost:9001 (credentials: `minioadmin`/`minioadmin`).

### Vault

The `vault-init` container seeds Vault with default secrets for all services (MinIO, ActiveMQ, MongoDB, PostgreSQL, Kafka). Vault runs in dev mode with root token `root-token`.

### ActiveMQ

Access the ActiveMQ web console at http://localhost:8161 (credentials: `admin`/`admin`).

## Configuration

The pipeline server reads configuration from `application.yaml`. For Docker, the config is mounted from `docker/config/application.yaml`.

Key configuration sections:

```yaml
# Environment name - used as prefix for bucket and table names
environment: oss

# MinIO connection
minio:
  enabled: "true"
  server: http://minio:9000

# Secrets from Vault
secrets:
  minIOSecretName: oss/minio
  activeMQSecretName: oss/activemq
  mongoDbSecretName: oss/mongodb
  postgresSecretName: oss/postgres

# ActiveMQ connection
activemq:
  enabled: "true"
  server: tcp://activemq:61616

# MongoDB connection
mongodb:
  enabled: "true"
  connectionString: mongodb://mongodb:27017
  database: oss
```

See [Configuration Reference](configuration-reference.md) for the full list of properties.

## Local Development

To run the server outside Docker (e.g., for development), use the local config:

```bash
sbt "datrisserver/run"
```

Ensure MinIO, MongoDB, ActiveMQ, and Vault are running locally or update `datrisserver/src/main/resources/application.yaml` with the correct endpoints.
