# Release Notes

## v1.47.0 — October 9, 2026

**Single sign-on through your identity provider, and security updates.**

- **Sign in with SSO.** With user authentication on, Datris can now sign people in through your company identity provider: Okta, Microsoft Entra ID, Google Workspace, Keycloak, Auth0, or any OpenID Connect provider. The login screen gains a **Sign in with SSO** button next to the username and password form. Sign-in uses the OpenID Connect authorization code flow with PKCE, so multi-factor authentication, password rules and lockout are whatever your provider enforces. Someone who signs in this way becomes an ordinary Datris user with one of the three existing roles, so sessions, role checks, the Users screen and the audit log work exactly as they do for password users. API keys, the CLI and MCP clients are unchanged. See [Single sign-on (OIDC)](/user-auth#single-sign-on-oidc).
- **You decide who gets in.** People you have already added on **Configuration → Users** sign in with the role you gave them. Anyone else is refused with a message to ask an administrator, unless you set `OIDC_DEFAULT_ROLE` to `viewer` or `editor` to create them on first sign-in. The built-in `admin` account can never be reached through SSO, so it stays available for recovery with its password. The first sign-in binds the user to the provider's subject id; a later sign-in with the same username from a different identity is refused.
- **Never in the way of password login.** A wrong or incomplete SSO setting switches SSO off with one ERROR line in the log and the server starts as usual. An identity provider that is down makes the SSO button fail with a clear message while password login keeps working, and SSO recovers on its own once the provider is reachable.
- **Every SSO sign-in and refusal is in the audit log** as an `auth` / `login` entry with `method: oidc`.
- **Security updates.** Refreshed server and UI build dependencies to clear the open vulnerability reports. No behaviour change.
- **MinIO health check.** The bundled MinIO's health check no longer depends on a config file inside the container, so a client alias run inside the container can no longer leave the service reported unhealthy forever.

**Upgrading**

Refresh the compose file (`git pull`, or re-download `docker-compose.standalone.yml`), then run `docker compose pull && docker compose up -d`. The server and UI images changed. SSO is off by default; nothing changes unless you set `OIDC_ENABLED=true`.

- **To turn SSO on**, set `USE_USER_AUTH=true`, `OIDC_ENABLED=true`, `OIDC_ISSUER`, `OIDC_CLIENT_ID` and `OIDC_REDIRECT_URI` in `.env`, store the client secret in Vault under `oss/oidc` (key `clientSecret`), and recreate the server container. Register `https://<your-ui-host>/api/v1/auth/oidc/callback` as the redirect URI at your provider. The docs include a local Keycloak walkthrough.
- **One reserved secret name.** A secret named `oidc` (for example `oss/oidc`) is now managed by the platform, so taps and pipelines can no longer reference it, like `api-keys` and `field-protection`.
- **One new version field.** The version endpoint reports `oidcEnabled` (`"false"` unless SSO is on).
- SSO is single-tenant: with `MULTI_TENANT=true` it is not verified and signs people in to the default environment. There is no SAML support and no group-to-role mapping from the provider yet; roles are set on the Users screen.
- No other configuration changes are required.
