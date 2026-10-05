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
#   XAI_API_KEY         pre-set Grok (xAI) key       (skips the prompt;
#                       optionally with GROK_MODEL, default grok-4.7)
#   AI_PROVIDER         anthropic|openai|azure|grok|bedrock — which provider
#                       handles chat and CodeGen. Always written to .env. When
#                       unset and several keys are configured, an interactive
#                       run asks; a non-interactive run picks the first of
#                       anthropic, openai, azure, grok and says so. A preset
#                       naming a provider with no key/config stops the install.
#                       azure with no AZURE_OPENAI_API_KEY means keyless Entra
#                       ID auth (endpoint + model still required; optionally
#                       AZURE_TENANT_ID/AZURE_CLIENT_ID/AZURE_CLIENT_SECRET).
#   AI_PROVIDER=bedrock Amazon Bedrock (Claude through your AWS account;
#                       explicit-only — AWS keys alone never imply it).
#                       Optionally with AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY/
#                       AWS_REGION (leave the keys unset to use the host's IAM
#                       role / default credential chain) and BEDROCK_MODEL
#                       (default: anthropic.claude-fable-5-1)
#   A fresh install with no AI provider stops before pulling anything (the
#   stack cannot start without one), except under DATRIS_NO_START=1.
#   DATRIS_POSTGRES     bundled|external|none        (default: bundled)
#                       external also reads POSTGRES_JDBC_URL/POSTGRES_USER/POSTGRES_PASSWORD
#   DATRIS_EMBEDDING    openai|tei|none              (default: openai if OpenAI key present, else tei)
#   DATRIS_PROFILES     comma-separated opt-in services: qdrant,weaviate,chroma,kafka
#                       (bundled; written to .env as COMPOSE_PROFILES plus the
#                       same in-network host/port an interactive run writes)
#   QDRANT_HOST, WEAVIATE_HOST, CHROMA_HOST, MILVUS_HOST — external vector
#                       stores, each with an optional port and API key in the
#                       same pattern (QDRANT_PORT, QDRANT_API_KEY; chroma takes
#                       no key); written to .env. A preset host wins over the
#                       same store named in DATRIS_PROFILES.
#   KAFKA_BOOTSTRAP_SERVERS, SNOWFLAKE_ACCOUNT/USER/PRIVATE_KEY/PASSWORD,
#   DATABRICKS_HOST/CLIENT_ID/CLIENT_SECRET/TOKEN — external store credentials
#   DATRIS_GOVERNED=1   switch on the governance controls for production on a
#                       fresh install: writes USE_USER_AUTH, USE_API_KEYS,
#                       USE_AUDIT_LOG and USE_AGENT_POLICY as true. Unset, an
#                       interactive run asks (default No); a non-interactive
#                       run leaves them off. Ignored on an upgrade (the .env is
#                       never edited; the lines to add are printed instead).
#                       1/true/yes/on or 0/false/no/off; anything else stops.
#   DATRIS_NO_START=1   write files but don't run compose
#   DATRIS_SKIP_DOCTOR=1  skip the pre-upgrade `datris doctor` check on an upgrade
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

# DATRIS_GOVERNED, read once for both the fresh-install and upgrade branches:
# GOVERNED_REQ is 1 (on), 0 (off) or empty (unset — an interactive fresh
# install asks). Anything unrecognized stops before any file is written.
case "${DATRIS_GOVERNED:-}" in
  "") GOVERNED_REQ="" ;;
  1|[Tt][Rr][Uu][Ee]|[Yy]|[Yy][Ee][Ss]|[Oo][Nn]) GOVERNED_REQ=1 ;;
  0|[Ff][Aa][Ll][Ss][Ee]|[Nn]|[Nn][Oo]|[Oo][Ff][Ff]) GOVERNED_REQ=0 ;;
  *) die "DATRIS_GOVERNED must be 1 or 0 (got '${DATRIS_GOVERNED}')" ;;
