#!/bin/sh
set -e

# Datris Vault bootstrap — runs in the vault-init container before seeding.
#
# Dev mode used to hide all of this (auto-init, auto-unseal, known root token).
# With file storage Vault boots SEALED and must be initialized once and
# unsealed on every start. This script:
#   1. waits for Vault to be reachable,
#   2. initializes it on first run (storing the unseal key + root token on the
#      shared vault-data volume),
#   3. unseals it from the stored Shamir key,
#   4. enables KV v2 at secret/ (dev mode did this implicitly),
#   5. mints a fixed-id token so the datris server's `VAULT_TOKEN: root-token`
#      keeps working unchanged,
#   6. hands off to vault-init.sh for create-if-absent seeding.
#
# Everything after first run is idempotent: secrets persist on the volume, so
# reboots only unseal + re-mint the server token (if absent) and skip seeding
# of any path that already exists.

INIT_FILE=/vault/file/datris-init.txt
SERVER_TOKEN_ID="${DATRIS_VAULT_TOKEN:-root-token}"

echo "vault-bootstrap: waiting for Vault to respond..."
# `vault status` exit codes: 0 = unsealed, 2 = sealed (both mean reachable),
# 1 = not reachable yet. Capture the code inside the `else` (where $? still
# holds the condition's status) — a bare `vault status; code=$?` would trip
# `set -e` and abort before we could read the code, since sealed/unreachable
# are non-zero and that's the whole state we're waiting through.
while true; do
  if vault status >/dev/null 2>&1; then
    break
  else
    code=$?
    [ "$code" -eq 2 ] && break
  fi
  sleep 1
done

# 1. Initialize on first run only. `operator init -status` exits 0 if already
#    initialized, 2 if not.
if vault operator init -status >/dev/null 2>&1; then
  echo "vault-bootstrap: Vault already initialized."
else
  echo "vault-bootstrap: initializing Vault (first run)..."
  # Single Shamir key — appropriate for the local / single-tenant box.
  vault operator init -key-shares=1 -key-threshold=1 > "$INIT_FILE"
  chmod 600 "$INIT_FILE" 2>/dev/null || true
fi

if [ ! -f "$INIT_FILE" ]; then
  echo "vault-bootstrap: ERROR: $INIT_FILE missing — cannot recover root token." >&2
  echo "vault-bootstrap: delete the vault-data volume to re-initialize from scratch." >&2
  exit 1
fi

UNSEAL_KEY=$(grep -i 'Unseal Key 1:' "$INIT_FILE" | awk '{print $NF}')
ROOT_TOKEN=$(grep -i 'Initial Root Token:' "$INIT_FILE" | awk '{print $NF}')

if [ -z "$ROOT_TOKEN" ]; then
  echo "vault-bootstrap: ERROR: could not read root token from $INIT_FILE." >&2
  exit 1
fi

# 2/3. Unseal if sealed.
SEALED=$(vault status 2>/dev/null | awk '/^Sealed/ {print $2}')
if [ "$SEALED" = "true" ]; then
  if [ -n "$UNSEAL_KEY" ]; then
    echo "vault-bootstrap: unsealing Vault..."
    vault operator unseal "$UNSEAL_KEY" >/dev/null
  else
    echo "vault-bootstrap: ERROR: Vault sealed but no Shamir unseal key found in $INIT_FILE." >&2
    exit 1
  fi
fi

export VAULT_TOKEN="$ROOT_TOKEN"

# 4. Enable KV v2 at secret/ (dev mode mounted this automatically).
if ! vault secrets list 2>/dev/null | grep -q '^secret/'; then
  echo "vault-bootstrap: enabling KV v2 at secret/..."
  vault secrets enable -path=secret -version=2 kv >/dev/null
fi

# 5. Policy + fixed-id token for the datris server. The server authenticates
#    with `VAULT_TOKEN: root-token` (unchanged from dev mode) — we mint a
#    token with that exact id so nothing downstream has to change.
vault policy write datris - >/dev/null <<'EOF'
path "secret/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}
EOF

if ! VAULT_TOKEN="$SERVER_TOKEN_ID" vault token lookup >/dev/null 2>&1; then
  echo "vault-bootstrap: creating datris server token (id=$SERVER_TOKEN_ID)..."
  # Orphan + periodic so it survives reboots without a parent and is exempt from
  # the system max-TTL. The 10-year period makes it effectively non-expiring on
  # a long-running box without anyone renewing it — matching the old dev-mode
  # root-token (which never expired). bootstrap also re-mints it on any restart
  # if it's somehow missing, so this self-heals.
  vault token create -id="$SERVER_TOKEN_ID" -policy=datris -orphan -period=87600h >/dev/null
fi

# 6. Seed (create-if-absent). vault-init.sh inherits VAULT_TOKEN from the env.
echo "vault-bootstrap: handing off to vault-init.sh for seeding..."
exec /bin/sh /vault-init.sh
