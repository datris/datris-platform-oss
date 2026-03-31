#!/bin/bash
set -e

# =============================================================================
# Datris Platform — One-Command Deployment
# Usage: ./deploy.sh <customer-domain> <environment> <ai-provider> <ai-api-key>
#
# Example:
#   ./deploy.sh acme.datris.ai acme anthropic sk-ant-xxx
#
# Prerequisites:
#   - Ubuntu 24.04 LTS Droplet
#   - Block Storage mounted at /data
#   - Root or sudo access
# =============================================================================

CUSTOMER_DOMAIN="${1:?Usage: ./deploy.sh <customer-domain> <environment> <ai-provider> <ai-api-key>}"
ENVIRONMENT="${2:?Usage: ./deploy.sh <customer-domain> <environment> <ai-provider> <ai-api-key>}"
AI_PROVIDER="${3:?Usage: ./deploy.sh <customer-domain> <environment> <ai-provider> <ai-api-key>}"
AI_API_KEY="${4:?Usage: ./deploy.sh <customer-domain> <environment> <ai-provider> <ai-api-key>}"

INSTALL_DIR="/opt/datris"
SECRETS_DIR="/data/secrets"

echo "============================================"
echo "  Datris Platform Deployment"
echo "============================================"
echo "  Domain:      $CUSTOMER_DOMAIN"
echo "  Environment: $ENVIRONMENT"
echo "  AI Provider: $AI_PROVIDER"
echo "============================================"
echo ""

# ---- Step 1: Install Docker if not present ----
if ! command -v docker &> /dev/null; then
    echo "[1/8] Installing Docker..."
    apt-get update -qq
    apt-get install -y -qq ca-certificates curl gnupg
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update -qq
    apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin
    systemctl enable docker
    systemctl start docker
    echo "Docker installed."
else
    echo "[1/8] Docker already installed."
fi

# ---- Step 2: Create directories ----
echo "[2/8] Creating directories..."
mkdir -p "$INSTALL_DIR" "$SECRETS_DIR"
mkdir -p /data/{postgres,mongodb,minio,vault,kafka,zookeeper,certbot/conf,certbot/www,backups}

# ---- Step 3: Copy deploy files ----
echo "[3/8] Copying deploy files..."
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cp "$SCRIPT_DIR/docker-compose.prod.yml" "$INSTALL_DIR/"
cp -r "$SCRIPT_DIR/nginx" "$INSTALL_DIR/"
cp -r "$SCRIPT_DIR/vault" "$INSTALL_DIR/"
cp -r "$SCRIPT_DIR/config" "$INSTALL_DIR/"
cp "$SCRIPT_DIR/minio-init-prod.sh" "$INSTALL_DIR/"
cp "$SCRIPT_DIR/backup.sh" "$INSTALL_DIR/"

# ---- Step 4: Generate credentials ----
echo "[4/8] Generating credentials..."
gen_password() { openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 32; }

PG_USERNAME="datris"
PG_PASSWORD="$(gen_password)"
MONGO_USERNAME="datris"
MONGO_PASSWORD="$(gen_password)"
MINIO_ROOT_USER="datris"
MINIO_ROOT_PASSWORD="$(gen_password)"
ACTIVEMQ_USERNAME="datris"
ACTIVEMQ_PASSWORD="$(gen_password)"
PIPELINE_API_KEY="$(openssl rand -hex 16)"

# Set AI defaults based on provider
case "$AI_PROVIDER" in
    anthropic)
        AI_ENDPOINT="https://api.anthropic.com/v1/messages"
        AI_MODEL="claude-sonnet-4-6"
        EMBEDDING_ENDPOINT="https://api.openai.com/v1/embeddings"
        EMBEDDING_MODEL="text-embedding-3-small"
        EMBEDDING_API_KEY=""
        ;;
    openai)
        AI_ENDPOINT="https://api.openai.com/v1/chat/completions"
        AI_MODEL="gpt-4.1"
        EMBEDDING_ENDPOINT="https://api.openai.com/v1/embeddings"
        EMBEDDING_MODEL="text-embedding-3-small"
        EMBEDDING_API_KEY="$AI_API_KEY"
        ;;
    *)
        echo "Error: Unsupported AI provider '$AI_PROVIDER'. Use 'anthropic' or 'openai'."
        exit 1
        ;;
esac

