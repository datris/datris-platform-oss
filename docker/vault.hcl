# Datris Vault configuration — file storage backend.
#
# Replaces dev mode so secrets, AI providers/models, tap secrets, and UI
# Configuration changes SURVIVE `docker compose up --build` / `down && up` /
# host reboot. Everything here is Vault Community Edition (free) — no
# Enterprise features.
#
# Lifecycle (handled by docker/vault-bootstrap.sh): a non-dev Vault boots
# SEALED and must be initialized once and unsealed on every start. The
# bootstrap stores the generated unseal key + root token on this same
# `vault-data` volume and auto-unseals from it on every boot. That is no more
# secure than the old known `root-token`, but it is appropriate for the
# local / single-tenant box Datris ships as.

# /vault/file (not /vault/data): this dir exists in the image owned by the
# vault user (uid 100), so a fresh named volume mounted here inherits writable
# ownership. /vault/data does not exist in the image — a volume there is
# root-owned and the vault process (which drops to uid 100) cannot write to it.
storage "file" {
  path = "/vault/file"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = "true"   # TLS terminates upstream (nginx / load balancer); intra-compose traffic only
}

# Redirect address used inside the compose network.
api_addr = "http://vault:8200"

# The datris server authenticates with a fixed-id periodic token minted by
# vault-bootstrap.sh with a 10-year period. Vault clamps a token's INITIAL TTL
# to max_lease_ttl regardless of its period, and the default (768h = 32 days)
# silently killed the server token a month after install. Raise the ceiling so
# the periodic token's TTL actually spans its period. Ordinary leases still get
# the explicit 768h default below.
default_lease_ttl = "768h"
max_lease_ttl     = "87600h"

ui = true
