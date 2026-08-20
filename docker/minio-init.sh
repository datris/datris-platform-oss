#!/bin/bash
set -e

echo "Waiting for MinIO to be ready..."
until mc alias set myminio http://minio:9000 minioadmin minioadmin > /dev/null 2>&1; do
  sleep 2
done

echo "Creating buckets..."
mc mb --ignore-existing myminio/oss-config
mc mb --ignore-existing myminio/oss-raw
mc mb --ignore-existing myminio/oss-data
mc mb --ignore-existing myminio/oss-temp

echo "Waiting for Pipeline server to be reachable..."
until bash -c 'echo > /dev/tcp/datris/8080' 2>/dev/null; do
  sleep 3
done
echo "Pipeline server is reachable."

echo "Configuring MinIO webhook notification endpoint..."
# When MINIO_WEBHOOK_TOKEN is set, MinIO sends it as `Authorization: Bearer <token>`
# and the datris server (same env var) requires it — closing the otherwise
# unauthenticated /minio-events endpoint. Empty by default for back-compat.
if [ -n "${MINIO_WEBHOOK_TOKEN}" ]; then
  mc admin config set myminio notify_webhook:1 \
    endpoint=http://datris:8080/minio-events \
    auth_token="${MINIO_WEBHOOK_TOKEN}" \
    queue_limit=1000 \
    queue_dir=/tmp/minio-events
else
  echo "WARNING: MINIO_WEBHOOK_TOKEN is not set — the /minio-events webhook will be unauthenticated."
  mc admin config set myminio notify_webhook:1 \
    endpoint=http://datris:8080/minio-events \
    queue_limit=1000 \
    queue_dir=/tmp/minio-events
fi

echo "Restarting MinIO to apply notification config..."
mc admin service restart myminio --json 2>/dev/null || true

echo "Waiting for MinIO to come back..."
sleep 5
until mc alias set myminio http://minio:9000 minioadmin minioadmin > /dev/null 2>&1; do
  sleep 2
done

echo "Setting up bucket event notifications on oss-raw..."
mc event remove myminio/oss-raw --force 2>/dev/null || true
mc event add myminio/oss-raw arn:minio:sqs::1:webhook --suffix .metadata.json --event put
mc event add myminio/oss-raw arn:minio:sqs::1:webhook --suffix .pipeline.csv --event put
mc event add myminio/oss-raw arn:minio:sqs::1:webhook --suffix .pipeline.json --event put
mc event add myminio/oss-raw arn:minio:sqs::1:webhook --suffix .pipeline.xml --event put

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
