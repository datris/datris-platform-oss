#!/bin/sh
set -e

echo "Waiting for Vault to be ready..."
until vault status > /dev/null 2>&1; do
  sleep 1
done

# Create-if-absent seeding. With persistent (non-dev) Vault, secrets survive
# restarts, so seeding must NOT overwrite a path that already holds a value —
# otherwise the .env defaults would clobber the user's persisted UI changes on
# every reboot. Each path is seeded only when it does not yet exist: on a fresh
# volume that's every path, on a reboot that's none.
seed_if_absent() {
  _path="$1"
  shift
  if vault kv get "$_path" > /dev/null 2>&1; then
    echo "  $_path already present — keeping persisted value"
    return 0
  fi
  echo "  seeding $_path"
  vault kv put "$_path" "$@"
}

# Does a path already hold a value? (Used to skip whole blocks whose seeding
# would otherwise run provider validation that can fail on reboot when the
# operator has since removed keys from .env — .env is first-boot seed only.)
exists() {
  vault kv get "$1" > /dev/null 2>&1
}

echo "Seeding Vault secrets (create-if-absent)..."

seed_if_absent secret/oss/minio accessKey=minioadmin secretKey=minioadmin
seed_if_absent secret/oss/activemq username=admin password=admin
seed_if_absent secret/oss/mongodb connectionString=mongodb://mongodb:27017 database=oss
# The UI API key — operator-supplied via DATRIS_UI_API_KEY in .env, or a
# stable fallback for fresh local installs. Seeded in two places so the
# auth layer recognizes it AND the operator can rotate it from the UI:
#   - oss/api-keys[ui] is the validation source (APIKeyValidator looks here)
#   - oss/ui-api-key is the operator-facing record (rotatable in Secrets UI;
#     on save, the server auto-mirrors the new value into oss/api-keys[ui])
UI_API_KEY_VALUE="${DATRIS_UI_API_KEY:-default-ui-key}"
seed_if_absent secret/oss/api-keys ui="${UI_API_KEY_VALUE}"
seed_if_absent secret/oss/ui-api-key apiKey="${UI_API_KEY_VALUE}"
# Postgres: env vars (installer-written, for an external Postgres) fall back
# to the bundled container's coordinates — absent vars reproduce today's seed.
# POSTGRES_JDBC_URL is the BASE url (no database segment), matching the
# bundled format; the destination database is a per-pipeline/config setting.
seed_if_absent secret/oss/postgres \
  jdbcUrl="${POSTGRES_JDBC_URL:-jdbc:postgresql://postgres:5432}" \
  username="${POSTGRES_USER:-postgres}" \
  password="${POSTGRES_PASSWORD:-postgres}"

# kafka-producer: seeded only when a broker list is supplied (installer or
# operator) — bundled test broker (kafka:9092 with COMPOSE_PROFILES=kafka) or
# an external one. Unset → not seeded, exactly like before, so the health
# card shows "Not Configured" rather than "Down". Manual alternative:
#   vault kv put secret/oss/kafka-producer bootstrapServers=kafka:9092
if [ -n "${KAFKA_BOOTSTRAP_SERVERS:-}" ]; then
  seed_if_absent secret/oss/kafka-producer bootstrapServers="${KAFKA_BOOTSTRAP_SERVERS}"
fi

# Snowflake / Databricks destination credentials — seeded as ordinary
# (non-tap) secrets, so they appear as human-owned Platform secrets that
# pipelines reference via credentialsSecret (agents can discover but never
# modify them). Seeded only when the anchor field is present; field names
# match CredentialResolver's canonical spellings. Warehouse/catalog/database
# are per-pipeline settings and deliberately NOT stored here.
if [ -n "${SNOWFLAKE_ACCOUNT:-}" ]; then
  seed_if_absent secret/oss/snowflake \
    account="${SNOWFLAKE_ACCOUNT}" \
    user="${SNOWFLAKE_USER:-}" \
    privateKey="${SNOWFLAKE_PRIVATE_KEY:-}" \
    password="${SNOWFLAKE_PASSWORD:-}"
