#!/bin/sh
set -e

echo "Waiting for MinIO to be ready..."
until mc alias set myminio http://minio:9000 minioadmin minioadmin > /dev/null 2>&1; do
  sleep 2
done

echo "Creating buckets..."
mc mb --ignore-existing myminio/oss-config
mc mb --ignore-existing myminio/oss-raw
mc mb --ignore-existing myminio/oss-raw-plus
mc mb --ignore-existing myminio/oss-temp

echo "Waiting for Pipeline server to be reachable..."
until wget -q --spider http://pipeline:8080/api/v1/version 2>/dev/null; do
  sleep 3
done

echo "Configuring MinIO webhook notification endpoint..."
mc admin config set myminio notify_webhook:1 \
  endpoint=http://pipeline:8080/minio-events \
  queue_limit=1000 \
  queue_dir=/tmp/minio-events

echo "Restarting MinIO to apply notification config..."
mc admin service restart myminio

echo "Waiting for MinIO to come back..."
sleep 5
until mc alias set myminio http://minio:9000 minioadmin minioadmin > /dev/null 2>&1; do
  sleep 2
done

echo "Setting up bucket event notifications on oss-raw..."
mc event add myminio/oss-raw arn:minio:sqs::1:webhook --suffix .metadata.json --event put
mc event add myminio/oss-raw arn:minio:sqs::1:webhook --suffix .dataset.csv --event put
mc event add myminio/oss-raw arn:minio:sqs::1:webhook --suffix .dataset.json --event put
mc event add myminio/oss-raw arn:minio:sqs::1:webhook --suffix .dataset.xml --event put

echo "MinIO initialization complete."
echo ""
echo "============================================"
echo "  Datris Platform is ready!"
echo "============================================"
echo ""
echo "  Pipeline UI:     http://localhost:4200"
echo "  Pipeline API:    http://localhost:8080"
echo "  MCP Server:      http://localhost:3000/sse"
echo "  MinIO Console:   http://localhost:9001"
echo "  Kafka UI:        http://localhost:8085"
echo "  ActiveMQ:        http://localhost:8161"
echo "  Vault:           http://localhost:8200"
echo ""
echo "============================================"
