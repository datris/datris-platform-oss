#!/bin/sh
# Switch docker-compose.yml to pull from Docker Hub (for release/external users)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/../docker-compose.yml"
sed -i '' 's/^    #image: datrisai/    image: datrisai/' "$COMPOSE_FILE"
sed -i '' 's/^    build:/    #build:/' "$COMPOSE_FILE"
echo "Switched to Docker Hub mode. Run: docker compose up -d"
