#!/bin/sh
# Switch docker-compose.yml to build from source (for local development)
sed -i '' 's/^    image: datrisai/    #image: datrisai/' docker-compose.yml
sed -i '' 's/^    #build:/    build:/' docker-compose.yml
echo "Switched to local build mode. Run: docker compose up --build"
