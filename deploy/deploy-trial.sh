#!/bin/bash
set -e

# =============================================================================
# Datris Shared Trial Droplet — Deployment
#
# Usage:
#   ./deploy-trial.sh
#
# Prerequisites:
#   - DO_API_TOKEN environment variable set
#   - DO_SSH_KEY_ID environment variable set
#   - Domain "datris.ai" managed in Digital Ocean DNS
#
# Creates a single shared Droplet for all free trial users.
# Uses multiTenant: true for per-request environment resolution.
# =============================================================================

: "${DO_API_TOKEN:?Error: DO_API_TOKEN environment variable is required}"
: "${DO_SSH_KEY_ID:?Error: DO_SSH_KEY_ID environment variable is required}"

DOMAIN="datris.ai"
CUSTOMER_DOMAIN="trial.${DOMAIN}"
DROPLET_NAME="datris-trial"
REGION="nyc1"
SIZE="s-4vcpu-8gb"
STORAGE_GB=50
ENVIRONMENT="oss"
AI_PROVIDER="openai"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

API="https://api.digitalocean.com/v2"
AUTH="Authorization: Bearer $DO_API_TOKEN"
JSON="Content-Type: application/json"

do_api() {
    local method="$1" endpoint="$2" data="$3"
    if [ -n "$data" ]; then
        curl -s -X "$method" "$API/$endpoint" -H "$AUTH" -H "$JSON" -d "$data"
    else
        curl -s -X "$method" "$API/$endpoint" -H "$AUTH"
    fi
}

echo "============================================"
echo "  Datris Shared Trial Droplet"
echo "============================================"
echo "  Domain:  $CUSTOMER_DOMAIN"
echo "  Region:  $REGION"
echo "  Size:    $SIZE (4 vCPU / 16 GB)"
echo "  Storage: ${STORAGE_GB} GB"
echo "============================================"
echo ""

# ---- Step 1: Create Droplet ----
echo "[1/8] Creating Droplet: $DROPLET_NAME..."
DROPLET_RESPONSE=$(do_api POST "droplets" "{
    \"name\": \"$DROPLET_NAME\",
    \"region\": \"$REGION\",
    \"size\": \"$SIZE\",
    \"image\": \"ubuntu-24-04-x64\",
    \"ssh_keys\": [\"$DO_SSH_KEY_ID\"],
    \"tags\": [\"datris-trial\"],
    \"monitoring\": true
}")

DROPLET_ID=$(echo "$DROPLET_RESPONSE" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
if [ -z "$DROPLET_ID" ] || [ "$DROPLET_ID" = "null" ]; then
    echo "Error: Failed to create Droplet"
    echo "$DROPLET_RESPONSE"
    exit 1
fi
echo "Droplet created: ID $DROPLET_ID"

# ---- Step 2: Wait for Droplet to be active ----
echo "[2/8] Waiting for Droplet to be active..."
for i in $(seq 1 60); do
    STATUS=$(do_api GET "droplets/$DROPLET_ID" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "$STATUS" = "active" ]; then
        break
    fi
    sleep 5
done

if [ "$STATUS" != "active" ]; then
    echo "Error: Droplet did not become active after 5 minutes"
    exit 1
fi

DROPLET_IP=$(do_api GET "droplets/$DROPLET_ID" | grep -o '"ip_address":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "Droplet active: $DROPLET_IP"

# ---- Step 3: Create Block Storage volume ----
echo "[3/8] Creating Block Storage volume (${STORAGE_GB} GB)..."
VOLUME_RESPONSE=$(do_api POST "volumes" "{
    \"size_gigabytes\": $STORAGE_GB,
    \"name\": \"datris-trial-data\",
    \"description\": \"Datris shared trial data volume\",
    \"region\": \"$REGION\",
    \"filesystem_type\": \"ext4\"
}")

VOLUME_ID=$(echo "$VOLUME_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$VOLUME_ID" ] || [ "$VOLUME_ID" = "null" ]; then
    echo "Warning: Failed to create volume (may already exist)"
    echo "$VOLUME_RESPONSE"
else
    echo "Volume created: $VOLUME_ID"
    echo "Attaching volume to Droplet..."
    do_api POST "volumes/$VOLUME_ID/actions" "{
        \"type\": \"attach\",
        \"droplet_id\": $DROPLET_ID
    }" > /dev/null
    sleep 10
    echo "Volume attached."
fi

# ---- Step 4: Create DNS records ----
echo "[4/8] Creating DNS records..."
SUBDOMAINS="app api mcp minio activemq vault"
for SUB in $SUBDOMAINS; do
    RECORD_NAME="${SUB}.trial"
    echo "  Creating A record: ${RECORD_NAME}.${DOMAIN} -> $DROPLET_IP"
    do_api POST "domains/$DOMAIN/records" "{
        \"type\": \"A\",
        \"name\": \"$RECORD_NAME\",
        \"data\": \"$DROPLET_IP\",
        \"ttl\": 300
    }" > /dev/null
