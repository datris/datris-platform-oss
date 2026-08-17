#!/bin/sh
# Datris one-command local install — no git required.
#
#   curl -fsSL https://get.datris.ai/install.sh | sh
#
# Pulls pre-built images from Docker Hub (datrisai/*) and the few runtime
# files Compose needs from the public repo, drops them into ./datris, seeds a
# .env, and runs `docker compose up -d`. Nothing is built from source.
#
# Fresh installs walk through AI keys + database/store selection (pick and
# choose what to run; point at external services you already have). Re-running
# against an existing install is a prompt-free UPGRADE: the .env is left
# untouched and absent selection vars default to "enabled", so a service that
# was running can never be silently dropped.
#
# Honors these env vars for non-interactive / CI use:
#   DATRIS_DIR          install directory            (default: ./datris)
#   DATRIS_REF          repo ref to fetch files from (default: main)
#   ANTHROPIC_API_KEY   pre-set Anthropic key        (skips the prompt)
#   OPENAI_API_KEY      pre-set OpenAI key           (skips the prompt)
#   AZURE_OPENAI_API_KEY / AZURE_OPENAI_ENDPOINT / AZURE_OPENAI_MODEL
#                       pre-set Azure OpenAI trio (all three required together;
#                       endpoint is the resource base URL, model the chat
#                       deployment name)
#   AI_PROVIDER=bedrock pre-select Amazon Bedrock (Claude through your AWS
#                       account; explicit-only — AWS keys alone never imply it).
#                       Optionally with AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY/
#                       AWS_REGION (leave the keys unset to use the host's IAM
#                       role / default credential chain) and BEDROCK_MODEL
#                       (default: anthropic.claude-sonnet-5)
#   XAI_API_KEY         pre-set Grok (xAI) key       (skips the prompt;
#                       optionally with GROK_MODEL, default grok-4.6)
#   DATRIS_POSTGRES     bundled|external|none        (default: bundled)
#                       external also reads POSTGRES_JDBC_URL/POSTGRES_USER/POSTGRES_PASSWORD
#   DATRIS_EMBEDDING    openai|tei|none              (default: openai if OpenAI key present, else tei)
#   DATRIS_PROFILES     comma-separated opt-in services: qdrant,weaviate,chroma,kafka
#                       external vector stores: set QDRANT_HOST etc. instead of the profile
#   KAFKA_BOOTSTRAP_SERVERS, SNOWFLAKE_ACCOUNT/USER/PRIVATE_KEY/PASSWORD,
#   DATABRICKS_HOST/CLIENT_ID/CLIENT_SECRET/TOKEN — external store credentials
#   DATRIS_NO_START=1   write files but don't run compose
set -eu

REPO_RAW="https://raw.githubusercontent.com/datris/datris-platform-oss"
REF="${DATRIS_REF:-main}"
DIR="${DATRIS_DIR:-./datris}"

# Files Compose bind-mounts from the working dir. This is the exact set that
# makes a git-free checkout unnecessary — keep in sync with docker-compose.yml.
FILES="docker-compose.yml docker/vault-init.sh docker/minio-init.sh docker/vault-bootstrap.sh docker/vault.hcl docker/config/application.yaml"

