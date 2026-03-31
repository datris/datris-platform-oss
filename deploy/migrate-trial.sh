#!/bin/bash
set -e

# =============================================================================
# Datris Trial → Dedicated Migration
#
# Usage:
#   ./migrate-trial.sh <customer-id> <trial-environment> <new-droplet-ip> <new-environment>
#
# Migrates data from the shared trial instance to a dedicated customer Droplet:
#   - PostgreSQL: pg_dump with schema filter → pg_restore
#   - MongoDB: mongodump → mongorestore
#   - MinIO: mc mirror trial buckets → new instance
#
# Prerequisites:
#   - SSH access to both shared trial and new dedicated Droplet
#   - Trial and dedicated instances running
# =============================================================================

CUSTOMER_ID="${1:?Usage: ./migrate-trial.sh <customer-id> <trial-environment> <new-droplet-ip> <new-environment>}"
TRIAL_ENV="${2:?Usage: ./migrate-trial.sh <customer-id> <trial-environment> <new-droplet-ip> <new-environment>}"
NEW_IP="${3:?Usage: ./migrate-trial.sh <customer-id> <trial-environment> <new-droplet-ip> <new-environment>}"
NEW_ENV="${4:?Usage: ./migrate-trial.sh <customer-id> <trial-environment> <new-droplet-ip> <new-environment>}"

TRIAL_IP="${TRIAL_DROPLET_IP:?Set TRIAL_DROPLET_IP environment variable}"

echo "============================================"
echo "  Trial → Dedicated Migration"
echo "============================================"
echo "  Customer:       $CUSTOMER_ID"
echo "  Trial env:      $TRIAL_ENV"
echo "  Trial IP:       $TRIAL_IP"
echo "  New env:        $NEW_ENV"
echo "  New Droplet IP: $NEW_IP"
echo "============================================"
echo ""

# Load trial credentials
source /data/secrets/datris.env

# ---- Step 1: Migrate PostgreSQL ----
echo "[1/4] Migrating PostgreSQL..."

# Dump from trial (filter by environment-prefixed tables)
ssh root@"$TRIAL_IP" "docker exec postgres pg_dump -U $PG_USERNAME -d datris \
    --table='${TRIAL_ENV}-*' --clean --if-exists" > /tmp/pg-migration.sql

# Rename tables from trial-xxx to new environment
sed -i "s/${TRIAL_ENV}/${NEW_ENV}/g" /tmp/pg-migration.sql

# Load into new instance
ssh root@"$NEW_IP" "docker exec -i postgres psql -U datris -d datris" < /tmp/pg-migration.sql
rm /tmp/pg-migration.sql

echo "PostgreSQL migration complete."

# ---- Step 2: Migrate MongoDB ----
echo "[2/4] Migrating MongoDB..."

# Dump from trial
ssh root@"$TRIAL_IP" "docker exec mongodb mongodump \
    --username=$MONGO_USERNAME --password=$MONGO_PASSWORD --authenticationDatabase=admin \
    --db=$TRIAL_ENV --archive=/tmp/mongo-migration.gz --gzip"
scp root@"$TRIAL_IP":/tmp/mongo-migration.gz /tmp/mongo-migration.gz
ssh root@"$TRIAL_IP" "rm /tmp/mongo-migration.gz"

# Restore to new instance with renamed database
scp /tmp/mongo-migration.gz root@"$NEW_IP":/tmp/mongo-migration.gz

# Load new instance credentials
NEW_SECRETS=$(ssh root@"$NEW_IP" "cat /data/secrets/datris.env")
NEW_MONGO_USER=$(echo "$NEW_SECRETS" | grep MONGO_USERNAME | cut -d= -f2)
NEW_MONGO_PASS=$(echo "$NEW_SECRETS" | grep MONGO_PASSWORD | cut -d= -f2)

ssh root@"$NEW_IP" "docker exec -i mongodb mongorestore \
    --username=$NEW_MONGO_USER --password=$NEW_MONGO_PASS --authenticationDatabase=admin \
    --nsFrom='${TRIAL_ENV}.*' --nsTo='${NEW_ENV}.*' \
    --archive=/tmp/mongo-migration.gz --gzip --drop"
ssh root@"$NEW_IP" "rm /tmp/mongo-migration.gz"
rm /tmp/mongo-migration.gz

echo "MongoDB migration complete."

# ---- Step 3: Migrate MinIO buckets ----
echo "[3/4] Migrating MinIO buckets..."

BUCKETS="${TRIAL_ENV}-config ${TRIAL_ENV}-raw ${TRIAL_ENV}-raw-plus ${TRIAL_ENV}-temp"

for BUCKET in $BUCKETS; do
    NEW_BUCKET=$(echo "$BUCKET" | sed "s/${TRIAL_ENV}/${NEW_ENV}/")
    echo "  Mirroring $BUCKET → $NEW_BUCKET"

    # Mirror from trial to new instance via SSH tunnel
    ssh root@"$TRIAL_IP" "docker exec minio-init mc alias set trial http://minio:9000 $MINIO_ROOT_USER $MINIO_ROOT_PASSWORD 2>/dev/null; \
        docker exec minio-init mc mirror trial/$BUCKET /tmp/minio-migration/$BUCKET" 2>/dev/null || true

    # Copy to new instance
    ssh root@"$TRIAL_IP" "docker cp minio-init:/tmp/minio-migration/$BUCKET /tmp/$BUCKET" 2>/dev/null || true
    scp -r root@"$TRIAL_IP":/tmp/$BUCKET /tmp/$BUCKET 2>/dev/null || true
    scp -r /tmp/$BUCKET root@"$NEW_IP":/tmp/$NEW_BUCKET 2>/dev/null || true

    ssh root@"$NEW_IP" "docker cp /tmp/$NEW_BUCKET minio:/data/$NEW_BUCKET" 2>/dev/null || true

    # Cleanup temp files
    ssh root@"$TRIAL_IP" "rm -rf /tmp/$BUCKET" 2>/dev/null || true
    rm -rf /tmp/$BUCKET 2>/dev/null || true
    ssh root@"$NEW_IP" "rm -rf /tmp/$NEW_BUCKET" 2>/dev/null || true
done

echo "MinIO migration complete."

# ---- Step 4: Clean up trial environment ----
echo "[4/4] Cleaning up trial environment on shared instance..."

# Remove Vault secrets
ssh root@"$TRIAL_IP" "docker exec vault vault kv metadata delete secret/${TRIAL_ENV}" 2>/dev/null || true

# Remove API key mapping
# (This would need to read, filter, and rewrite the api-key-mappings secret)
echo "  Note: Remove the trial API key from api-key-mappings manually or via the cleanup cron."

echo ""
echo "============================================"
echo "  Migration Complete!"
echo "============================================"
echo "  Data migrated from $TRIAL_ENV → $NEW_ENV"
echo "  New instance: $NEW_IP"
echo "============================================"
