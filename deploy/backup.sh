#!/bin/bash
set -e

# Daily backup script for Datris Platform
# Run via cron: 0 3 * * * /opt/datris/backup.sh >> /var/log/datris-backup.log 2>&1

BACKUP_DIR="/data/backups"
DATE=$(date +%Y%m%d-%H%M%S)
RETENTION_DAYS=7

source /data/secrets/datris.env

mkdir -p "$BACKUP_DIR"

echo "[$DATE] Starting Datris backup..."

# PostgreSQL backup
echo "Backing up PostgreSQL..."
docker exec postgres pg_dump -U "$PG_USERNAME" -d datris --clean --if-exists \
    > "$BACKUP_DIR/postgres-$DATE.sql"
gzip "$BACKUP_DIR/postgres-$DATE.sql"
echo "PostgreSQL backup complete: postgres-$DATE.sql.gz"

# MongoDB backup
echo "Backing up MongoDB..."
docker exec mongodb mongodump \
    --username="$MONGO_USERNAME" \
    --password="$MONGO_PASSWORD" \
    --authenticationDatabase=admin \
    --db="$ENVIRONMENT" \
    --archive=/tmp/mongodb-backup.gz \
    --gzip
docker cp mongodb:/tmp/mongodb-backup.gz "$BACKUP_DIR/mongodb-$DATE.gz"
docker exec mongodb rm /tmp/mongodb-backup.gz
echo "MongoDB backup complete: mongodb-$DATE.gz"

# Clean up old backups
echo "Cleaning up backups older than $RETENTION_DAYS days..."
find "$BACKUP_DIR" -type f -mtime +$RETENTION_DAYS -delete

echo "[$DATE] Backup complete."
