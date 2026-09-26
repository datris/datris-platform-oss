#!/bin/sh
# Switch docker-compose.yml to build from source (for local development).
# Only the four Datris-built services toggle (datrisai/datris-*); third-party
# images published under datrisai/ (e.g. the MinIO mirror) have no build context.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/../docker-compose.yml"
sed -i '' 's|^    image: datrisai/datris-|    #image: datrisai/datris-|' "$COMPOSE_FILE"
sed -i '' 's|^    #build:|    build:|' "$COMPOSE_FILE"
echo "Switched to local build mode. Run: docker compose up --build"
