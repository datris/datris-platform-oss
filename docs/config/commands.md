# Docker Commands Reference

## Building

```bash
# Build the assembly jar (required before Docker build)
sbt clean assembly

# Build and start all services
docker-compose up --build

# Rebuild without cache (use when Docker layers are stale)
docker-compose up --build --no-cache
```

## Starting and Stopping

```bash
# Start all services in the foreground
docker-compose up

# Start all services in the background
docker-compose up -d

# Stop all services
docker-compose down

# Stop a single service (keeps others running)
docker-compose stop datris

# Start a previously stopped service
docker-compose start datris

# Restart a single service
docker-compose restart datris
```

## Logs

```bash
# Follow logs from all services
docker-compose logs -f

# Follow logs from the pipeline server only
docker-compose logs -f datris

# Follow logs from multiple services
docker-compose logs -f datris mongodb activemq

# Show last 100 lines and follow
docker-compose logs -f --tail 100 pipeline
```

## Vault Secrets — Switching Between Local Dev and Docker

Several services (MongoDB, PostgreSQL, Kafka) connect using hostnames stored in Vault secrets. The hostnames differ depending on whether the pipeline is running locally in VSCode or fully inside Docker. Run the appropriate set of commands and restart the pipeline to switch modes.

### VSCode (pipeline running locally)

```bash
docker-compose exec -e VAULT_ADDR=http://vault:8200 -e VAULT_TOKEN=root-token vault \
  vault kv put secret/oss/mongodb connectionString=mongodb://localhost:27017 database=oss

docker-compose exec -e VAULT_ADDR=http://vault:8200 -e VAULT_TOKEN=root-token vault \
  vault kv put secret/oss/postgres jdbcUrl=jdbc:postgresql://localhost:5432 username=postgres password=postgres

docker-compose exec -e VAULT_ADDR=http://vault:8200 -e VAULT_TOKEN=root-token vault \
  vault kv put secret/oss/kafka-producer bootstrapServers=localhost:9092
```

### Docker (all services in containers)

```bash
docker-compose exec -e VAULT_ADDR=http://vault:8200 -e VAULT_TOKEN=root-token vault \
  vault kv put secret/oss/mongodb connectionString=mongodb://mongodb:27017 database=oss

docker-compose exec -e VAULT_ADDR=http://vault:8200 -e VAULT_TOKEN=root-token vault \
  vault kv put secret/oss/postgres jdbcUrl=jdbc:postgresql://postgres:5432 username=postgres password=postgres

docker-compose exec -e VAULT_ADDR=http://vault:8200 -e VAULT_TOKEN=root-token vault \
  vault kv put secret/oss/kafka-producer bootstrapServers=kafka:9092
```

## MongoDB — Clearing Status Data

```bash
# Clear all dataset status records
docker-compose exec mongodb mongosh oss --eval "db['oss-dataset-status'].deleteMany({})"

# Clear all dataset status summary records
docker-compose exec mongodb mongosh oss --eval "db['oss-dataset-status-summary'].deleteMany({})"
```

## AI Configuration

The Anthropic API key is stored in a `.env` file at the project root (gitignored). Create it once:

```bash
echo 'ANTHROPIC_API_KEY=your-key-here' > .env
```

`docker-compose up --build` will automatically seed Vault with the key on every startup. No manual step required.

## Init Containers

```bash
# Re-run MinIO bucket initialization
docker-compose run --rm minio-init

# Re-run Vault secret seeding
docker-compose run --rm vault-init
```

## Debugging

```bash
# Run infrastructure only (no pipeline server) for VS Code debugging
docker-compose up -d
docker-compose stop datris

# Check service status
docker-compose ps

# Open a shell inside the pipeline container
docker exec -it datris /bin/bash

# Inspect container environment variables
docker exec datris env
```

## Git Configuration for Large Pushes

```bash
# Increase HTTP buffer if git push fails with HTTP 400
git config http.postBuffer 524288000
```

## Ollama EC2 Server

The pipeline can use a remote Ollama instance running on an AWS EC2 GPU instance. See `docs/config/ec2-ollama.md` (gitignored) for instance-specific commands.

## Vector Databases

Vector databases run externally (not in docker-compose). pgvector is the exception — it's built into the PostgreSQL container.

### Qdrant

```bash
# Start
docker run -d --name qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant

# Stop
docker rm -f qdrant

# Verify
curl http://localhost:6333/collections
```

### Weaviate

```bash
# Start (port 8079 to avoid conflict with Datris on 8080)
docker run -d --name weaviate -p 8079:8080 -p 50051:50051 cr.weaviate.io/semitechnologies/weaviate:latest

# Stop
docker rm -f weaviate

# Verify
curl http://localhost:8079/v1/meta
```

### Milvus

```bash
# Start (requires standalone embed script — Milvus needs etcd + MinIO internally)
curl -sfL https://raw.githubusercontent.com/milvus-io/milvus/master/scripts/standalone_embed.sh -o standalone_embed.sh
bash standalone_embed.sh start

# Stop
bash standalone_embed.sh stop

# Verify
curl http://localhost:9091/v1/vector/collections
```

### Chroma

```bash
# Start
docker run -d --name chroma -p 8000:8000 chromadb/chroma:latest

# Stop
docker rm -f chroma

# Verify
curl http://localhost:8000/api/v2/heartbeat
```

### pgvector

pgvector is already included in the Docker Compose stack (uses `pgvector/pgvector:pg16` image). No separate startup needed.

```bash
# Verify pgvector extension is available
docker-compose exec postgres psql -U postgres -d idata -c "CREATE EXTENSION IF NOT EXISTS vector; SELECT extname FROM pg_extension WHERE extname = 'vector';"
```

## Service Ports

| Service    | Port  | Purpose                    |
|------------|-------|----------------------------|
| Datris     | 8080  | REST API                   |
| MinIO      | 9000  | S3-compatible API          |
| MinIO      | 9001  | Web console                |
| ActiveMQ   | 61616 | OpenWire protocol          |
| ActiveMQ   | 8161  | Web console (admin/admin)  |
| MongoDB    | 27017 | Database                   |
| PostgreSQL | 5432  | Database                   |
| Vault      | 8200  | Secrets API                |
| Kafka      | 9092  | Broker                     |
| Zookeeper  | 2181  | Kafka coordination         |
| MCP Server | 3000  | AI agent integration (MCP/SSE)               |
| Qdrant     | 6333/6334 | Vector database (REST/gRPC)              |
| Weaviate   | 8079/50051 | Vector database (REST/gRPC)             |
| Milvus     | 19530/9091 | Vector database (gRPC/REST)             |
| Chroma     | 8000  | Vector database (REST)                       |
| Ollama     | 11434 | Local model API (runs on host, not in Docker) |