fi
if [ -n "${DATABRICKS_HOST:-}" ]; then
  seed_if_absent secret/oss/databricks \
    host="${DATABRICKS_HOST}" \
    clientId="${DATABRICKS_CLIENT_ID:-}" \
    clientSecret="${DATABRICKS_CLIENT_SECRET:-}" \
    token="${DATABRICKS_TOKEN:-}"
fi

# AI configuration — three independent, self-describing secrets.
# Each Vault secret carries provider/endpoint/model/apiKey/version inline so
# the resolver never derives the path from a YAML provider field.
#
# Azure endpoints embed the customer's resource name. AZURE_OPENAI_ENDPOINT
# is the resource base URL (https://YOUR-RESOURCE.openai.azure.com); the chat
# and embeddings paths are derived from it. Defined here (not inside the AI
# block) because the embedding block below also needs it when the AI block is
# skipped on reboot.
AZURE_BASE="${AZURE_OPENAI_ENDPOINT%/}"

# Skip the whole block once ai-primary AND codegen exist: with persistence
# these survive reboots, and re-running the provider resolution below would
# fail when the operator has removed keys from .env (now first-boot seed only).
if exists secret/oss/ai-primary && exists secret/oss/codegen; then
  echo "  secret/oss/ai-primary + secret/oss/codegen already present — skipping AI seed"