esac

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
# Only replace a file whose content changed: docker/vault.hcl is bind-mounted
# and `datris doctor` flags it when its mtime is newer than the vault
# container's start, so an identical re-download must not touch the mtime.
for f in $FILES; do
  mkdir -p "$DIR/$(dirname "$f")"
  curl -fsSL "$REPO_RAW/$REF/$f" -o "$DIR/$f.tmp" || die "could not download $f from $REPO_RAW/$REF/$f"
  if [ -f "$DIR/$f" ] && cmp -s "$DIR/$f.tmp" "$DIR/$f"; then
    rm -f "$DIR/$f.tmp"
  else
    mv -f "$DIR/$f.tmp" "$DIR/$f"
  fi
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
#
# The .env holds provider keys, so it stays mode 600 at every step: the
# rewrite goes through a temp file created under umask 077, and the mode is
# re-applied after the mv (which carries the temp file's mode) and after an
# append.
set_env() {
  _key="$1"; _val="$2"
  if grep -q "^${_key}=" "$ENV_FILE" 2>/dev/null; then
    _esc=$(printf '%s' "$_val" | sed 's/[&|\\]/\\&/g')
    if ! (umask 077 && sed "s|^${_key}=.*|${_key}=${_esc}|" "$ENV_FILE" > "$ENV_FILE.tmp"); then
      rm -f "$ENV_FILE.tmp"
      return 1
    fi
    mv -f "$ENV_FILE.tmp" "$ENV_FILE"
  else
    printf '%s=%s\n' "$_key" "$_val" >> "$ENV_FILE"
  fi
  chmod 600 "$ENV_FILE" 2>/dev/null || true
}

# --- seed .env ------------------------------------------------------------
ENV_FILE="$DIR/.env"
FRESH_ENV=0
SEED_DONE=0
SUMMARY=""
add_summary() { SUMMARY="${SUMMARY}$(printf '  %-11s %-10s %s' "$1" "$2" "$3")\n"; }

# A fresh run that stops before seeding finishes (die, an error under set -e,
# Ctrl-C) must not leave a partial .env behind: it may already hold a provider
# key, and the next run would treat it as an upgrade and ignore corrected
# variables. Only a .env this run created is removed (FRESH_ENV=1), and the
# trap is disarmed once seeding is complete, so a later `compose up` failure
# keeps the finished .env. set_env's temp file is always cleaned up.
cleanup_partial_env() {
  rm -f "$ENV_FILE.tmp" 2>/dev/null || true
  if [ "$FRESH_ENV" = "1" ] && [ "$SEED_DONE" != "1" ]; then
    rm -f "$ENV_FILE" 2>/dev/null || true
    printf '\033[33m%s\033[0m\n' "Install stopped before finishing — removed the partial $ENV_FILE." >&2
  fi
}
trap cleanup_partial_env EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

GOVERNED_ON=0
if [ -f "$ENV_FILE" ]; then
  warn "Existing .env found — leaving it untouched (upgrade mode, no prompts)."
  warn "To change installed databases/stores, edit $ENV_FILE (see comments) and re-run '$COMPOSE up -d'."
  if [ "$GOVERNED_REQ" = "1" ]; then
    warn ""
    warn "DATRIS_GOVERNED is ignored on upgrade — the existing .env is not edited."
    warn "To switch on the governance controls, add these lines to $ENV_FILE:"
    warn "  USE_USER_AUTH=true"
    warn "  USE_API_KEYS=true"
    warn "  USE_AUDIT_LOG=true"
    warn "  USE_AGENT_POLICY=true"
    warn "Before recreating, read and save the admin bootstrap password (it is only in the"
    warn "log of the container that seeded admin, and recreating discards that log):"
    warn "  cd $DIR && $COMPOSE logs datris | grep \"Bootstrap login\""
    warn "If nothing is printed, see https://docs.datris.ai/user-auth#recovering-admin-access"
    warn "then run: cd $DIR && $COMPOSE up -d --force-recreate datris mcp-server"
  fi
