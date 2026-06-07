#!/bin/sh
# Datris one-command local install — no git required.
#
#   curl -fsSL https://get.datris.ai/install.sh | sh
#
# Pulls pre-built images from Docker Hub (datrisai/*) and the few runtime
# files Compose needs from the public repo, drops them into ./datris, seeds a
# .env, and runs `docker compose up -d`. Nothing is built from source.
#
# Honors these env vars for non-interactive / CI use:
#   DATRIS_DIR        install directory            (default: ./datris)
#   DATRIS_REF        repo ref to fetch files from (default: main)
#   ANTHROPIC_API_KEY pre-set Anthropic key        (skips the prompt)
#   OPENAI_API_KEY    pre-set OpenAI key           (skips the prompt)
#   DATRIS_NO_START=1 write files but don't run compose
set -eu

REPO_RAW="https://raw.githubusercontent.com/datris/datris-platform-oss"
REF="${DATRIS_REF:-main}"
DIR="${DATRIS_DIR:-./datris}"

# Files Compose bind-mounts from the working dir. This is the exact set that
# makes a git-free checkout unnecessary — keep in sync with docker-compose.yml.
FILES="docker-compose.yml docker/vault-init.sh docker/minio-init.sh docker/config/application.yaml"

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

# --- fetch runtime files --------------------------------------------------
for f in $FILES; do
  mkdir -p "$DIR/$(dirname "$f")"
  curl -fsSL "$REPO_RAW/$REF/$f" -o "$DIR/$f" || die "could not download $f from $REPO_RAW/$REF/$f"
done
chmod +x "$DIR/docker/vault-init.sh" "$DIR/docker/minio-init.sh"
ok "Fetched compose file and runtime scripts."

# --- seed .env ------------------------------------------------------------
ENV_FILE="$DIR/.env"
if [ -f "$ENV_FILE" ]; then
  warn "Existing .env found — leaving it untouched."
else
  curl -fsSL "$REPO_RAW/$REF/.env.example" -o "$ENV_FILE" || die "could not download .env.example"

  AKEY="${ANTHROPIC_API_KEY:-}"
  OKEY="${OPENAI_API_KEY:-}"

  # Detect a *usable* controlling terminal. `[ -r /dev/tty ]` is not enough: the
  # device node can be readable yet fail to open ("Device not configured") when
  # there's no controlling terminal (CI, some `curl | sh` contexts). Actually
  # try to open it (error suppressed) so we fall back cleanly instead of aborting.
  TTY=""
  if { : < /dev/tty; } 2>/dev/null; then TTY="/dev/tty"; fi

  # If a provider key was inherited from the shell environment, never adopt it
  # silently — a stray ANTHROPIC/OPENAI_API_KEY in a shell rc shouldn't decide
  # your provider without you knowing. Announce what was found, and when
  # interactive let the user keep it or ignore it and enter their own.
  if [ -n "$AKEY" ] || [ -n "$OKEY" ]; then
    [ -n "$AKEY" ] && say "Detected ANTHROPIC_API_KEY in your environment ($(mask "$AKEY")) — will use Anthropic/Claude."
    [ -n "$OKEY" ] && say "Detected OPENAI_API_KEY in your environment ($(mask "$OKEY")) — will use OpenAI."
    if [ -n "$TTY" ]; then
      printf "  Use the detected key(s)? [Y/n] (n = ignore and enter your own): " > "$TTY"
      ans=""
      read -r ans < "$TTY" || ans=""
      case "$ans" in
        n*|N*) warn "Ignoring environment keys."; AKEY=""; OKEY="" ;;
      esac
    else
      say "Non-interactive — using the detected key(s)."
    fi
  fi

  # Prompt only when no key was supplied/kept and we have a real terminal.
  if [ -z "$AKEY" ] && [ -z "$OKEY" ] && [ -n "$TTY" ]; then
    say ""
    say "Datris needs one AI provider key. Anthropic (Claude) is recommended —"
    say "CodeGen, AI data-quality rules, and NL→SQL are much better with Claude."
    printf "  Anthropic API key (sk-ant-...), or press Enter to use OpenAI instead: " > "$TTY"
    read -r AKEY < "$TTY" || AKEY=""
    if [ -z "$AKEY" ]; then
      printf "  OpenAI API key (sk-...), or press Enter to skip for now: " > "$TTY"
      read -r OKEY < "$TTY" || OKEY=""
    fi
  fi

  # Write whichever key we have into the .env (portable sed -i).
  if [ -n "$AKEY" ]; then
    sed "s|^ANTHROPIC_API_KEY=.*|ANTHROPIC_API_KEY=$AKEY|" "$ENV_FILE" > "$ENV_FILE.tmp" && mv "$ENV_FILE.tmp" "$ENV_FILE"
    ok "Wrote ANTHROPIC_API_KEY to .env"
  fi
  if [ -n "$OKEY" ]; then
    sed "s|^OPENAI_API_KEY=.*|OPENAI_API_KEY=$OKEY|" "$ENV_FILE" > "$ENV_FILE.tmp" && mv "$ENV_FILE.tmp" "$ENV_FILE"
    ok "Wrote OPENAI_API_KEY to .env"
  fi
  if [ -z "$AKEY" ] && [ -z "$OKEY" ]; then
    warn "No AI key set. Datris will start, but AI features stay off until you add a"
    warn "key in $ENV_FILE (or the Configuration tab in the UI) and re-run 'up -d'."
  fi
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
# removes containers no longer in the compose file; named volumes (your data) survive.
( cd "$DIR" && $COMPOSE pull && $COMPOSE up -d --remove-orphans )

ok ""
ok "Datris is starting up."
say "  UI:   http://localhost:4200"
say "  API:  http://localhost:8080"
say "  MCP:  http://localhost:3000"
say ""
say "First boot pulls an embedding model (~2.2 GB) — give it a couple minutes."
say "Logs:   cd $DIR && $COMPOSE logs -f datris"
say "Stop:   cd $DIR && $COMPOSE down"