say()  { printf '\033[36m%s\033[0m\n' "$*"; }
ok()   { printf '\033[32m%s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*"; }
die()  { printf '\033[31merror: %s\033[0m\n' "$*" >&2; exit 1; }

# Mask a secret for display: short head + tail, middle hidden.
mask() {
  v="$1"; n=${#v}
  [ "$n" -le 12 ] && { printf '****'; return; }
  printf '%s...%s' "$(printf '%s' "$v" | cut -c1-7)" "$(printf '%s' "$v" | cut -c"$((n-3))"-"$n")"
}

# --- preflight ------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "Docker is not installed. Get it at https://docs.docker.com/get-docker/"
if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE="docker-compose"
else
  die "Docker Compose v2 not found. Update Docker Desktop, or install the compose plugin."
fi
docker info >/dev/null 2>&1 || die "The Docker daemon isn't running. Start Docker Desktop (or dockerd) and re-run."
command -v curl >/dev/null 2>&1 || die "curl is required."

say "Installing Datris into $DIR (ref: $REF)"
mkdir -p "$DIR"

# --- container-name conflict preflight ---------------------------------------
# Every Datris service uses a fixed container_name, so a PREVIOUS install in a
# different directory (a different compose project) blocks this one from
# creating its containers — and the failure would otherwise surface only after
# pulling gigabytes of images. Detect it up front and explain the way out.
# Skipped under DATRIS_NO_START (nothing will be created).
if [ "${DATRIS_NO_START:-}" != "1" ]; then
  PROJECT=$(basename "$(cd "$DIR" && pwd)" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9_-]/_/g; s/^[_-]*//')
  CONFLICTS=""
  for name in minio activemq mongodb postgres vault vault-init tei datris datris-tap-runner ui mcp-server minio-init qdrant weaviate chroma zookeeper kafka kafka-ui; do
    if docker inspect "$name" >/dev/null 2>&1; then
      owner=$(docker inspect "$name" --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null)
      [ "$owner" = "$PROJECT" ] && continue
      owner_dir=$(docker inspect "$name" --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' 2>/dev/null)
      CONFLICTS="${CONFLICTS}  ${name}  (project: ${owner:-none — created outside compose}${owner_dir:+, dir: $owner_dir})
"
    fi
  done
  if [ -n "$CONFLICTS" ]; then
    warn "Found containers from a previous Datris installation that block this one:"
    printf '%s' "$CONFLICTS" >&2
    warn ""
    warn "To proceed, remove the old installation first:"
    warn "  cd <its directory above> && docker compose --profile \"*\" down"
    warn "or remove the containers directly:"
    warn "  docker rm -f$(printf '%s' "$CONFLICTS" | awk '{printf " %s", $1}')"
    warn ""
    warn "NOTE: if the old installation predates v1.11.0 and holds data you care"
    warn "about, its data lives on anonymous volumes — removing containers orphans"
    warn "(does not delete) that data, but the new install will NOT see it. Copy it"
    warn "out first, or install into the OLD directory instead to upgrade in place."
    die "container name conflict — resolve the above and re-run"
  fi
fi

# --- fetch runtime files --------------------------------------------------
for f in $FILES; do
  mkdir -p "$DIR/$(dirname "$f")"
  curl -fsSL "$REPO_RAW/$REF/$f" -o "$DIR/$f" || die "could not download $f from $REPO_RAW/$REF/$f"
done
chmod +x "$DIR/docker/vault-init.sh" "$DIR/docker/minio-init.sh" "$DIR/docker/vault-bootstrap.sh" 2>/dev/null || true
ok "Fetched compose file and runtime scripts."

# Detect a *usable* controlling terminal. `[ -r /dev/tty ]` is not enough: the
# device node can be readable yet fail to open ("Device not configured") when
# there's no controlling terminal (CI, some `curl | sh` contexts). Actually
# try to open it (error suppressed) so we fall back cleanly instead of aborting.
TTY=""
if { : < /dev/tty; } 2>/dev/null; then TTY="/dev/tty"; fi

# Prompt helpers — all input flows through the TTY, never stdin (which is the
# script itself under `curl | sh`).
ask() { # ask "prompt" -> $ANS (empty when non-interactive)
  ANS=""
  [ -z "$TTY" ] && return 0
  printf '%s' "$1" > "$TTY"
  read -r ANS < "$TTY" || ANS=""
}
ask_hidden() { # ask_hidden "prompt" -> $ANS, input not echoed
  ANS=""
  [ -z "$TTY" ] && return 0
  printf '%s' "$1" > "$TTY"
  stty -echo < "$TTY" 2>/dev/null || true
  read -r ANS < "$TTY" || ANS=""
  stty echo < "$TTY" 2>/dev/null || true
  printf '\n' > "$TTY"
}

# Append or replace KEY=VALUE in the .env being seeded. Values are escaped
# for the sed replacement so secrets containing & | \ can't corrupt the file.
set_env() {
  _key="$1"; _val="$2"
  if grep -q "^${_key}=" "$ENV_FILE" 2>/dev/null; then
    _esc=$(printf '%s' "$_val" | sed 's/[&|\\]/\\&/g')
    sed "s|^${_key}=.*|${_key}=${_esc}|" "$ENV_FILE" > "$ENV_FILE.tmp" && mv "$ENV_FILE.tmp" "$ENV_FILE"
  else
    printf '%s=%s\n' "$_key" "$_val" >> "$ENV_FILE"
  fi
}

# --- seed .env ------------------------------------------------------------
ENV_FILE="$DIR/.env"
FRESH_ENV=0
SUMMARY=""
add_summary() { SUMMARY="${SUMMARY}$(printf '  %-11s %-10s %s' "$1" "$2" "$3")\n"; }

if [ -f "$ENV_FILE" ]; then
  warn "Existing .env found — leaving it untouched (upgrade mode, no prompts)."
  warn "To change installed databases/stores, edit $ENV_FILE (see comments) and re-run '$COMPOSE up -d'."
else
  FRESH_ENV=1
  curl -fsSL "$REPO_RAW/$REF/.env.example" -o "$ENV_FILE" || die "could not download .env.example"
  chmod 600 "$ENV_FILE" 2>/dev/null || true

  # ---- AI keys (both providers, each best at a different job) ----
  AKEY="${ANTHROPIC_API_KEY:-}"
  OKEY="${OPENAI_API_KEY:-}"
  ZKEY="${AZURE_OPENAI_API_KEY:-}"
  ZEP="${AZURE_OPENAI_ENDPOINT:-}"
  ZMODEL="${AZURE_OPENAI_MODEL:-}"
  GKEY="${XAI_API_KEY:-}"
  # Bedrock is opt-in ONLY via AI_PROVIDER=bedrock (env preset or the prompt
  # below) — a stray AWS_ACCESS_KEY_ID must never flip the AI provider, since
  # AWS credentials are routinely present for S3 destinations.
  BEDROCK_SELECTED=0
  [ "${AI_PROVIDER:-}" = "bedrock" ] && BEDROCK_SELECTED=1
  BAK="${AWS_ACCESS_KEY_ID:-}"
  BSK="${AWS_SECRET_ACCESS_KEY:-}"
  BREGION="${AWS_REGION:-}"

  # If a provider key was inherited from the shell environment, never adopt it
  # silently — a stray ANTHROPIC/OPENAI_API_KEY in a shell rc shouldn't decide
  # your provider without you knowing. Announce what was found, and when
  # interactive let the user keep it or ignore it and enter their own.
  if [ -n "$AKEY" ] || [ -n "$OKEY" ] || [ -n "$ZKEY" ] || [ -n "$GKEY" ]; then
    [ -n "$AKEY" ] && say "Detected ANTHROPIC_API_KEY in your environment ($(mask "$AKEY"))."
    [ -n "$OKEY" ] && say "Detected OPENAI_API_KEY in your environment ($(mask "$OKEY"))."
    [ -n "$ZKEY" ] && say "Detected AZURE_OPENAI_API_KEY in your environment ($(mask "$ZKEY"))."
    [ -n "$GKEY" ] && say "Detected XAI_API_KEY in your environment ($(mask "$GKEY"))."
    if [ -n "$TTY" ]; then
      ask "  Use the detected key(s)? [Y/n] (n = ignore and enter your own): "
      case "$ANS" in
        n*|N*) warn "Ignoring environment keys."; AKEY=""; OKEY=""; ZKEY=""; GKEY="" ;;
      esac
    else
      say "Non-interactive — using the detected key(s)."
    fi
  fi

  if [ -n "$TTY" ] && [ "$BEDROCK_SELECTED" = "0" ] && { [ -z "$AKEY" ] || [ -z "$OKEY" ] || [ -z "$GKEY" ]; }; then
    say ""
    say "Datris can use Anthropic Claude, OpenAI, or Grok (xAI). Enter any keys"
    say "you have — one is enough. If you prefer Claude through Amazon Bedrock,"
    say "or OpenAI through your Azure OpenAI resource, press Enter through the"
    say "key prompts and you'll be offered those routes next."
    # Keys are read with echo OFF (like passwords) so they never land in the
    # terminal scrollback; a masked confirmation is printed instead.
    if [ -z "$AKEY" ]; then
      ask_hidden "  Anthropic API key (sk-ant-...) — powers chat, CodeGen, AI data quality, NL→SQL (recommended), or Enter to skip (input hidden): "
      AKEY="$ANS"
      [ -n "$AKEY" ] && say "  Anthropic key received ($(mask "$AKEY"))."
    fi
    if [ -z "$OKEY" ]; then
      ask_hidden "  OpenAI API key (sk-...) — powers semantic-search embeddings (recommended; Anthropic doesn't offer embeddings), or Enter to skip (input hidden): "
      OKEY="$ANS"
      [ -n "$OKEY" ] && say "  OpenAI key received ($(mask "$OKEY"))."
    fi
    # Grok (xAI) — the third direct-key provider, prompted like the first two.
    if [ -z "$GKEY" ]; then
      ask_hidden "  Grok (xAI) API key from console.x.ai — powers chat and CodeGen (xAI has no embeddings), or Enter to skip (input hidden): "
      GKEY="$ANS"
      [ -n "$GKEY" ] && say "  Grok key received ($(mask "$GKEY"))."
    fi
    # Azure OpenAI as a fallback route to the OpenAI models — offered only when
    # no direct key was provided (users with a direct key rarely want Azure too;
    # it stays available any time via the Configuration tab).
    if [ -z "$AKEY" ] && [ -z "$OKEY" ] && [ -z "$GKEY" ] && [ -z "$ZKEY" ]; then
      ask_hidden "  Azure OpenAI API key — use your Azure OpenAI resource instead, or Enter to skip (input hidden): "
      ZKEY="$ANS"
      if [ -n "$ZKEY" ]; then
        say "  Azure OpenAI key received ($(mask "$ZKEY"))."
        [ -z "$ZEP" ] && { ask "    Azure resource endpoint (https://YOUR-RESOURCE.openai.azure.com): "; ZEP="$ANS"; }
        [ -z "$ZMODEL" ] && { ask "    Chat deployment name (tip: name deployments after their model, e.g. gpt-5-2): "; ZMODEL="$ANS"; }
      fi
    fi
    # Amazon Bedrock — Claude through the user's AWS account. Offered only when
    # nothing else was chosen (same reasoning as the Azure fallback above).
    if [ -z "$AKEY" ] && [ -z "$OKEY" ] && [ -z "$ZKEY" ] && [ -z "$GKEY" ]; then
      ask "  Use Amazon Bedrock (Claude via your AWS account)? [y/N]: "
      case "$ANS" in
        y*|Y*)
          BEDROCK_SELECTED=1
          if [ -z "$BAK" ]; then
            ask "    AWS Access Key ID (Enter to use the host's IAM role / default credential chain): "
            BAK="$ANS"
            if [ -n "$BAK" ]; then
              ask_hidden "    AWS Secret Access Key (input hidden): "
              BSK="$ANS"
            fi
          fi
          if [ -z "$BREGION" ]; then
            ask "    AWS region [us-east-1]: "
            BREGION="${ANS:-us-east-1}"
          fi
          ;;
      esac
    fi
  fi

  WROTE_KEYS=""
  if [ -n "$AKEY" ]; then
    set_env ANTHROPIC_API_KEY "$AKEY"
    # Pin the chat/CodeGen provider: with both keys present, vault-init's
    # tie-break would otherwise pick OpenAI for ai-primary — the opposite of
    # the recommendation.
    set_env AI_PROVIDER anthropic
    WROTE_KEYS="ANTHROPIC_API_KEY, AI_PROVIDER=anthropic"
  fi
  if [ -n "$OKEY" ]; then
    set_env OPENAI_API_KEY "$OKEY"
    WROTE_KEYS="${WROTE_KEYS:+$WROTE_KEYS, }OPENAI_API_KEY"
  fi
  if [ -n "$ZKEY" ]; then
    # Azure needs all three values or vault-init fails the first boot — write
    # nothing rather than seed a half-configured provider.
    if [ -n "$ZEP" ] && [ -n "$ZMODEL" ]; then
      set_env AZURE_OPENAI_API_KEY "$ZKEY"
      set_env AZURE_OPENAI_ENDPOINT "$ZEP"
      set_env AZURE_OPENAI_MODEL "$ZMODEL"
      WROTE_KEYS="${WROTE_KEYS:+$WROTE_KEYS, }AZURE_OPENAI_API_KEY"
      # Pin only when Azure is the sole chat provider (anthropic pin above wins).
      if [ -z "$AKEY" ] && [ -z "$OKEY" ]; then
        set_env AI_PROVIDER azure
        WROTE_KEYS="$WROTE_KEYS, AI_PROVIDER=azure"
      fi
    else
      warn "Azure OpenAI needs AZURE_OPENAI_ENDPOINT and AZURE_OPENAI_MODEL too — skipping."
      warn "Add all three in $ENV_FILE (or the Configuration tab) later."
      ZKEY=""
    fi
  fi
  if [ -n "$GKEY" ]; then
    set_env XAI_API_KEY "$GKEY"
    WROTE_KEYS="${WROTE_KEYS:+$WROTE_KEYS, }XAI_API_KEY"
    # Pin only when Grok is the sole chat provider (any pin above wins).
    if [ -z "$AKEY" ] && [ -z "$OKEY" ] && [ -z "$ZKEY" ]; then
      set_env AI_PROVIDER grok
      WROTE_KEYS="$WROTE_KEYS, AI_PROVIDER=grok"
    fi
  fi
  if [ "$BEDROCK_SELECTED" = "1" ]; then
    if [ -n "$BAK" ] && [ -z "$BSK" ]; then
      # Half a credential would fail the first AI call — drop the keys and let
      # the default chain (or the Configuration tab) supply them instead.
      warn "AWS_ACCESS_KEY_ID without AWS_SECRET_ACCESS_KEY — skipping the keys."
      warn "The server will use its IAM role / default chain; or add both keys in the Configuration tab."
      BAK=""; BSK=""
    fi
    # Pin only when Bedrock is the sole chat provider (a direct Anthropic key
    # keeps its pin from above).
    if [ -z "$AKEY" ]; then
      set_env AI_PROVIDER bedrock
      WROTE_KEYS="${WROTE_KEYS:+$WROTE_KEYS, }AI_PROVIDER=bedrock"
    fi
    [ -n "$BAK" ] && { set_env AWS_ACCESS_KEY_ID "$BAK"; set_env AWS_SECRET_ACCESS_KEY "$BSK"; WROTE_KEYS="$WROTE_KEYS, AWS_ACCESS_KEY_ID"; }
    [ -n "$BREGION" ] && set_env AWS_REGION "$BREGION"
    [ -n "${BEDROCK_MODEL:-}" ] && set_env BEDROCK_MODEL "${BEDROCK_MODEL}"
  fi
  if [ -n "$WROTE_KEYS" ]; then
    ok "Wrote $WROTE_KEYS to .env"
  else
    warn "No AI key set. Datris will start, but AI features stay off until you add a"
    warn "key in $ENV_FILE (or the Configuration tab in the UI) and re-run 'up -d'."
  fi

  # ---- store selection -----------------------------------------------------
  PROFILES="${DATRIS_PROFILES:-}"
  add_profile() {
    case ",$PROFILES," in *",$1,"*) ;; *) PROFILES="${PROFILES:+$PROFILES,}$1" ;; esac
  }

  if [ -n "$TTY" ]; then
    say ""
    say "Now choose your data stores. Defaults match a standard install — just"
    say "press Enter to accept. For each store: run the bundled container,"
    say "connect to one you already have, or skip it."
  fi

  # Postgres — bundled / external / none (default bundled).
  PG_MODE="${DATRIS_POSTGRES:-}"
  if [ -z "$PG_MODE" ] && [ -n "$TTY" ]; then
    ask "  Postgres (structured destination) — [B]undled / [e]xternal / [n]one: "
    case "$ANS" in
      e*|E*) PG_MODE="external" ;;
      n*|N*) PG_MODE="none" ;;
      *)     PG_MODE="bundled" ;;
    esac
  fi
  PG_MODE="${PG_MODE:-bundled}"
  case "$PG_MODE" in
    external)
      PG_URL="${POSTGRES_JDBC_URL:-}"
      if [ -z "$PG_URL" ] && [ -n "$TTY" ]; then
        ask "    JDBC URL, base only, no database (jdbc:postgresql://host:5432): "
        PG_URL="$ANS"
      fi
      [ -z "$PG_URL" ] && die "DATRIS_POSTGRES=external requires POSTGRES_JDBC_URL"
      PG_USER_V="${POSTGRES_USER:-}"
      if [ -z "$PG_USER_V" ] && [ -n "$TTY" ]; then ask "    Username: "; PG_USER_V="$ANS"; fi
      PG_PASS_V="${POSTGRES_PASSWORD:-}"
      if [ -z "$PG_PASS_V" ] && [ -n "$TTY" ]; then ask_hidden "    Password (input hidden): "; PG_PASS_V="$ANS"; fi
      set_env POSTGRES_ENABLED 0
      set_env POSTGRES_JDBC_URL "$PG_URL"
      set_env POSTGRES_USER "$PG_USER_V"
      set_env POSTGRES_PASSWORD "$PG_PASS_V"
      say "  Will use external Postgres — no local container."
      add_summary postgres external "$(printf '%s' "$PG_URL" | sed 's|^jdbc:postgresql://||')"
      ;;
    none)
      set_env POSTGRES_ENABLED 0
      add_summary postgres "-" "not installed"
      ;;
    *)
      add_summary postgres bundled "pgvector/pgvector:pg16"
      ;;
  esac

  # Embeddings — openai (recommended) / bundled tei / none.
  EMB_MODE="${DATRIS_EMBEDDING:-}"
  if [ -z "$EMB_MODE" ] && [ -n "$TTY" ]; then
    say ""
    say "  Semantic search needs an embedding model. We recommend OpenAI"
    say "  (text-embedding-3-small): great quality, costs pennies, and skips the"
    say "  2.2 GB local model download. Run the bundled server only if your data"
    say "  can't leave this machine."
    if [ -n "$OKEY" ]; then
      ask "    [O]penAI (recommended, uses your OpenAI key) / [b]undled local server (TEI, ~2.2 GB) / [n]one: "
      case "$ANS" in
        b*|B*) EMB_MODE="tei" ;;
        n*|N*) EMB_MODE="none" ;;
        *)     EMB_MODE="openai" ;;
      esac
    else
      ask "    [B]undled local server (TEI, ~2.2 GB) / [n]one (OpenAI needs an OpenAI key — add one in the Configuration tab later): "
      case "$ANS" in
        n*|N*) EMB_MODE="none" ;;
        *)     EMB_MODE="tei" ;;
      esac
    fi
  fi
  if [ -z "$EMB_MODE" ]; then
    if [ -n "$OKEY" ]; then EMB_MODE="openai"; else EMB_MODE="tei"; fi
  fi
  case "$EMB_MODE" in
    openai)
      [ -z "$OKEY" ] && die "DATRIS_EMBEDDING=openai requires OPENAI_API_KEY"
      set_env EMBEDDING_PROVIDER openai
      set_env TEI_ENABLED 0
      say "  OpenAI embeddings selected — no local embedding container."
      add_summary search openai "text-embedding-3-small (no local container)"
      ;;
    none)
      set_env TEI_ENABLED 0
      add_summary search "-" "not installed"
      ;;
    *)
      set_env EMBEDDING_PROVIDER tei
      add_summary search bundled "TEI (model downloads on first boot)"
      ;;
  esac

  # Vector stores — opt-in, bundled (profile) or external per store.
  VEC_CHOICE=""
  if [ -n "$TTY" ] && [ -z "${DATRIS_PROFILES:-}${QDRANT_HOST:-}${WEAVIATE_HOST:-}${CHROMA_HOST:-}${MILVUS_HOST:-}" ]; then
    say ""
    ask "  Vector stores (qdrant, weaviate, chroma; milvus external-only) — comma-separated, or Enter for none: "
    VEC_CHOICE="$ANS"
  fi
  for store in $(printf '%s' "$VEC_CHOICE" | tr ',' ' '); do
    case "$store" in
      qdrant|weaviate|chroma|milvus) ;;
      *) warn "  Unknown vector store '$store' — skipping."; continue ;;
    esac
    MODE="bundled"
    if [ "$store" = "milvus" ]; then
      MODE="external"
      say "    Milvus is external-only (needs its own etcd/minio stack)."
    elif [ -n "$TTY" ]; then
      ask "    Run $store [B]undled or [e]xternal (e.g. managed cloud)? "
      case "$ANS" in e*|E*) MODE="external" ;; esac
    fi
    if [ "$MODE" = "bundled" ]; then
      add_profile "$store"
      # In-network coordinates: compose service name + container port
      # (weaviate's container port is 8080; 8079 is only the host mapping).
      case "$store" in
        qdrant)   set_env QDRANT_HOST qdrant;     set_env QDRANT_PORT 6334 ;;
        weaviate) set_env WEAVIATE_HOST weaviate; set_env WEAVIATE_PORT 8080 ;;
        chroma)   set_env CHROMA_HOST chroma;     set_env CHROMA_PORT 8000 ;;
      esac
      add_summary "$store" bundled "local container"
    else
      ask "    Host: "; V_HOST="$ANS"
      [ -z "$V_HOST" ] && { warn "    No host given — skipping $store."; continue; }
      case "$store" in
        qdrant)   DEF_PORT=6334 ;;
        weaviate) DEF_PORT=8079 ;;
        chroma)   DEF_PORT=8000 ;;
        milvus)   DEF_PORT=19530 ;;
      esac
      ask "    Port [$DEF_PORT]: "; V_PORT="${ANS:-$DEF_PORT}"
      V_KEY=""
      if [ "$store" != "chroma" ]; then
        ask_hidden "    API key (input hidden, Enter for none): "; V_KEY="$ANS"
      fi
      STORE_UPPER=$(printf '%s' "$store" | tr '[:lower:]' '[:upper:]')
      set_env "${STORE_UPPER}_HOST" "$V_HOST"
      set_env "${STORE_UPPER}_PORT" "$V_PORT"
      [ "$store" != "chroma" ] && set_env "${STORE_UPPER}_API_KEY" "$V_KEY"
      say "    Will use external $store — no local container."
      add_summary "$store" external "$V_HOST"
    fi
  done

  # Kafka — bundled test broker / external / none (default none).
  KAFKA_MODE=""
  if [ -n "${KAFKA_BOOTSTRAP_SERVERS:-}" ]; then
    KAFKA_MODE="external"
  elif [ -n "$TTY" ]; then
    say ""
    ask "  Kafka (streaming source/destination) — [b]undled test broker / [e]xternal / [N]one: "
    case "$ANS" in
      b*|B*) KAFKA_MODE="bundled" ;;
      e*|E*) KAFKA_MODE="external" ;;
    esac
  fi
  case "$KAFKA_MODE" in
    bundled)
      add_profile kafka
      set_env KAFKA_BOOTSTRAP_SERVERS kafka:9092
      add_summary kafka bundled "local test broker (kafka-ui on :8085)"
      ;;
    external)
      KB="${KAFKA_BOOTSTRAP_SERVERS:-}"
      if [ -z "$KB" ] && [ -n "$TTY" ]; then
        ask "    Bootstrap servers (host1:9092,host2:9092): "
        KB="$ANS"
      fi
      if [ -n "$KB" ]; then
        set_env KAFKA_BOOTSTRAP_SERVERS "$KB"
        add_summary kafka external "$KB"
      else
        warn "    No bootstrap servers given — skipping Kafka."
        add_summary kafka "-" "not installed"
      fi
      ;;
    *)
      add_summary kafka "-" "not installed"
      ;;
  esac

  # Snowflake — external-only credentials, skippable.
  SNOW_DONE=""
  if [ -n "${SNOWFLAKE_ACCOUNT:-}" ]; then
    set_env SNOWFLAKE_ACCOUNT "${SNOWFLAKE_ACCOUNT}"
    set_env SNOWFLAKE_USER "${SNOWFLAKE_USER:-}"
    [ -n "${SNOWFLAKE_PRIVATE_KEY:-}" ] && set_env SNOWFLAKE_PRIVATE_KEY "${SNOWFLAKE_PRIVATE_KEY}"
    [ -n "${SNOWFLAKE_PASSWORD:-}" ] && set_env SNOWFLAKE_PASSWORD "${SNOWFLAKE_PASSWORD}"
    SNOW_DONE="${SNOWFLAKE_ACCOUNT}"
  elif [ -n "$TTY" ]; then
    say ""
    ask "  Snowflake destination — configure credentials now? [y/N]: "
    case "$ANS" in
      y*|Y*)
        ask "    Account (xy12345.us-east-1): "; SF_ACC="$ANS"
        ask "    User: "; SF_USER="$ANS"
        ask_hidden "    Private key for key-pair auth (recommended), or Enter to use a password: "; SF_PK="$ANS"
        SF_PW=""
        if [ -z "$SF_PK" ]; then ask_hidden "    Password (input hidden): "; SF_PW="$ANS"; fi
        if [ -n "$SF_ACC" ]; then
          set_env SNOWFLAKE_ACCOUNT "$SF_ACC"
          set_env SNOWFLAKE_USER "$SF_USER"
          [ -n "$SF_PK" ] && set_env SNOWFLAKE_PRIVATE_KEY "$SF_PK"
          [ -n "$SF_PW" ] && set_env SNOWFLAKE_PASSWORD "$SF_PW"
          SNOW_DONE="$SF_ACC"
        fi
        ;;
    esac
  fi
  if [ -n "$SNOW_DONE" ]; then
    add_summary snowflake external "$SNOW_DONE (credentials secret)"
  else
    add_summary snowflake "-" "not configured"
  fi

  # Databricks — external-only credentials, skippable.
  DBX_DONE=""
  if [ -n "${DATABRICKS_HOST:-}" ]; then
    set_env DATABRICKS_HOST "${DATABRICKS_HOST}"
    [ -n "${DATABRICKS_CLIENT_ID:-}" ] && set_env DATABRICKS_CLIENT_ID "${DATABRICKS_CLIENT_ID}"
    [ -n "${DATABRICKS_CLIENT_SECRET:-}" ] && set_env DATABRICKS_CLIENT_SECRET "${DATABRICKS_CLIENT_SECRET}"
    [ -n "${DATABRICKS_TOKEN:-}" ] && set_env DATABRICKS_TOKEN "${DATABRICKS_TOKEN}"
    DBX_DONE="${DATABRICKS_HOST}"
  elif [ -n "$TTY" ]; then
    ask "  Databricks destination — configure credentials now? [y/N]: "
    case "$ANS" in
      y*|Y*)
        ask "    Workspace host (adb-....azuredatabricks.net / dbc-....cloud.databricks.com): "; DB_HOST="$ANS"
        ask "    Auth — [S]ervice principal (clientId/clientSecret) or [t]oken: "
        case "$ANS" in
          t*|T*)
            ask_hidden "    Personal access token (input hidden): "; DB_TOK="$ANS"
            if [ -n "$DB_HOST" ] && [ -n "$DB_TOK" ]; then
              set_env DATABRICKS_HOST "$DB_HOST"
              set_env DATABRICKS_TOKEN "$DB_TOK"
              DBX_DONE="$DB_HOST"
            fi
            ;;
          *)
            ask "    Client ID: "; DB_CID="$ANS"
            ask_hidden "    Client secret (input hidden): "; DB_CSEC="$ANS"
            if [ -n "$DB_HOST" ] && [ -n "$DB_CID" ]; then
              set_env DATABRICKS_HOST "$DB_HOST"
              set_env DATABRICKS_CLIENT_ID "$DB_CID"
              set_env DATABRICKS_CLIENT_SECRET "$DB_CSEC"
              DBX_DONE="$DB_HOST"
            fi
            ;;
        esac
        ;;
    esac
  fi
  if [ -n "$DBX_DONE" ]; then
    add_summary databricks external "$DBX_DONE (credentials secret)"
  else
    add_summary databricks "-" "not configured"
  fi

  [ -n "$PROFILES" ] && set_env COMPOSE_PROFILES "$PROFILES"

  # set_env rewrites via mv, which resets permissions — re-tighten as the
  # final step now that the file may hold DB passwords.
  chmod 600 "$ENV_FILE" 2>/dev/null || true

  say ""
  say "Install summary:"
  # shellcheck disable=SC2059
  printf "$SUMMARY"
  ok "Wrote store configuration to .env (permissions set to 600)."
