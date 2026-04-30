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
# kafka-producer is intentionally not seeded — the bundled Kafka service is
# now opt-in (see optional Kafka block in docker-compose.yml). Users who
# enable it can configure this secret via the Configuration tab or by hand:
#   vault kv put secret/oss/kafka-producer bootstrapServers=kafka:9092

# AI configuration — three independent, self-describing secrets.
# Each Vault secret carries provider/endpoint/model/apiKey/version inline so
# the resolver never derives the path from a YAML provider field.
#
# Provider selection:
#   1. AI_PROVIDER (anthropic|openai) is the explicit override — always wins.
#      Use this in .env to pin a choice when shell env vars (e.g. a global
#      OPENAI_API_KEY exported from your shell rc) would otherwise leak in
#      and silently flip the default on every rebuild.
#   2. If AI_PROVIDER is unset, fall back to "whichever key is present",
#      with OpenAI winning ties — preserves the historical default.
PROVIDER="${AI_PROVIDER:-}"
if [ -z "$PROVIDER" ]; then
  if [ -n "${OPENAI_API_KEY:-}" ]; then
    PROVIDER="openai"
  elif [ -n "${ANTHROPIC_API_KEY:-}" ]; then
    PROVIDER="anthropic"
  fi
fi

if [ "$PROVIDER" = "openai" ]; then
  if [ -z "${OPENAI_API_KEY:-}" ]; then
    echo "ERROR: AI_PROVIDER=openai but OPENAI_API_KEY is not set." >&2
    exit 1
  fi
  # OpenAI handles main AI, codegen, and embedding from a single key.
  vault kv put secret/oss/ai-primary \
    provider="openai" \
    endpoint="https://api.openai.com/v1/chat/completions" \
    model="${OPENAI_MODEL:-gpt-5.5}" \
    apiKey="${OPENAI_API_KEY}"
  vault kv put secret/oss/codegen \
    provider="openai" \
    endpoint="https://api.openai.com/v1/chat/completions" \
    model="${CODEGEN_MODEL:-gpt-5.5}" \
    apiKey="${OPENAI_API_KEY}"
  vault kv put secret/oss/embedding \
    provider="openai" \
    endpoint="https://api.openai.com/v1/embeddings" \
    model="text-embedding-3-small" \
    apiKey="${OPENAI_API_KEY}"
elif [ "$PROVIDER" = "anthropic" ]; then
  if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
    echo "ERROR: AI_PROVIDER=anthropic but ANTHROPIC_API_KEY is not set." >&2
    exit 1
  fi
  # Anthropic handles main AI and codegen. Anthropic has no embeddings API,
  # so embedding falls back to the bundled TEI sidecar serving bge-m3
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
    model="${CODEGEN_MODEL:-claude-opus-4-7}" \
    apiKey="${ANTHROPIC_API_KEY}" \
    version="2023-06-01"
  vault kv put secret/oss/embedding \
    provider="tei" \
    endpoint="http://tei:80/v1/embeddings" \
    model="BAAI/bge-m3" \
    apiKey=""
else
  echo "ERROR: No AI provider configured. Set AI_PROVIDER=(anthropic|openai), or set ANTHROPIC_API_KEY / OPENAI_API_KEY in .env." >&2
  exit 1
fi

# Vector store secrets.
# Only pgvector is seeded by default — it rides on the bundled Postgres so
# it's always available. The other vector stores (qdrant, weaviate, milvus,
# chroma) are opt-in via the optional service blocks in docker-compose.yml.
# Seeding their secrets unconditionally would make the Configuration tab's
# Service Health card show them as "Down" instead of "Not Configured" when
# the user hasn't enabled them. Users who turn on an optional vector store
# can write its secret via the Configuration tab or by hand:
#   vault kv put secret/oss/qdrant   host="host.docker.internal" port="6334" apiKey=""
#   vault kv put secret/oss/weaviate host="host.docker.internal" port="8079" apiKey=""
#   vault kv put secret/oss/milvus   host="host.docker.internal" port="19530" apiKey=""
#   vault kv put secret/oss/chroma   host="host.docker.internal" port="8000"
vault kv put secret/oss/pgvector jdbcUrl="jdbc:postgresql://postgres:5432/datris" username="postgres" password="postgres"

echo "Vault secrets seeded successfully."
