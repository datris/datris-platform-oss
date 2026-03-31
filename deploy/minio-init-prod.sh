#!/bin/sh
set -e

echo "Waiting for MinIO to be ready..."
until mc alias set myminio http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" > /dev/null 2>&1; do
  sleep 2
done

echo "Creating buckets for environment: $ENVIRONMENT"
mc mb --ignore-existing myminio/${ENVIRONMENT}-config
mc mb --ignore-existing myminio/${ENVIRONMENT}-raw
mc mb --ignore-existing myminio/${ENVIRONMENT}-raw-plus
mc mb --ignore-existing myminio/${ENVIRONMENT}-temp

echo "Waiting for Datris server to be reachable..."
until wget -q --spider http://datris:8080/api/v1/version 2>/dev/null; do
  sleep 3
done

echo "Configuring MinIO webhook notification endpoint..."
mc admin config set myminio notify_webhook:1 \
  endpoint=http://datris:8080/minio-events \
  queue_limit=1000 \
  queue_dir=/tmp/minio-events

echo "Restarting MinIO to apply notification config..."
mc admin service restart myminio

echo "Waiting for MinIO to come back..."
sleep 5
until mc alias set myminio http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" > /dev/null 2>&1; do
  sleep 2
done

echo "Setting up bucket event notifications on ${ENVIRONMENT}-raw..."
mc event add myminio/${ENVIRONMENT}-raw arn:minio:sqs::1:webhook --suffix .metadata.json --event put
mc event add myminio/${ENVIRONMENT}-raw arn:minio:sqs::1:webhook --suffix .pipeline.csv --event put
mc event add myminio/${ENVIRONMENT}-raw arn:minio:sqs::1:webhook --suffix .pipeline.json --event put
mc event add myminio/${ENVIRONMENT}-raw arn:minio:sqs::1:webhook --suffix .pipeline.xml --event put

echo "MinIO initialization complete."