else
  # Provider selection:
  #   1. AI_PROVIDER (anthropic|openai|azure|bedrock|grok) is the explicit override —
  #      always wins. Use this in .env to pin a choice when shell env vars (e.g.
  #      a global OPENAI_API_KEY exported from your shell rc) would otherwise
  #      leak in and silently flip the default on every rebuild.
  #   2. If AI_PROVIDER is unset, fall back to "whichever key is present",
  #      with OpenAI winning ties — preserves the historical default.
  #      Bedrock is EXPLICIT-ONLY: AWS_ACCESS_KEY_ID presence never implies it,
  #      because AWS credentials are routinely set for S3 destinations.
  PROVIDER="${AI_PROVIDER:-}"
  if [ -z "$PROVIDER" ]; then
    if [ -n "${OPENAI_API_KEY:-}" ]; then
      PROVIDER="openai"
    elif [ -n "${ANTHROPIC_API_KEY:-}" ]; then
      PROVIDER="anthropic"
    elif [ -n "${AZURE_OPENAI_API_KEY:-}" ]; then
      PROVIDER="azure"
    elif [ -n "${XAI_API_KEY:-}" ]; then
      PROVIDER="grok"
    fi
  fi

  if [ "$PROVIDER" = "bedrock" ]; then
    # No API key and no static endpoint: AWS credentials resolve at request
    # time on the server (AWS_* env vars, or the IAM role / default credential
    # chain), and the invoke URL derives from the region + model. A blank
    # endpoint in the slot secret is the "derive it" signal.
    if [ -n "${AWS_ACCESS_KEY_ID:-}" ] && [ -z "${AWS_SECRET_ACCESS_KEY:-}" ]; then
      echo "ERROR: AI_PROVIDER=bedrock with AWS_ACCESS_KEY_ID set requires AWS_SECRET_ACCESS_KEY too." >&2
      exit 1
    fi
    if [ -z "${AWS_ACCESS_KEY_ID:-}" ]; then
      echo "  bedrock: no AWS_ACCESS_KEY_ID in .env — the server will use its IAM role / default AWS credential chain"
    fi
    if [ -z "${AWS_REGION:-}${AWS_DEFAULT_REGION:-}" ]; then
      echo "  bedrock: no AWS_REGION in .env — the server will resolve the region from the default chain (set one if the first AI call fails)"
    fi
    seed_if_absent secret/oss/ai-primary \
      provider="bedrock" \
      endpoint="" \
      model="${BEDROCK_MODEL:-anthropic.claude-fable-5}" \
      apiKey=""
    seed_if_absent secret/oss/codegen \
      provider="bedrock" \
      endpoint="" \
      model="${CODEGEN_MODEL:-anthropic.claude-opus-5}" \
      apiKey=""
  elif [ "$PROVIDER" = "azure" ]; then
    # Keyless (Entra ID) azure is allowed with an explicit AI_PROVIDER=azure:
    # a blank apiKey in the slot secret makes the server resolve Entra
    # credentials at request time (AZURE_TENANT_ID/AZURE_CLIENT_ID/
    # AZURE_CLIENT_SECRET service principal, or managed identity on Azure
    # compute). Guard the half-configured SP trio; require the key only when
    # azure was auto-picked by key presence (then it can't be blank anyway).
    if [ -z "${AZURE_OPENAI_API_KEY:-}" ]; then
      SP_SET=0
      [ -n "${AZURE_TENANT_ID:-}" ] && SP_SET=$((SP_SET + 1))
      [ -n "${AZURE_CLIENT_ID:-}" ] && SP_SET=$((SP_SET + 1))
      [ -n "${AZURE_CLIENT_SECRET:-}" ] && SP_SET=$((SP_SET + 1))
      if [ "$SP_SET" -gt 0 ] && [ "$SP_SET" -lt 3 ]; then
        echo "ERROR: AI_PROVIDER=azure keyless auth needs all three of AZURE_TENANT_ID, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET (or none, for managed identity on Azure compute)." >&2
        exit 1
      fi
      if [ "$SP_SET" -eq 3 ]; then
        echo "  azure: no AZURE_OPENAI_API_KEY in .env — using the Entra ID service principal (AZURE_TENANT_ID/AZURE_CLIENT_ID/AZURE_CLIENT_SECRET)"
      else
        echo "  azure: no AZURE_OPENAI_API_KEY and no Entra service principal in .env — the server will use its Azure managed identity (requires Azure compute + the 'Cognitive Services OpenAI User' role)"
      fi
    fi
    if [ -z "$AZURE_BASE" ]; then
      echo "ERROR: AI_PROVIDER=azure requires AZURE_OPENAI_ENDPOINT (e.g. https://YOUR-RESOURCE.openai.azure.com)." >&2
      exit 1
    fi
    if [ -z "${AZURE_OPENAI_MODEL:-}" ]; then
      echo "ERROR: AI_PROVIDER=azure requires AZURE_OPENAI_MODEL (your chat deployment name)." >&2
      exit 1
    fi
    seed_if_absent secret/oss/ai-primary \
      provider="azure" \
      endpoint="${AZURE_BASE}/openai/v1/chat/completions" \
      model="${AZURE_OPENAI_MODEL}" \
      apiKey="${AZURE_OPENAI_API_KEY:-}"
    seed_if_absent secret/oss/codegen \
      provider="azure" \
      endpoint="${AZURE_BASE}/openai/v1/chat/completions" \
      model="${CODEGEN_MODEL:-${AZURE_OPENAI_MODEL}}" \
      apiKey="${AZURE_OPENAI_API_KEY:-}"
  elif [ "$PROVIDER" = "openai" ]; then
    if [ -z "${OPENAI_API_KEY:-}" ]; then
      echo "ERROR: AI_PROVIDER=openai but OPENAI_API_KEY is not set." >&2
      exit 1
    fi
    seed_if_absent secret/oss/ai-primary \
      provider="openai" \
      endpoint="https://api.openai.com/v1/chat/completions" \
      model="${OPENAI_MODEL:-gpt-5.5}" \
      apiKey="${OPENAI_API_KEY}"
    seed_if_absent secret/oss/codegen \
      provider="openai" \
      endpoint="https://api.openai.com/v1/chat/completions" \
      model="${CODEGEN_MODEL:-gpt-5.5}" \
      apiKey="${OPENAI_API_KEY}"
  elif [ "$PROVIDER" = "grok" ]; then
    if [ -z "${XAI_API_KEY:-}" ]; then
      echo "ERROR: AI_PROVIDER=grok but XAI_API_KEY is not set." >&2
      exit 1
    fi
    seed_if_absent secret/oss/ai-primary \
      provider="grok" \
      endpoint="https://api.x.ai/v1/chat/completions" \
      model="${GROK_MODEL:-grok-4.6}" \
      apiKey="${XAI_API_KEY}"
    seed_if_absent secret/oss/codegen \
      provider="grok" \
      endpoint="https://api.x.ai/v1/chat/completions" \
      model="${CODEGEN_MODEL:-grok-4.6}" \
      apiKey="${XAI_API_KEY}"
  elif [ "$PROVIDER" = "anthropic" ]; then
    if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
      echo "ERROR: AI_PROVIDER=anthropic but ANTHROPIC_API_KEY is not set." >&2
      exit 1
    fi
    # Fresh installs default ai-primary to Opus: strongest chat/NL→SQL model,
    # matching the codegen default below (decided 2026-07-14). Existing vaults
    # keep whatever they have (seed_if_absent); ANTHROPIC_MODEL still overrides.
    # Sustained-overload downgrades are handled at runtime via
    # ANTHROPIC_OVERLOAD_FALLBACK_MODEL.
    seed_if_absent secret/oss/ai-primary \
      provider="anthropic" \
      endpoint="https://api.anthropic.com/v1/messages" \
      model="${ANTHROPIC_MODEL:-claude-opus-5}" \
      apiKey="${ANTHROPIC_API_KEY}" \
      version="2023-06-01"
    seed_if_absent secret/oss/codegen \
      provider="anthropic" \
      endpoint="https://api.anthropic.com/v1/messages" \
      model="${CODEGEN_MODEL:-claude-opus-5}" \
      apiKey="${ANTHROPIC_API_KEY}" \
      version="2023-06-01"
  else
    echo "ERROR: No AI provider configured. Set AI_PROVIDER=(anthropic|openai|azure|bedrock|grok), or set ANTHROPIC_API_KEY / OPENAI_API_KEY / AZURE_OPENAI_API_KEY / XAI_API_KEY in .env. (Bedrock is explicit-only: AI_PROVIDER=bedrock.)" >&2
    exit 1
  fi