else
  FRESH_ENV=1
  (umask 077 && curl -fsSL "$REPO_RAW/$REF/.env.example" -o "$ENV_FILE") || die "could not download .env.example"
  chmod 600 "$ENV_FILE" 2>/dev/null || true

  # ---- AI keys ----
  # Chat and CodeGen can run on Anthropic, OpenAI, Azure OpenAI, Grok (xAI)
  # or Amazon Bedrock; exactly one handles them, recorded as AI_PROVIDER.
  # Embeddings are separate (below): Anthropic and xAI have no embeddings API,
  # so semantic search uses OpenAI or the bundled local server.
  AKEY="${ANTHROPIC_API_KEY:-}"
  OKEY="${OPENAI_API_KEY:-}"
  ZKEY="${AZURE_OPENAI_API_KEY:-}"
  ZEP="${AZURE_OPENAI_ENDPOINT:-}"
  ZMODEL="${AZURE_OPENAI_MODEL:-}"
  GKEY="${XAI_API_KEY:-}"
  # AI_PROVIDER preset: any value vault-init accepts. Checked against the
  # configured keys once they are collected (below).
  PRESET="${AI_PROVIDER:-}"
  case "$PRESET" in
    ""|anthropic|openai|azure|grok|bedrock) ;;
    *) die "AI_PROVIDER='$PRESET' is not a provider Datris knows — use anthropic, openai, azure, grok or bedrock, or leave it unset." ;;
  esac
  # Bedrock is opt-in ONLY via AI_PROVIDER=bedrock (env preset or the prompt
  # below) — a stray AWS_ACCESS_KEY_ID must never flip the AI provider, since
  # AWS credentials are routinely present for S3 destinations.
  BEDROCK_SELECTED=0
  [ "$PRESET" = "bedrock" ] && BEDROCK_SELECTED=1
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
      ask_hidden "  Anthropic API key (sk-ant-...) — powers chat, CodeGen, AI data quality and NL→SQL, or Enter to skip (input hidden): "
      AKEY="$ANS"
      [ -n "$AKEY" ] && say "  Anthropic key received ($(mask "$AKEY"))."
    fi
    if [ -z "$OKEY" ]; then
      ask_hidden "  OpenAI API key (sk-...) — powers chat, CodeGen, AI data quality and NL→SQL, plus semantic-search embeddings, or Enter to skip (input hidden): "
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

  # Write the keys and collect the configured chat providers in tie-break
  # order (anthropic, openai, azure, grok). This order is what the installer
  # plus vault-init have always produced for automated installs, so keeping
  # it means an existing CI install resolves to the same provider as before.
  WROTE_KEYS=""
  CHAT_OK=""
  if [ -n "$AKEY" ]; then
    set_env ANTHROPIC_API_KEY "$AKEY"
    WROTE_KEYS="ANTHROPIC_API_KEY"
    CHAT_OK="anthropic"
  fi
  if [ -n "$OKEY" ]; then
    set_env OPENAI_API_KEY "$OKEY"
    WROTE_KEYS="${WROTE_KEYS:+$WROTE_KEYS, }OPENAI_API_KEY"
    CHAT_OK="${CHAT_OK:+$CHAT_OK }openai"
  fi
  if [ -n "$ZKEY" ]; then
    # Azure needs all three values or vault-init fails the first boot — write
    # nothing rather than seed a half-configured provider.
    if [ -n "$ZEP" ] && [ -n "$ZMODEL" ]; then
      set_env AZURE_OPENAI_API_KEY "$ZKEY"
      set_env AZURE_OPENAI_ENDPOINT "$ZEP"
      set_env AZURE_OPENAI_MODEL "$ZMODEL"
      WROTE_KEYS="${WROTE_KEYS:+$WROTE_KEYS, }AZURE_OPENAI_API_KEY"
      CHAT_OK="${CHAT_OK:+$CHAT_OK }azure"
    else
      warn "Azure OpenAI needs AZURE_OPENAI_ENDPOINT and AZURE_OPENAI_MODEL too — skipping."
      warn "Add all three in $ENV_FILE (or the Configuration tab) later."
      ZKEY=""
    fi
  elif [ "$PRESET" = "azure" ] && [ -n "$ZEP" ] && [ -n "$ZMODEL" ]; then
    # Keyless Azure (Entra ID), which vault-init accepts only with an explicit
    # AI_PROVIDER=azure: the service-principal trio, or none of it for a
    # managed identity on Azure compute. Mirror vault-init's all-or-none rule.
    _sp=0
    [ -n "${AZURE_TENANT_ID:-}" ] && _sp=$((_sp + 1))
    [ -n "${AZURE_CLIENT_ID:-}" ] && _sp=$((_sp + 1))
    [ -n "${AZURE_CLIENT_SECRET:-}" ] && _sp=$((_sp + 1))
    if [ "$_sp" -gt 0 ] && [ "$_sp" -lt 3 ]; then
      die "AI_PROVIDER=azure without AZURE_OPENAI_API_KEY needs all three of AZURE_TENANT_ID, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET (or none, for a managed identity on Azure compute)."
    fi
    set_env AZURE_OPENAI_ENDPOINT "$ZEP"
    set_env AZURE_OPENAI_MODEL "$ZMODEL"
    WROTE_KEYS="${WROTE_KEYS:+$WROTE_KEYS, }AZURE_OPENAI_ENDPOINT (keyless)"
    if [ "$_sp" -eq 3 ]; then
      set_env AZURE_TENANT_ID "${AZURE_TENANT_ID}"
      set_env AZURE_CLIENT_ID "${AZURE_CLIENT_ID}"
      set_env AZURE_CLIENT_SECRET "${AZURE_CLIENT_SECRET}"
      WROTE_KEYS="$WROTE_KEYS, AZURE_CLIENT_ID"
    fi
    CHAT_OK="${CHAT_OK:+$CHAT_OK }azure"
  fi
  if [ -n "$GKEY" ]; then
    set_env XAI_API_KEY "$GKEY"
    WROTE_KEYS="${WROTE_KEYS:+$WROTE_KEYS, }XAI_API_KEY"
    CHAT_OK="${CHAT_OK:+$CHAT_OK }grok"
  fi
  if [ "$BEDROCK_SELECTED" = "1" ]; then
    if [ -n "$BAK" ] && [ -z "$BSK" ]; then
      # Half a credential would fail the first AI call — drop the keys and let
      # the default chain (or the Configuration tab) supply them instead.
      warn "AWS_ACCESS_KEY_ID without AWS_SECRET_ACCESS_KEY — skipping the keys."
      warn "The server will use its IAM role / default chain; or add both keys in the Configuration tab."
      BAK=""; BSK=""
    fi
    [ -n "$BAK" ] && { set_env AWS_ACCESS_KEY_ID "$BAK"; set_env AWS_SECRET_ACCESS_KEY "$BSK"; WROTE_KEYS="${WROTE_KEYS:+$WROTE_KEYS, }AWS_ACCESS_KEY_ID"; }
    [ -n "$BREGION" ] && set_env AWS_REGION "$BREGION"
    [ -n "${BEDROCK_MODEL:-}" ] && set_env BEDROCK_MODEL "${BEDROCK_MODEL}"
  fi

  # Resolve the one provider for chat and CodeGen.
  CHAT=""
  CHAT_N=0
  for _p in $CHAT_OK; do
    CHAT_N=$((CHAT_N + 1))
    if [ -z "$CHAT" ]; then CHAT="$_p"; fi
  done
  if [ -n "$PRESET" ]; then
    if [ "$PRESET" = "bedrock" ]; then
      CHAT="bedrock"
    else
      case " $CHAT_OK " in
        *" $PRESET "*) CHAT="$PRESET" ;;
        *)
          case "$PRESET" in
            anthropic) _need="ANTHROPIC_API_KEY" ;;
            openai)    _need="OPENAI_API_KEY" ;;
            grok)      _need="XAI_API_KEY" ;;
            azure)     _need="AZURE_OPENAI_ENDPOINT and AZURE_OPENAI_MODEL (with AZURE_OPENAI_API_KEY, or Entra ID credentials for keyless auth)" ;;
          esac
          die "AI_PROVIDER=$PRESET needs $_need, which is not set. Set it, or choose a provider you have a key for${CHAT_OK:+ (configured: $CHAT_OK)}, and re-run."
          ;;
      esac
    fi
  elif [ "$BEDROCK_SELECTED" = "1" ]; then
    CHAT="bedrock"
  elif [ "$CHAT_N" -gt 1 ]; then
    if [ -n "$TTY" ]; then
      say ""
      say "More than one AI provider is configured. One handles chat and CodeGen;"
      say "you can change it later in the Configuration tab."
      ask "  Provider for chat and CodeGen — $(printf '%s' "$CHAT_OK" | sed 's/ /, /g') [$CHAT]: "
      _pick=$(printf '%s' "$ANS" | tr '[:upper:]' '[:lower:]' | tr -d ' ')
      if [ -n "$_pick" ]; then
        case " $CHAT_OK " in
          *" $_pick "*) CHAT="$_pick" ;;
          *) warn "  '$ANS' is not one of the configured providers — using $CHAT." ;;
        esac
      fi
    else
      say "Several AI providers configured ($(printf '%s' "$CHAT_OK" | sed 's/ /, /g')) — chat and CodeGen will use $CHAT. Set AI_PROVIDER to choose another."
    fi
  fi

  if [ -n "$CHAT" ]; then
    set_env AI_PROVIDER "$CHAT"
    WROTE_KEYS="${WROTE_KEYS:+$WROTE_KEYS, }AI_PROVIDER=$CHAT"
    ok "Wrote $WROTE_KEYS to .env"
  elif [ "${DATRIS_NO_START:-}" = "1" ]; then
    warn "No AI provider key set. Datris will not start until you add one AI provider key to"
    warn "  $(cd "$DIR" && pwd)/.env"
    warn "  (ANTHROPIC_API_KEY, OPENAI_API_KEY, XAI_API_KEY, the AZURE_OPENAI_API_KEY/ENDPOINT/MODEL"
    warn "  trio, or AI_PROVIDER=bedrock), then run '$COMPOSE up -d' in $DIR."
  else
    die "No AI provider key set, and Datris cannot start without one. Set ANTHROPIC_API_KEY, OPENAI_API_KEY, XAI_API_KEY, the Azure OpenAI trio (AZURE_OPENAI_API_KEY, AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_MODEL), or AI_PROVIDER=bedrock, then re-run. Nothing was pulled or started."
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

  # Vector stores — pgvector is the default: it rides on Postgres (the bundled
  # image is pgvector/pgvector) and vault-init always seeds its secret, so it
  # needs no extra container and no prompt. The rest are opt-in additions,
  # bundled (profile) or external per store.
  if [ "$PG_MODE" != "none" ]; then
    if [ "$PG_MODE" = "external" ]; then
      add_summary pgvector external "via Postgres (needs the pgvector extension)"
    else
      add_summary pgvector bundled "via Postgres (no extra container)"
    fi
  fi
  # The two ways to add a vector store, shared by the interactive prompts and
  # the non-interactive DATRIS_PROFILES / <STORE>_HOST path so both write the
  # same .env lines.
  vec_bundled() { # vec_bundled store
    add_profile "$1"
    # In-network coordinates: compose service name + container port
    # (weaviate's container port is 8080; 8079 is only the host mapping).
    case "$1" in
      qdrant)   set_env QDRANT_HOST qdrant;     set_env QDRANT_PORT 6334 ;;
      weaviate) set_env WEAVIATE_HOST weaviate; set_env WEAVIATE_PORT 8080 ;;
      chroma)   set_env CHROMA_HOST chroma;     set_env CHROMA_PORT 8000 ;;
    esac
    add_summary "$1" bundled "local container"
  }
  vec_default_port() { # vec_default_port store -> external default port
    case "$1" in
      qdrant)   printf '6334' ;;
      weaviate) printf '8079' ;;
      chroma)   printf '8000' ;;
      milvus)   printf '19530' ;;
    esac
  }
  vec_external() { # vec_external store host port apikey
    _su=$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')
    set_env "${_su}_HOST" "$2"
    set_env "${_su}_PORT" "$3"
    if [ "$1" != "chroma" ]; then set_env "${_su}_API_KEY" "$4"; fi
    say "    Will use external $1 — no local container."
    add_summary "$1" external "$2"
  }

  VEC_CHOICE=""
  if [ -n "$TTY" ] && [ -z "${DATRIS_PROFILES:-}${QDRANT_HOST:-}${WEAVIATE_HOST:-}${CHROMA_HOST:-}${MILVUS_HOST:-}" ]; then
    say ""
    if [ "$PG_MODE" != "none" ]; then
      say "  Vector search runs on pgvector inside your Postgres by default —"
      say "  nothing extra to install."
      ask "  Additional vector stores (qdrant, weaviate, chroma; milvus external-only) — comma-separated, or Enter for pgvector only: "
    else
      ask "  Vector stores (qdrant, weaviate, chroma; milvus external-only) — comma-separated, or Enter for none: "
    fi
    VEC_CHOICE="$ANS"
  fi
  for store in $(printf '%s' "$VEC_CHOICE" | tr ',' ' '); do
    case "$store" in
      pgvector)
        if [ "$PG_MODE" = "none" ]; then
          warn "  pgvector rides on Postgres, which is set to 'none' — skipping."
        else
          say "    pgvector is already included via Postgres — nothing to add."
        fi
        continue ;;
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
      vec_bundled "$store"
    else
      ask "    Host: "; V_HOST="$ANS"
      [ -z "$V_HOST" ] && { warn "    No host given — skipping $store."; continue; }
      DEF_PORT=$(vec_default_port "$store")
      ask "    Port [$DEF_PORT]: "; V_PORT="${ANS:-$DEF_PORT}"
      V_KEY=""
      if [ "$store" != "chroma" ]; then
        ask_hidden "    API key (input hidden, Enter for none): "; V_KEY="$ANS"
      fi
      vec_external "$store" "$V_HOST" "$V_PORT" "$V_KEY"
    fi
  done

  # Non-interactive selection (and interactive runs that skipped the prompt
  # above because one of these was preset): a preset <STORE>_HOST is an
  # external store and wins; otherwise a store named in DATRIS_PROFILES is
  # bundled. The prompt above only runs when all of these are empty, so the
  # two paths never both write a store. Names DATRIS_PROFILES carries that are
  # not a store here (kafka, anything unknown) pass through to
  # COMPOSE_PROFILES unchanged, as before.
  if [ -z "$VEC_CHOICE" ]; then
    for store in qdrant weaviate chroma milvus; do
      _su=$(printf '%s' "$store" | tr '[:lower:]' '[:upper:]')
      eval "_vh=\${${_su}_HOST:-}"
      if [ -n "$_vh" ]; then
        eval "_vp=\${${_su}_PORT:-}"
        eval "_vk=\${${_su}_API_KEY:-}"
        vec_external "$store" "$_vh" "${_vp:-$(vec_default_port "$store")}" "$_vk"
        # External wins: drop the same store from the profiles so no unused
        # local container is started next to it.
        PROFILES=$(printf '%s' ",$PROFILES," | sed "s/,$store,/,/g; s/^,//; s/,\$//")
      elif [ "$store" != "milvus" ]; then
        case ",${DATRIS_PROFILES:-}," in
          *",$store,"*) vec_bundled "$store" ;;
        esac
      fi
    done
  fi

  # Kafka — bundled test broker / external / none (default none).
  KAFKA_MODE=""
  if [ -n "${KAFKA_BOOTSTRAP_SERVERS:-}" ]; then
    KAFKA_MODE="external"
  elif case ",${DATRIS_PROFILES:-}," in *",kafka,"*) true ;; *) false ;; esac; then
    # kafka in DATRIS_PROFILES = the bundled test broker, same as answering b.
    KAFKA_MODE="bundled"
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

  # ---- governance controls ----
  # User login, API keys, the audit log and the agent policy ship off; they
  # are switched on for production here (DATRIS_GOVERNED=1 or the prompt,
  # default No) and only on a fresh install. An existing .env is never edited.
  GOVERNED_ON="$GOVERNED_REQ"
  if [ -z "$GOVERNED_REQ" ] && [ -n "$TTY" ]; then
    ask "  Switch on the governance controls for production (user login, API keys, audit log, agent policy)? [y/N]: "
    case "$ANS" in y*|Y*) GOVERNED_ON=1 ;; esac
  fi
  if [ "$GOVERNED_ON" = "1" ]; then
    set_env USE_USER_AUTH true
    set_env USE_API_KEYS true
    set_env USE_AUDIT_LOG true
    set_env USE_AGENT_POLICY true
    add_summary governance on "user login, API keys, audit log, agent policy"
  else
    add_summary governance off "switch on for production: https://docs.datris.ai/quick-start"
  fi

  # set_env keeps the file at 600 on every write; re-apply once more as the
  # final step anyway, since the file now holds keys and DB passwords.
  chmod 600 "$ENV_FILE" 2>/dev/null || true

  say ""
  say "Install summary:"
  # shellcheck disable=SC2059
  printf "$SUMMARY"
  ok "Wrote store configuration to .env (permissions set to 600)."