# Save credentials to env file
cat > "$SECRETS_DIR/datris.env" << EOF
ENVIRONMENT=$ENVIRONMENT
CUSTOMER_DOMAIN=$CUSTOMER_DOMAIN
PG_USERNAME=$PG_USERNAME
PG_PASSWORD=$PG_PASSWORD
MONGO_USERNAME=$MONGO_USERNAME
MONGO_PASSWORD=$MONGO_PASSWORD
MINIO_ROOT_USER=$MINIO_ROOT_USER
MINIO_ROOT_PASSWORD=$MINIO_ROOT_PASSWORD
ACTIVEMQ_USERNAME=$ACTIVEMQ_USERNAME
ACTIVEMQ_PASSWORD=$ACTIVEMQ_PASSWORD
PIPELINE_API_KEY=$PIPELINE_API_KEY
AI_PROVIDER=$AI_PROVIDER
AI_ENDPOINT=$AI_ENDPOINT
AI_MODEL=$AI_MODEL
AI_API_KEY=$AI_API_KEY
EMBEDDING_ENDPOINT=$EMBEDDING_ENDPOINT
EMBEDDING_MODEL=$EMBEDDING_MODEL
EMBEDDING_API_KEY=$EMBEDDING_API_KEY
KAFKA_ENABLED=false
EOF
chmod 600 "$SECRETS_DIR/datris.env"
echo "Credentials generated and saved to $SECRETS_DIR/datris.env"

# ---- Step 5: Configure Nginx with customer domain ----
echo "[5/8] Configuring Nginx for $CUSTOMER_DOMAIN..."
sed -i "s/CUSTOMER_DOMAIN/$CUSTOMER_DOMAIN/g" "$INSTALL_DIR/nginx/nginx.conf"
sed -i "s/CUSTOMER_DOMAIN/$CUSTOMER_DOMAIN/g" "$INSTALL_DIR/nginx/nginx-init.conf"

# Start with pre-TLS config (port 80 only)
cp "$INSTALL_DIR/nginx/nginx-init.conf" "$INSTALL_DIR/nginx/nginx-active.conf"

# Update docker-compose to use the active nginx config
sed -i "s|./nginx/nginx.conf:/etc/nginx/nginx.conf:ro|./nginx/nginx-active.conf:/etc/nginx/nginx.conf:ro|" \
    "$INSTALL_DIR/docker-compose.prod.yml"

# ---- Step 6: Pull images and start services ----
echo "[6/8] Pulling Docker images and starting services..."
cd "$INSTALL_DIR"
set -a
source "$SECRETS_DIR/datris.env"
set +a

docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d

echo "Waiting for services to be healthy..."
sleep 30

# ---- Step 7: Obtain TLS certificates ----
echo "[7/8] Obtaining TLS certificates..."
DOMAINS="-d app.$CUSTOMER_DOMAIN -d api.$CUSTOMER_DOMAIN -d mcp.$CUSTOMER_DOMAIN -d minio.$CUSTOMER_DOMAIN -d activemq.$CUSTOMER_DOMAIN -d vault.$CUSTOMER_DOMAIN"

docker compose -f docker-compose.prod.yml run --rm certbot \
    certbot certonly --webroot -w /var/www/certbot \
    --email admin@datris.ai --agree-tos --no-eff-email \
    $DOMAINS

# Switch to full SSL nginx config
cp "$INSTALL_DIR/nginx/nginx.conf" "$INSTALL_DIR/nginx/nginx-active.conf"
docker compose -f docker-compose.prod.yml restart nginx
echo "TLS certificates obtained and Nginx restarted with SSL."

# ---- Step 8: Install systemd service + backup cron ----
echo "[8/8] Setting up auto-start and backups..."
cp "$SCRIPT_DIR/systemd/datris.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable datris

chmod +x "$INSTALL_DIR/backup.sh"
(crontab -l 2>/dev/null; echo "0 3 * * * $INSTALL_DIR/backup.sh >> /var/log/datris-backup.log 2>&1") | sort -u | crontab -

echo ""
echo "============================================"
echo "  Datris Platform is ready!"
echo "============================================"
echo ""
echo "  Platform UI:  https://app.$CUSTOMER_DOMAIN"
echo "  REST API:     https://api.$CUSTOMER_DOMAIN"
echo "  MCP Server:   https://mcp.$CUSTOMER_DOMAIN/sse"
echo ""
echo "  API Key:      $PIPELINE_API_KEY"
echo ""
echo "  Admin UIs:"
echo "    MinIO:      https://minio.$CUSTOMER_DOMAIN"
echo "    ActiveMQ:   https://activemq.$CUSTOMER_DOMAIN"
echo "    Vault:      https://vault.$CUSTOMER_DOMAIN"
echo ""
echo "  Credentials:  $SECRETS_DIR/datris.env"
echo ""
echo "============================================"
