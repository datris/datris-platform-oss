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
vault kv put secret/oss/anthropic endpoint="https://api.anthropic.com/v1/messages" model="claude-sonnet-4-6" apiKey="${ANTHROPIC_API_KEY:-}"
vault kv put secret/oss/openai endpoint="https://api.openai.com/v1/chat/completions" model="gpt-4o" apiKey="${OPENAI_API_KEY:-}"

# Ollama (local model)
vault kv put secret/oss/ollama endpoint="http://host.docker.internal:11434/v1/chat/completions" model="${OLLAMA_MODEL:-qwen2.5:14b-instruct}" apiKey=""

# Codegen AI secret — stronger model for code-generation tasks (tap scripts, AI DQ,
# AI transformations, schema generation, NL→SQL). Reuses the same API key as the main
# AI provider. For Ollama, falls back to the same model as the main config to avoid
# requiring extra RAM. Override the model with CODEGEN_MODEL.
case "${AI_PROVIDER:-anthropic}" in
  openai)
    vault kv put secret/oss/codegen endpoint="https://api.openai.com/v1/chat/completions" model="${CODEGEN_MODEL:-gpt-5.4}" apiKey="${OPENAI_API_KEY:-}"
    ;;
  ollama)
    vault kv put secret/oss/codegen endpoint="http://host.docker.internal:11434/v1/chat/completions" model="${CODEGEN_MODEL:-${OLLAMA_MODEL:-qwen2.5:14b-instruct}}" apiKey=""
    ;;
  *)
    vault kv put secret/oss/codegen endpoint="https://api.anthropic.com/v1/messages" model="${CODEGEN_MODEL:-claude-opus-4-6}" apiKey="${ANTHROPIC_API_KEY:-}"
    ;;
esac

# Qdrant vector database
vault kv put secret/oss/qdrant host="host.docker.internal" port="6334" apiKey=""

# Weaviate vector database
vault kv put secret/oss/weaviate host="host.docker.internal" port="8079" apiKey=""

# pgvector (PostgreSQL with vector extension)
vault kv put secret/oss/pgvector jdbcUrl="jdbc:postgresql://postgres:5432/datris" username="postgres" password="postgres"

# Milvus vector database
vault kv put secret/oss/milvus host="host.docker.internal" port="19530" apiKey=""

# Chroma vector database
vault kv put secret/oss/chroma host="host.docker.internal" port="8000"
vault kv put secret/oss/embedding endpoint="https://api.openai.com/v1/embeddings" model="text-embedding-3-small" apiKey="${OPENAI_API_KEY:-}"
echo "Vault secrets seeded successfully."
