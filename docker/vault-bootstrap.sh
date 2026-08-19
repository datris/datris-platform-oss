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
#   5. mints the datris server token — a RANDOM per-install token written to the
#      shared vault-token volume for the server to read (VAULT_TOKEN_FILE), or a
#      fixed-id token when DATRIS_VAULT_TOKEN is set (operator override / legacy),
#   6. hands off to vault-init.sh for create-if-absent seeding.
#
# Everything after first run is idempotent: secrets persist on the volume, so
# reboots only unseal + renew/re-mint the server token and skip seeding of any
# path that already exists.

INIT_FILE=/vault/file/datris-init.txt
# Where the server reads its token from (VAULT_TOKEN_FILE on the datris service
# points here). On a dedicated volume — NOT the vault-data volume — so the
# server never gets read access to the root token / unseal key in the init file.
SERVER_TOKEN_FILE=/vault-token/token

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

# 5. Policy + token for the datris server.
vault policy write datris - >/dev/null <<'EOF'
path "secret/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}
EOF

# Orphan + periodic so the token survives reboots without a parent. The 10-year
# period, combined with max_lease_ttl=87600h in vault.hcl (the initial TTL is
# clamped to max_lease_ttl regardless of period) and the renew-on-boot below,
# makes it effectively non-expiring.
if [ -n "${DATRIS_VAULT_TOKEN:-}" ]; then
  # Operator override / legacy path: mint a fixed-id token. Kept so an operator
  # who pins DATRIS_VAULT_TOKEN (e.g. to preserve an existing setup) still works.
  SERVER_TOKEN_ID="$DATRIS_VAULT_TOKEN"
  if VAULT_TOKEN="$SERVER_TOKEN_ID" vault token lookup >/dev/null 2>&1; then
    vault token renew "$SERVER_TOKEN_ID" >/dev/null 2>&1 || true
  else
    echo "vault-bootstrap: creating datris server token (fixed id from DATRIS_VAULT_TOKEN)..."
    # Revoke defensively so the re-mint can't fail with "duplicate ID" on a live
    # zombie entry left by the expiration manager after an upgrade restart.
    vault token revoke "$SERVER_TOKEN_ID" >/dev/null 2>&1 || true
    vault token create -id="$SERVER_TOKEN_ID" -policy=datris -orphan -period=87600h >/dev/null
  fi
  mkdir -p "$(dirname "$SERVER_TOKEN_FILE")"
  printf '%s' "$SERVER_TOKEN_ID" > "$SERVER_TOKEN_FILE"
else
  # Default path: a RANDOM per-install token (no well-known id). Persist it to
  # the shared vault-token volume and reuse it across reboots (renew) so we
  # don't leak a fresh token every boot.
  mkdir -p "$(dirname "$SERVER_TOKEN_FILE")"
  EXISTING_TOKEN=""
  [ -f "$SERVER_TOKEN_FILE" ] && EXISTING_TOKEN=$(cat "$SERVER_TOKEN_FILE" 2>/dev/null)
  if [ -n "$EXISTING_TOKEN" ] && VAULT_TOKEN="$EXISTING_TOKEN" vault token lookup >/dev/null 2>&1; then
    vault token renew "$EXISTING_TOKEN" >/dev/null 2>&1 || true
  else
    echo "vault-bootstrap: creating random datris server token..."
    NEW_TOKEN=$(vault token create -policy=datris -orphan -period=87600h -field=token)
    printf '%s' "$NEW_TOKEN" > "$SERVER_TOKEN_FILE"
  fi
  # Revoke the legacy well-known `root-token` if a prior install created it, so
  # upgrades don't leave a guessable server token valid. Best-effort; harmless
  # if it never existed.
  vault token revoke root-token >/dev/null 2>&1 || true
fi

# The datris container runs as a non-root user (USER datris) and mounts this
# volume read-only, so the token file must be world-readable INSIDE the shared
# volume — a 0600 file owned by root (this init container) is unreadable by the
# server, which then fails to start. The volume is dedicated to vault-init +
# datris only, so 0644 exposes nothing further. Applied unconditionally (create
# AND renew paths) so an already-written 0600 file is repaired on the next run.
chmod 644 "$SERVER_TOKEN_FILE" 2>/dev/null || true

# 6. Seed (create-if-absent). vault-init.sh inherits VAULT_TOKEN from the env.
echo "vault-bootstrap: handing off to vault-init.sh for seeding..."
exec /bin/sh /vault-init.sh
