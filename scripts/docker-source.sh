#!/bin/sh
# Switch docker-compose.yml to build from source (for local development)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/../docker-compose.yml"
sed -i '' 's/^    image: datrisai/    #image: datrisai/' "$COMPOSE_FILE"
sed -i '' 's/^    #build:/    build:/' "$COMPOSE_FILE"
echo "Switched to local build mode. Run: docker compose up --build"