fi

# Embedding slot — decoupled from AI_PROVIDER so users can mix-and-match
# (e.g. Anthropic for chat/codegen + OpenAI for embedding when bundled TEI
# is unreliable on a small host).
#
# Skip once present (same first-boot-seed-only reasoning as the AI block).
if exists secret/oss/embedding; then
  echo "  secret/oss/embedding already present — skipping embedding seed"
else
  # Selection precedence:
  #   1. EMBEDDING_PROVIDER (openai|azure|tei|ollama) — explicit override.
  #   2. Otherwise: AI_PROVIDER=openai → OpenAI embeddings;
  #                 anything else (anthropic, azure, bedrock, grok) → bundled TEI (bge-m3,
  #                 1024-dim). Azure embeddings are opt-in only, because they
  #                 require an embedding deployment that may not exist on the
  #                 customer's resource.
  EMBEDDING_PROVIDER_RESOLVED="${EMBEDDING_PROVIDER:-}"
  if [ -z "$EMBEDDING_PROVIDER_RESOLVED" ]; then
    if [ "${PROVIDER:-}" = "openai" ]; then
      EMBEDDING_PROVIDER_RESOLVED="openai"
    else
      EMBEDDING_PROVIDER_RESOLVED="tei"
    fi
  fi

  if [ "$EMBEDDING_PROVIDER_RESOLVED" = "openai" ]; then
    if [ -z "${OPENAI_API_KEY:-}" ]; then
      echo "ERROR: EMBEDDING_PROVIDER=openai but OPENAI_API_KEY is not set." >&2
      exit 1
    fi
    seed_if_absent secret/oss/embedding \
      provider="openai" \
      endpoint="https://api.openai.com/v1/embeddings" \
      model="${EMBEDDING_MODEL:-text-embedding-3-small}" \
      apiKey="${OPENAI_API_KEY}"
  elif [ "$EMBEDDING_PROVIDER_RESOLVED" = "azure" ]; then
    if [ -z "${AZURE_OPENAI_API_KEY:-}" ]; then
      echo "ERROR: EMBEDDING_PROVIDER=azure but AZURE_OPENAI_API_KEY is not set." >&2
      exit 1
    fi
    if [ -z "${EMBEDDING_ENDPOINT:-}" ] && [ -z "$AZURE_BASE" ]; then
      echo "ERROR: EMBEDDING_PROVIDER=azure requires AZURE_OPENAI_ENDPOINT or EMBEDDING_ENDPOINT." >&2
      exit 1
    fi
    # EMBEDDING_MODEL must match your embedding deployment's name; the default
    # matches the Azure portal's default deployment name for this model.
    seed_if_absent secret/oss/embedding \
      provider="azure" \
      endpoint="${EMBEDDING_ENDPOINT:-${AZURE_BASE}/openai/v1/embeddings}" \
      model="${EMBEDDING_MODEL:-text-embedding-3-small}" \
      apiKey="${AZURE_OPENAI_API_KEY:-}"
  elif [ "$EMBEDDING_PROVIDER_RESOLVED" = "tei" ]; then
    seed_if_absent secret/oss/embedding \
      provider="tei" \
      endpoint="${EMBEDDING_ENDPOINT:-http://tei:80/v1/embeddings}" \
      model="${EMBEDDING_MODEL:-BAAI/bge-m3}" \
      apiKey=""
  elif [ "$EMBEDDING_PROVIDER_RESOLVED" = "ollama" ]; then
    seed_if_absent secret/oss/embedding \
      provider="ollama" \
      endpoint="${EMBEDDING_ENDPOINT:-http://ollama:11434/v1/embeddings}" \
      model="${EMBEDDING_MODEL:-bge-m3}" \
      apiKey=""
  else
    echo "ERROR: Unknown EMBEDDING_PROVIDER='$EMBEDDING_PROVIDER_RESOLVED'. Expected: openai, azure, tei, or ollama." >&2
    exit 1
  fi
