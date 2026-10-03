# Security Policy

## Reporting a Vulnerability

If you believe you've found a security vulnerability in Datris, please report it
**privately** via GitHub's security advisory process:

> https://github.com/datris/datris-platform-oss/security/advisories/new

If you cannot use GitHub, email `info@datris.ai` with the same detail.

Please **do not** open a public issue for security reports.

We aim to acknowledge reports within 3 business days and to provide a remediation
timeline within 10 business days.

## Supported Versions

Only the latest minor version receives security updates. See
[release-notes.md](release-notes.md) for the current release and
[Upgrades & Supported Versions](https://docs.datris.ai/production/upgrades) for
the upgrade procedure.

## Current Security Model

We document the model honestly here so operators can make informed decisions
about how to deploy it. The
[Security Architecture](https://docs.datris.ai/production/security-architecture)
page covers the same ground in more depth, with links to each control.

Datris is fully self-hosted: there is no managed service, and the platform sends
no telemetry.

### Authentication and authorization

There are two independent authentication paths: **API keys** for programmatic
clients and **user accounts** for the web UI. Both are **off by default** in a
fresh install, so anyone who can reach the server has full access — fine on a
laptop, wrong for anything shared. Turn both on for production.

- **API keys (programmatic clients)**: the CLI, MCP agents, and scripts each
  hold their own labeled key, sent as the `x-api-key` header. Keys are issued
  from the UI, shown once, stored hashed, and revocable individually; a revoked
  key is rejected immediately. Every key carries an explicit list of
  capabilities (`resource:action[:scope]`) — a call outside that list returns
  403, and the MCP tool list an agent sees is filtered to match. Enabled via
  `USE_API_KEYS=true`.
- **User accounts (web UI)**: humans log in with a username + password. Passwords
  are hashed with **BCrypt** (cost factor 12) and stored in MongoDB; the platform
  never stores plaintext passwords. The user-auth path is gated by the
  `USE_USER_AUTH` flag (off by default in the OSS build, so existing deployments are
  unchanged). On first boot a default `admin` account exists with no password and
  is forced through a set-password flow before it can do anything.
- **Sessions**: successful login issues an opaque, `SecureRandom`-generated token
  stored in a `datris-session` cookie (`HttpOnly`, `SameSite=Strict`, 8-hour TTL).
  Sessions live in MongoDB with a TTL index that auto-purges expired tokens. The
  cookie's `Secure` flag is off in the container because TLS is terminated at the
  nginx edge — keep that reverse proxy in front in production.
- **Authorization / RBAC**: when `USE_USER_AUTH` is enabled, three roles are
  enforced — **admin**, **editor**, **viewer**. The default rule is: any
  logged-in role may read (GET); writes (POST/PUT/PATCH/DELETE) require admin or
  editor; sensitive operations such as user management, API keys, the audit log,
  and agent policy require admin. When `USE_USER_AUTH` is off, role enforcement
  is a no-op and API callers are limited only by their key's capabilities.
- **Multi-tenancy**: when enabled, tenants are isolated at the **database level**
  (separate Postgres databases, separate object-store buckets). This is
  infrastructure isolation that sits beneath the user/role model above.
- **MFA, password complexity, account lockout**: not implemented. Passwords have
  a minimum length only (5 characters).

**OIDC / SSO is not yet implemented** — there is no external identity-provider
integration today. If you need OIDC/SSO for an enterprise deployment, please open
a discussion; it's on the roadmap.

### Agent governance
- **Agent policy** (`USE_AGENT_POLICY=true`, off by default): an administrator
  decides per action whether an agent runs it unattended, waits for a person to
  approve it, or is refused. It is enforced by the platform on every client —
  the in-platform Assistant, MCP agents, the CLI, any script holding an API
  key — not by a prompt. People using the UI are never gated; they are the
  approvers.
- **Audit log** (`USE_AUDIT_LOG=true`, off by default): a durable record of
  every platform write — create, change, run, delete, login, key rotation,
  denied request — attributed to a login, an API key, or the Assistant acting on
  a user's behalf. Queryable in the UI and over REST, exportable as CSV, and
  mirrored to the server log for your SIEM.

### Secrets management
- All secrets are stored in **HashiCorp Vault** (KV v2).
- The platform never persists secrets to disk outside Vault.
- API responses mask sensitive fields (`password`, `apikey`, `secretkey`,
  `token`, `secret`).
- Agents reference a secret by name and never receive its value. Secrets
  created through MCP are tagged as tap secrets; an agent cannot modify or
  delete a secret a person created in the UI.
- Tap scripts receive secrets via environment variables; tap stderr is masked
  before logging to prevent accidental leakage.

### Isolation of agent-written code
- Taps are Python scripts that agents (or people) write and Datris runs. On
  Docker Compose the **isolated tap runner** is on by default
  (`USE_TAP_RUNNER=true`): tap code runs in a separate container that holds no
  platform credentials and has no network route to Vault, the config store, or
  the object store. The server refuses to start with isolation on and a missing
  or default runner token.
- Tap scripts never receive a platform API key. Each run gets a short-lived,
  read-only token for the platform API.
- HTTP taps and REST endpoint destinations refuse loopback, link-local, private,
  and cloud-metadata addresses unless `DATRIS_ALLOW_PRIVATE_EGRESS=true`.

### Sensitive data sent to AI providers
- Pipelines that use AI rules, AI transformations, or error explanation send
  samples of your data to the AI provider you configure.
- Local models through Ollama and the bundled embedding server allow a
  deployment with no cloud AI provider.

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
3. Set `USE_USER_AUTH=true` and `USE_API_KEYS=true`. Set a password on the
   default `admin` account immediately, and assign the least-privileged role
   (viewer/editor) appropriate to each user.
4. Issue one scoped API key per agent or client, with only the capabilities it
   needs, and rotate keys periodically.
5. Turn on `USE_AGENT_POLICY=true` and `USE_AUDIT_LOG=true`, and ship the audit
   log line to your log aggregator.
6. Keep the isolated tap runner on (the Compose default).
7. Set `cors.allowedOrigins` to your real frontend origin(s).
8. Set `DATRIS_ENV=production` and enable `sslmode=require` (or stricter) on
   external Postgres JDBC URLs.
9. Enable encryption at rest on MinIO, MongoDB, and Kafka per their respective
   docs.
10. Run an external WAF and rate limiter.
11. Monitor `docker logs` (or pipe to your log aggregator) for unauthorized
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
