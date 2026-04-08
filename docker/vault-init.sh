#!/bin/sh
set -e

echo "Waiting for Vault to be ready..."
until vault status > /dev/null 2>&1; do
  sleep 1
done

echo "Seeding Vault secrets..."

vault kv put secret/oss/minio accessKey=minioadmin secretKey=minioadmin
vault kv put secret/oss/activemq username=admin password=admin
vault kv put secret/oss/mongodb connectionString=mongodb://mongodb:27017 database=oss
vault kv put secret/oss/api-keys key=default-api-key
vault kv put secret/oss/postgres jdbcUrl=jdbc:postgresql://postgres:5432 username=postgres password=postgres
vault kv put secret/oss/kafka-producer bootstrapServers=kafka:9092

# AI configuration — three independent, self-describing secrets.
# Branch on which key the user supplied in .env. Each Vault secret carries
# provider/endpoint/model/apiKey/version inline so the resolver never derives
# the path from a YAML provider field.
if [ -n "${OPENAI_API_KEY:-}" ]; then
  # OpenAI handles main AI, codegen, and embedding from a single key.
  vault kv put secret/oss/ai-primary \
    provider="openai" \
    endpoint="https://api.openai.com/v1/chat/completions" \
    model="${OPENAI_MODEL:-gpt-5.4}" \
    apiKey="${OPENAI_API_KEY}"
  vault kv put secret/oss/codegen \
    provider="openai" \
    endpoint="https://api.openai.com/v1/chat/completions" \
    model="${CODEGEN_MODEL:-gpt-5.3-codex}" \
    apiKey="${OPENAI_API_KEY}"
  vault kv put secret/oss/embedding \
    provider="openai" \
    endpoint="https://api.openai.com/v1/embeddings" \
    model="text-embedding-3-small" \
    apiKey="${OPENAI_API_KEY}"
elif [ -n "${ANTHROPIC_API_KEY:-}" ]; then
  # Anthropic handles main AI and codegen. Anthropic has no embeddings API,
  # so embedding falls back to the local ollama sidecar serving bge-m3
  # (1024-dim, strong open-source embedding model). No OpenAI key needed.
  vault kv put secret/oss/ai-primary \
    provider="anthropic" \
    endpoint="https://api.anthropic.com/v1/messages" \
    model="${ANTHROPIC_MODEL:-claude-sonnet-4-6}" \
    apiKey="${ANTHROPIC_API_KEY}" \
    version="2023-06-01"
  vault kv put secret/oss/codegen \
    provider="anthropic" \
    endpoint="https://api.anthropic.com/v1/messages" \
    model="${CODEGEN_MODEL:-claude-opus-4-6}" \
    apiKey="${ANTHROPIC_API_KEY}" \
    version="2023-06-01"
  vault kv put secret/oss/embedding \
    provider="ollama" \
    endpoint="http://ollama:11434/v1/embeddings" \
    model="bge-m3" \
    apiKey=""
else
  echo "ERROR: No AI API key found. Set ANTHROPIC_API_KEY or OPENAI_API_KEY in .env." >&2
  exit 1
fi

# Vector store secrets
vault kv put secret/oss/qdrant host="host.docker.internal" port="6334" apiKey=""
vault kv put secret/oss/weaviate host="host.docker.internal" port="8079" apiKey=""
vault kv put secret/oss/pgvector jdbcUrl="jdbc:postgresql://postgres:5432/datris" username="postgres" password="postgres"
vault kv put secret/oss/milvus host="host.docker.internal" port="19530" apiKey=""
vault kv put secret/oss/chroma host="host.docker.internal" port="8000"

echo "Vault secrets seeded successfully."
