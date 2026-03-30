#!/bin/sh
# Switch docker-compose.yml to pull from Docker Hub (for release/external users)
sed -i '' 's/^    #image: datrisai/    image: datrisai/' docker-compose.yml
sed -i '' 's/^    build:/    #build:/' docker-compose.yml
echo "Switched to Docker Hub mode. Run: docker compose up -d"
