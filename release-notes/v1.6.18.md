# Release Notes

## v1.6.18 — May 5, 2026

**User authentication, roles, and an admin-only Configuration tab.**

- **Optional username/password login.** Datris can now require a login before any tab is reachable. Three roles ship out of the box — **admin** (full access), **editor** (read + edit pipelines, taps, secrets), and **viewer** (read-only). Off by default, so existing single-tenant installs are unaffected. See the new "User Authentication" doc to enable it.
- **Configuration is admin-only.** When auth is on, only admins see the Configuration tab (Secrets, AI Providers, Taps, Users, Environment). Editors and viewers continue to use everything else.
- **New Users sub-tab.** Admins can add, remove, and reassign roles. A built-in 16-character password generator with a reveal toggle makes handing out credentials painless. The last admin can't be deleted.
- **Self-service password change.** Users can change their own password from the top-right user menu.
- **Reveal toggle on the login screen.** Easier to see what you're typing on a new device.
- **Clear the Agents activity log.** The trash icon now wipes the server-side activity buffer (with an inline confirm) so the cleared state survives a refresh.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d`. No data migration needed; auth defaults to off.

---

See [archived release notes](release-notes/) for prior versions.