fi

# Isolation default-on: mint TAP_RUNNER_TOKEN so compose up is not changeme.
if [ -f "$ENV_FILE" ]; then
  _tok=$(grep '^TAP_RUNNER_TOKEN=' "$ENV_FILE" 2>/dev/null | sed 's/^TAP_RUNNER_TOKEN=//' | tr -d '"' | tr -d "'")
  case "$_tok" in
    ""|changeme-tap-runner-token|change-me-to-a-long-random-string)
      _tok=$(openssl rand -hex 32 2>/dev/null || dd if=/dev/urandom bs=32 count=1 2>/dev/null | xxd -p | tr -d '\n')
      if [ ${#_tok} -lt 32 ]; then
        die "failed to mint TAP_RUNNER_TOKEN (need openssl or /dev/urandom)"
      fi
      set_env TAP_RUNNER_TOKEN "$_tok"
      ok "Minted TAP_RUNNER_TOKEN (tap isolation on for compose)."
      chmod 600 "$ENV_FILE" 2>/dev/null || true
      ;;
  esac
fi

# Seeding is complete: from here on the .env is kept whatever happens (a
# failed pull or `compose up` must not delete a finished configuration).
SEED_DONE=1
trap - EXIT INT TERM

# First-login and API-key hint for a fresh install with the governance
# controls on; printed at the end of a normal run and under DATRIS_NO_START.
print_governed_hint() {
  [ "$GOVERNED_ON" = "1" ] || return 0
  say ""
  say "Governance controls are on (user login, API keys, audit log, agent policy)."
  say "  First login: user admin, with the bootstrap password the server prints once to its"
  say "  log on first boot (save it; recreating the container discards that log):"
  say "    cd $DIR && $COMPOSE logs datris | grep \"Bootstrap login\""
  say "  Change it after you log in. CLI, MCP and API clients need a key from"
  say "  Configuration -> API Keys (pass it as x-api-key / DATRIS_API_KEY)."
}

# --- launch ---------------------------------------------------------------
if [ "${DATRIS_NO_START:-}" = "1" ]; then
  ok "Files written to $DIR. Skipping start (DATRIS_NO_START=1)."
  say "Run it with:  cd $DIR && $COMPOSE up -d"
  print_governed_hint
  exit 0
fi

# --- pre-upgrade self-check --------------------------------------------------
# On an upgrade (an .env already existed) run `datris doctor --pre-upgrade`
# before touching anything: it catches data on an anonymous volume that
# --remove-orphans would drop, a missing AI slot secret the new server would
# crash-loop on, a disk too full to pull, and a container still on a stale
# .env. Needs the datris CLI (pip install datris-mcp-server / brew install
# datris/tap/datris) — without it we say so and continue, as before. An error
# finding stops the upgrade; DATRIS_SKIP_DOCTOR=1 overrides. Never runs on a
# fresh install (nothing to check yet).
if [ "$FRESH_ENV" = "0" ] && [ "${DATRIS_SKIP_DOCTOR:-}" != "1" ]; then
  if command -v datris >/dev/null 2>&1 && datris doctor --help >/dev/null 2>&1; then
    say ""
    say "Running the pre-upgrade self-check (datris doctor --pre-upgrade)..."
    DOCTOR_RC=0
    ( cd "$DIR" && datris doctor --pre-upgrade --project-dir "$DIR" ) || DOCTOR_RC=$?
    case "$DOCTOR_RC" in
      0) ok "Pre-upgrade check clean." ;;
      1) warn "Pre-upgrade check has warnings (above) — continuing with the upgrade." ;;
      *) warn "Pre-upgrade check found an error (above). Fix it and re-run, or bypass with:"
         warn "  curl -fsSL https://get.datris.ai/install.sh | DATRIS_SKIP_DOCTOR=1 sh"
         die "upgrade stopped by datris doctor --pre-upgrade (exit $DOCTOR_RC)" ;;
    esac
  else
    warn ""
    warn "Skipping the pre-upgrade self-check: the datris CLI is not installed (or is older than 1.29)."
    warn "Upgrade it so the next upgrade is checked for orphaned data, missing secrets and a full disk first:"
    warn "  pip install -U datris-mcp-server   # or: brew upgrade datris"
    warn "  then: datris doctor --pre-upgrade  (see https://docs.datris.ai/doctor)"
  fi
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
print_governed_hint
say "Logs:   cd $DIR && $COMPOSE logs -f datris"
say "Stop:   cd $DIR && $COMPOSE down          (full teardown incl. opt-in services:"
say "        cd $DIR && $COMPOSE --profile \"*\" down)"
say ""
say "If Datris is useful to you, a GitHub star helps other people find it:"
say "  https://github.com/datris/datris-platform-oss"
