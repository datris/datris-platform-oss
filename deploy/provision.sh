#!/bin/bash
set -e

# =============================================================================
# Datris Platform — Automated Provisioning via Digital Ocean API
#
# Usage:
#   ./provision.sh <customer-name> <region> <droplet-size> <storage-gb> <ai-provider> <ai-api-key>
#
# Example:
#   ./provision.sh acme nyc1 s-2vcpu-8gb 25 anthropic sk-ant-xxx
#
# Prerequisites:
#   - DO_API_TOKEN environment variable set
#   - DO_SSH_KEY_ID environment variable set (your SSH key ID in Digital Ocean)
#   - Domain "datris.ai" managed in Digital Ocean DNS
#   - deploy/ directory with all deploy files
#
# What this script does:
#   1. Creates a Droplet
#   2. Attaches Block Storage at /data
#   3. Creates 6 DNS A records ({sub}.{customer}.datris.ai)
#   4. Waits for SSH to be ready
#   5. Copies deploy files to the Droplet
#   6. Runs deploy.sh remotely
# =============================================================================

CUSTOMER="${1:?Usage: ./provision.sh <customer-name> <region> <droplet-size> <storage-gb> <ai-provider> <ai-api-key>}"
REGION="${2:?Usage: ./provision.sh <customer-name> <region> <droplet-size> <storage-gb> <ai-provider> <ai-api-key>}"
SIZE="${3:?Usage: ./provision.sh <customer-name> <region> <droplet-size> <storage-gb> <ai-provider> <ai-api-key>}"
STORAGE_GB="${4:?Usage: ./provision.sh <customer-name> <region> <droplet-size> <storage-gb> <ai-provider> <ai-api-key>}"
AI_PROVIDER="${5:?Usage: ./provision.sh <customer-name> <region> <droplet-size> <storage-gb> <ai-provider> <ai-api-key>}"
AI_API_KEY="${6:?Usage: ./provision.sh <customer-name> <region> <droplet-size> <storage-gb> <ai-provider> <ai-api-key>}"

: "${DO_API_TOKEN:?Error: DO_API_TOKEN environment variable is required}"
: "${DO_SSH_KEY_ID:?Error: DO_SSH_KEY_ID environment variable is required}"

DOMAIN="datris.ai"
CUSTOMER_DOMAIN="${CUSTOMER}.${DOMAIN}"
DROPLET_NAME="${CUSTOMER}-datris"
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
echo "  Datris Platform — Provisioning"
echo "============================================"
echo "  Customer:    $CUSTOMER"
echo "  Domain:      $CUSTOMER_DOMAIN"
echo "  Region:      $REGION"
echo "  Size:        $SIZE"
echo "  Storage:     ${STORAGE_GB} GB"
echo "  AI Provider: $AI_PROVIDER"
echo "============================================"
echo ""

# ---- Step 1: Create Droplet ----
echo "[1/7] Creating Droplet: $DROPLET_NAME..."
DROPLET_RESPONSE=$(do_api POST "droplets" "{
    \"name\": \"$DROPLET_NAME\",
    \"region\": \"$REGION\",
    \"size\": \"$SIZE\",
    \"image\": \"ubuntu-24-04-x64\",
    \"ssh_keys\": [\"$DO_SSH_KEY_ID\"],
    \"tags\": [\"datris-customer\"],
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
echo "[2/7] Waiting for Droplet to be active..."
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
echo "[3/7] Creating Block Storage volume (${STORAGE_GB} GB)..."
VOLUME_RESPONSE=$(do_api POST "volumes" "{
    \"size_gigabytes\": $STORAGE_GB,
    \"name\": \"${CUSTOMER}-datris-data\",
    \"description\": \"Datris data volume for $CUSTOMER\",
    \"region\": \"$REGION\",
    \"filesystem_type\": \"ext4\"
}")

VOLUME_ID=$(echo "$VOLUME_RESPONSE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$VOLUME_ID" ] || [ "$VOLUME_ID" = "null" ]; then
    echo "Error: Failed to create volume"
    echo "$VOLUME_RESPONSE"
    exit 1
fi
echo "Volume created: $VOLUME_ID"

# Attach volume to Droplet
echo "Attaching volume to Droplet..."
do_api POST "volumes/$VOLUME_ID/actions" "{
    \"type\": \"attach\",
    \"droplet_id\": $DROPLET_ID
}" > /dev/null

sleep 10
echo "Volume attached."

# ---- Step 4: Create DNS records ----
echo "[4/7] Creating DNS records..."
SUBDOMAINS="app api mcp minio activemq vault"
for SUB in $SUBDOMAINS; do
    RECORD_NAME="${SUB}.${CUSTOMER}"
    echo "  Creating A record: ${RECORD_NAME}.${DOMAIN} -> $DROPLET_IP"
    do_api POST "domains/$DOMAIN/records" "{
        \"type\": \"A\",
        \"name\": \"$RECORD_NAME\",
        \"data\": \"$DROPLET_IP\",
        \"ttl\": 300
    }" > /dev/null
done
echo "DNS records created. Allow 1-2 minutes for propagation."

# ---- Step 5: Wait for SSH ----
echo "[5/7] Waiting for SSH to be ready..."
for i in $(seq 1 30); do
    if ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 root@"$DROPLET_IP" "echo ready" 2>/dev/null; then
        break
    fi
    sleep 5
done

# ---- Step 6: Mount volume and copy deploy files ----
echo "[6/7] Mounting volume and copying deploy files..."

# Mount the block storage volume at /data
ssh -o StrictHostKeyChecking=no root@"$DROPLET_IP" bash -s << 'REMOTE_MOUNT'
set -e
# Find the volume device (Digital Ocean volumes appear as /dev/disk/by-id/scsi-0DO_Volume_*)
VOLUME_DEV=$(ls /dev/disk/by-id/scsi-0DO_Volume_* 2>/dev/null | head -1)
if [ -z "$VOLUME_DEV" ]; then
    echo "Error: Block storage volume not found"
    exit 1
fi
mkdir -p /data
if ! mountpoint -q /data; then
    mount -o defaults,nofail,discard,noatime "$VOLUME_DEV" /data
fi
# Add to fstab for persistence across reboots
if ! grep -q "/data" /etc/fstab; then
    echo "$VOLUME_DEV /data ext4 defaults,nofail,discard,noatime 0 2" >> /etc/fstab
fi
echo "Volume mounted at /data"
REMOTE_MOUNT

# Copy deploy files
scp -o StrictHostKeyChecking=no -r "$SCRIPT_DIR" root@"$DROPLET_IP":/tmp/deploy

# ---- Step 7: Run deploy.sh ----
echo "[7/7] Running deploy.sh on Droplet..."
ssh -o StrictHostKeyChecking=no root@"$DROPLET_IP" \
    "cd /tmp/deploy && bash deploy.sh $CUSTOMER_DOMAIN $CUSTOMER $AI_PROVIDER $AI_API_KEY"

echo ""
echo "============================================"
echo "  Provisioning Complete!"
echo "============================================"
echo ""
echo "  Droplet ID:  $DROPLET_ID"
echo "  Droplet IP:  $DROPLET_IP"
echo "  Volume ID:   $VOLUME_ID"
echo ""
echo "  Platform UI:  https://app.$CUSTOMER_DOMAIN"
echo "  REST API:     https://api.$CUSTOMER_DOMAIN"
echo "  MCP Server:   https://mcp.$CUSTOMER_DOMAIN/sse"
echo ""
echo "  SSH:          ssh root@$DROPLET_IP"
echo ""
echo "============================================"