done
echo "DNS records created."

# ---- Step 5: Wait for SSH ----
echo "[5/8] Waiting for SSH to be ready..."
for i in $(seq 1 30); do
    if ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 root@"$DROPLET_IP" "echo ready" 2>/dev/null; then
        break
    fi
    sleep 5
done

# ---- Step 6: Mount volume and copy deploy files ----
echo "[6/8] Mounting volume and copying deploy files..."

ssh -o StrictHostKeyChecking=no root@"$DROPLET_IP" bash -s << 'REMOTE_MOUNT'
set -e
VOLUME_DEV=$(ls /dev/disk/by-id/scsi-0DO_Volume_* 2>/dev/null | head -1)
if [ -z "$VOLUME_DEV" ]; then
    echo "Warning: Block storage volume not found, using local disk"
    mkdir -p /data
else
    mkdir -p /data
    if ! mountpoint -q /data; then
        mount -o defaults,nofail,discard,noatime "$VOLUME_DEV" /data
    fi
    if ! grep -q "/data" /etc/fstab; then
        echo "$VOLUME_DEV /data ext4 defaults,nofail,discard,noatime 0 2" >> /etc/fstab
    fi
    echo "Volume mounted at /data"
fi
REMOTE_MOUNT

scp -o StrictHostKeyChecking=no -r "$SCRIPT_DIR" root@"$DROPLET_IP":/tmp/deploy

# ---- Step 7: Use trial application config ----
echo "[7/8] Configuring for multi-tenant trial mode..."
ssh -o StrictHostKeyChecking=no root@"$DROPLET_IP" \
    "cp /tmp/deploy/config/application-trial.yaml /tmp/deploy/config/application.yaml"

# ---- Step 8: Run deploy.sh ----
echo "[8/8] Running deploy.sh on Droplet..."
ssh -o StrictHostKeyChecking=no root@"$DROPLET_IP" \
    "cd /tmp/deploy && bash deploy.sh $CUSTOMER_DOMAIN $ENVIRONMENT $AI_PROVIDER none"

echo ""
echo "============================================"
echo "  Shared Trial Droplet is ready!"
echo "============================================"
echo ""
echo "  Droplet ID:  $DROPLET_ID"
echo "  Droplet IP:  $DROPLET_IP"
echo "  Volume ID:   $VOLUME_ID"
echo ""
echo "  Trial URLs:"
echo "    UI:   https://app.trial.datris.ai"
echo "    API:  https://api.trial.datris.ai"
echo "    MCP:  https://mcp.trial.datris.ai/sse"
echo ""
echo "  Admin:"
echo "    SSH:  ssh root@$DROPLET_IP"
echo "    Creds: /data/secrets/datris.env"
echo ""
echo "  Next steps:"
echo "    1. Add TRIAL_* env vars to datris-website .env:"
echo "       TRIAL_VAULT_ADDR=http://$DROPLET_IP:8200"
echo "       TRIAL_VAULT_TOKEN=<from /data/secrets/vault-token>"
echo "       TRIAL_MINIO_ACCESS_KEY=<from /data/secrets/datris.env>"
echo "       TRIAL_MINIO_SECRET_KEY=<from /data/secrets/datris.env>"
echo "       TRIAL_PG_PASSWORD=<from /data/secrets/datris.env>"
echo "       TRIAL_MONGO_USER=<from /data/secrets/datris.env>"
echo "       TRIAL_MONGO_PASSWORD=<from /data/secrets/datris.env>"
echo "       TRIAL_ACTIVEMQ_USER=<from /data/secrets/datris.env>"
echo "       TRIAL_ACTIVEMQ_PASSWORD=<from /data/secrets/datris.env>"
echo ""
echo "============================================"
