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

There are two independent authentication paths: an **API key** for programmatic
clients and **user accounts** for the web UI.

- **API keys (programmatic clients)**: a static API key per environment, sent as
  the `x-api-key` header and validated against HashiCorp Vault. This is how the
  CLI and MCP server authenticate. Enabled via `useApiKeys`.
- **User accounts (web UI)**: humans log in with a username + password. Passwords
  are hashed with **BCrypt** (cost factor 12) and stored in MongoDB; the platform
  never stores plaintext passwords. The user-auth path is gated by the
  `useUserAuth` flag (off by default in the OSS build, so existing deployments are
  unchanged). On first boot a default `admin` account exists with no password and
  is forced through a set-password flow before it can do anything.
- **Sessions**: successful login issues an opaque, `SecureRandom`-generated token
  stored in a `datris-session` cookie (`HttpOnly`, `SameSite=Strict`, 8-hour TTL).
  Sessions live in MongoDB with a TTL index that auto-purges expired tokens. The
  cookie's `Secure` flag is off in the container because TLS is terminated at the
  nginx edge — keep that reverse proxy in front in production.
- **Authorization / RBAC**: when `useUserAuth` is enabled, three roles are
  enforced — **admin**, **editor**, **viewer**. The default rule is: any
  logged-in role may read (GET); writes (POST/PUT/PATCH/DELETE) require admin or
  editor; sensitive operations such as user management require admin. When
  `useUserAuth` is off, role enforcement is a no-op and any caller with a valid
  API key can perform any action within their tenant.
- **Multi-tenancy**: when enabled, tenants are isolated at the **database level**
  (separate Postgres databases, separate object-store buckets). This is
  infrastructure isolation that sits beneath the user/role model above.
- **MFA, password complexity, account lockout**: not implemented. Passwords have
  a minimum length only.

**OIDC / SSO is not yet implemented** — there is no external identity-provider
integration today. If you need OIDC/SSO for an enterprise deployment, please open
a discussion; it's on the roadmap.

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
  deployments.
- **Postgres TLS enforcement (opt-in)**: set `DATRIS_ENV=production` and Datris
  **enforces** Postgres TLS at startup — it refuses to boot when a JDBC URL
  points at an external host without `sslmode=require` (or stricter). To opt
  out, set `DATRIS_ALLOW_PLAINTEXT_DB=true` and accept the risk. The bundled
  in-network Postgres is exempt: the compose host network is the trust boundary
  (see above), and enforcement targets external databases, where traffic
  crosses a real network. Without the flag, behavior is unchanged — an
  external-looking plaintext URL logs a startup warning for visibility.

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
7. For multi-user deployments, enable `useUserAuth`, set a password on the
   default `admin` account immediately, and assign the least-privileged role
   (viewer/editor) appropriate to each user.
8. Run an external WAF and rate limiter.
9. Monitor `docker logs` (or pipe to your log aggregator) for unauthorized
   access attempts.

## Repository Hardening

This repository has the following GitHub security features enabled:

- **Secret scanning** (default for public repositories)
- **Push protection** (blocks commits containing detected secrets at push time)
- **Dependabot security updates** — enabled for GitHub Actions, npm (UI), pip
  (MCP server), and Docker base images across all four Dockerfiles. Scala/sbt
  dependencies are covered via the GitHub dependency graph, so advisory alerts
  fire for JVM dependencies too.
- **Trivy vulnerability scanning** (`.github/workflows/security-scan.yml`) — a
  filesystem scan (vulnerable dependencies, committed secrets, IaC misconfig)
  runs on every pull request and push to `main` and **fails the build** on new
  HIGH/CRITICAL findings with an available fix. The published `datrisai/*`
  container images are scanned weekly for OS-package CVEs (report-only).
  Findings upload to the repository's Security tab.
- **SBOMs** — every release publishes a CycloneDX SBOM for each of the four
  container images, generated by Syft and attached as artifacts on the
  `docker-publish` workflow run.
- **Private vulnerability reporting** (via the security advisory link above)