fi

# --- launch ---------------------------------------------------------------
if [ "${DATRIS_NO_START:-}" = "1" ]; then
  ok "Files written to $DIR. Skipping start (DATRIS_NO_START=1)."
  say "Run it with:  cd $DIR && $COMPOSE up -d"
  exit 0
fi

say ""
say "Pulling images and starting Datris (first run downloads ~a few GB)..."
# --remove-orphans keeps re-running this script a safe upgrade: a new version may
# rename or drop a service (e.g. Ollama → TEI on the same port), and without the
# flag the stale container holds the port and the new one fails to bind. It only
# removes containers no longer in the compose file; named volumes (your data)
# survive. Services disabled via *_ENABLED=0 still exist in the compose model
# (replicas: 0), so they are never treated as orphans.
( cd "$DIR" && $COMPOSE pull && $COMPOSE up -d --remove-orphans )

# --- post-boot store check --------------------------------------------------
# The installer host has no DB clients, so external-store validation happens
# here: once the server answers, /health/services probes every configured
# store (bundled or external) and we surface the result by name.
if [ "$FRESH_ENV" = "1" ] && command -v curl >/dev/null 2>&1; then
  say ""
  say "Waiting for first boot, then checking your stores (this can take a couple minutes)..."
  HEALTH=""
  i=0
  while [ $i -lt 60 ]; do
    HEALTH=$(curl -fsS --max-time 5 "http://localhost:8080/api/v1/health/services" 2>/dev/null) && break
    HEALTH=""
    i=$((i+1))
    sleep 5
  done
  if [ -z "$HEALTH" ]; then
    warn "Server not answering yet — check progress with: cd $DIR && $COMPOSE logs -f datris"
    warn "Once it's up, per-store status: http://localhost:8080/api/v1/health/services"
  else
    for store in mongodb minio activemq postgres qdrant weaviate milvus chroma kafka; do
      entry=$(printf '%s' "$HEALTH" | grep -o "\"$store\":{[^}]*}" | head -1) || true
      [ -z "$entry" ] && continue
      case "$entry" in
        *'"status":"up"'*)             ok   "  $store  up" ;;
        *'"status":"not_configured"'*|*'"status":"not configured"'*) ;; # skipped stores stay quiet
        *)                              warn "  $store  DOWN — check its credentials in the Configuration tab" ;;
      esac
    done
  fi
fi

ok ""
ok "Datris is starting up."
say "  UI:   http://localhost:4200"
say "  API:  http://localhost:8080"
say "  MCP:  http://localhost:3000"
say ""
say "First boot may pull an embedding model (~2.2 GB) if you chose the bundled"
say "embedding server — give it a couple minutes."
say "Logs:   cd $DIR && $COMPOSE logs -f datris"
say "Stop:   cd $DIR && $COMPOSE down          (full teardown incl. opt-in services:"
say "        cd $DIR && $COMPOSE --profile \"*\" down)"
