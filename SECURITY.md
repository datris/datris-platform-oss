# Security Policy

## Reporting a Vulnerability

If you believe you've found a security vulnerability in Datris, please report it
**privately** via GitHub's security advisory process:

> https://github.com/datris/datris-platform-oss/security/advisories/new

Please **do not** open a public issue for security reports.

We aim to acknowledge reports within 3 business days and to provide a remediation
timeline within 10 business days.

## Supported Versions

Only the latest minor version receives security updates. See
[release-notes.md](release-notes.md) for the current release.

## Current Security Model

Datris is intentionally simple. We document the model honestly here so operators
can make informed decisions about how to deploy it.

### Authentication & authorization
- **Authentication**: a single static API key per environment, sent as the
  `x-api-key` header and validated against HashiCorp Vault.
- **Authorization / RBAC**: **none today.** Any caller with a valid API key can
  perform any action within their tenant. There are no users, roles, or
  per-resource permissions.
- **Multi-tenancy**: when enabled, tenants are isolated at the **database level**
  (separate Postgres databases, separate object-store buckets). This is
  infrastructure isolation, not user-level isolation.
- **Sessions, MFA, password policies**: not applicable — there are no user
  accounts.

If you need OIDC/SSO/RBAC for an enterprise deployment, please open a discussion;
this is on the roadmap but not yet implemented.

### Secrets management
- All secrets are stored in **HashiCorp Vault** (KV v2).
- The platform never persists secrets to disk outside Vault.
- API responses mask sensitive fields (`password`, `apikey`, `secretkey`,
  `token`, `secret`).
- Tap scripts receive secrets via environment variables; tap stderr is masked
  before logging to prevent accidental leakage.

### Encryption
- **In transit**: TLS is terminated at the nginx reverse proxy in production
  deployments. Container-to-container traffic inside the Docker network is
  plaintext — operators who don't trust their host network should add a service
  mesh or IPsec.
- **At rest**: Datris does **not** enforce at-rest encryption on Postgres,
  MongoDB, MinIO, or Kafka. Operators are expected to enable
  `sslmode=require`, MinIO server-side encryption, etc., for production
  deployments. (We plan to fail-fast on plaintext Postgres in a future release)

### Network controls
- No built-in rate limiting or WAF. Place Cloudflare, AWS WAF, or equivalent in
  front of production deployments.
- CORS allowed origins are configurable via the `cors.allowedOrigins` property
  in `application.yaml` (default `*` for development; **lock this down in
  production**).

## Operator Responsibilities

A safe self-hosted Datris deployment requires the operator to:

1. Run Vault in non-dev mode with sealed root tokens.
2. Place a TLS-terminating reverse proxy (nginx, Caddy, Traefik) in front of the
   server. **Do not** expose port 8080 directly.
3. Set `cors.allowedOrigins` to your real frontend origin(s).
4. Enable `sslmode=require` (or stricter) on the Postgres JDBC URL.
5. Enable encryption at rest on MinIO, MongoDB, and Kafka per their respective
   docs.
6. Rotate the `x-api-key` value periodically.
7. Run an external WAF and rate limiter.
8. Monitor `docker logs` (or pipe to your log aggregator) for unauthorized
   access attempts.

## Repository Hardening

This repository has the following GitHub security features enabled:

- **Secret scanning** (default for public repositories)
- **Push protection** (blocks commits containing detected secrets at push time)
- **Dependabot security updates** (planned)
- **Private vulnerability reporting** (via the security advisory link above)