fi

# Vector store secrets.
# pgvector is seeded by default — it rides on the (bundled or external)
# Postgres so it's available whenever Postgres is. The other vector stores
# (qdrant, weaviate, milvus, chroma) are seeded ONLY when their HOST var is
# set (installer-written: the compose service name for a bundled profile
# service, or a real hostname for a managed/cloud instance). Seeding them
# unconditionally would make the Configuration tab's Service Health card
# show them as "Down" instead of "Not Configured" when the user hasn't
# enabled them. Manual alternative stays available:
#   vault kv put secret/oss/qdrant host="host.docker.internal" port="6334" apiKey=""
seed_if_absent secret/oss/pgvector \
  jdbcUrl="${POSTGRES_JDBC_URL:-jdbc:postgresql://postgres:5432}/datris" \
  username="${POSTGRES_USER:-postgres}" \
  password="${POSTGRES_PASSWORD:-postgres}"
if [ -n "${QDRANT_HOST:-}" ]; then
  seed_if_absent secret/oss/qdrant \
    host="${QDRANT_HOST}" port="${QDRANT_PORT:-6334}" apiKey="${QDRANT_API_KEY:-}"
fi
if [ -n "${WEAVIATE_HOST:-}" ]; then
  seed_if_absent secret/oss/weaviate \
    host="${WEAVIATE_HOST}" port="${WEAVIATE_PORT:-8079}" apiKey="${WEAVIATE_API_KEY:-}"
fi
if [ -n "${MILVUS_HOST:-}" ]; then
  seed_if_absent secret/oss/milvus \
    host="${MILVUS_HOST}" port="${MILVUS_PORT:-19530}" apiKey="${MILVUS_API_KEY:-}"
fi
if [ -n "${CHROMA_HOST:-}" ]; then
  seed_if_absent secret/oss/chroma \
    host="${CHROMA_HOST}" port="${CHROMA_PORT:-8000}"
fi

echo "Vault secrets seeded successfully."
