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

ui = true
