# Release Notes

## v1.47.1 — October 11, 2026

**Server framework update.**

- **Current, supported server framework.** The server now runs on Spring Boot 4, Spring Framework 7 and Tomcat 11, a line that still receives security fixes. This closes two open vulnerability reports against the web framework; neither affected path is used by Datris. The API, responses, sign-in sessions, Assistant streaming, metrics and audit log behave exactly as before.

**Upgrading**

- The server image changed: run `docker compose pull && docker compose up -d`.
- No configuration changes are required.
