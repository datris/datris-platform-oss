# Release Notes

## v1.8.0 — May 29, 2026

**Write to AWS S3, query Parquet and ORC from the Assistant and Search, and stop chats actually stop.**

- **AWS S3 as a first-class destination.** The Object Store destination now writes Parquet or ORC directly to S3 alongside the built-in MinIO. Pick `Object Store (MinIO or S3)` in the pipeline wizard, point at your bucket, and reference a credentials secret you've created in Configuration → Secrets → Platform. Multiple S3 destinations with different IAM keys coexist in the same deployment — each pipeline carries its own credential reference, applied per bucket at write time.
- **Region lives with the credential.** AWS credentials and region travel together in the credentials secret rather than on the pipeline config. One source of truth, one place to rotate, no more silent `us-east-1`-says-the-config-but-the-key-is-`us-west-2` failures. Field names are flexible — `accessKey` / `AWS_ACCESS_KEY` / `AWS_ACCESS_KEY_ID` all work, so a credential you already have in another format drops in unchanged.
- **Query Parquet and ORC from the Assistant.** "Show me the weather data" now works against pipelines whose destination is Object Store. The Assistant resolves the bucket and credentials from the pipeline config, reads the columnar files, and returns rows in chat — no more "I can't read Parquet, here are some alternatives".
- **Search tab gains an Object Store option.** Pick a pipeline from the dropdown, see the resolved bucket/prefix/format, set a limit, hit Execute. Works for both MinIO and S3 destinations.
- **Assistant offers all three structured destinations.** When you ask for structured data and no pipeline yet covers it, the Assistant now mentions MongoDB, PostgreSQL, **and** Object Store as choices, instead of silently defaulting to one. The default behavior is unchanged when you don't have a preference.
- **Assistant can discover destination credentials it shouldn't create.** The Platform tab in Configuration → Secrets is now visible to the Assistant for reading (names and field shape only — never values). When a pipeline destination needs a credentials reference, the Assistant lists what's available, verifies the secret has the right field shape, and points you at the Secrets tab to create one when nothing fits. It can't create, modify, or delete platform secrets — those stay user-owned.
- **Stop actually stops.** Clicking Stop in the Assistant now halts the in-flight chat within a fraction of a second instead of waiting for the upstream model to finish generating. Anthropic stops generating tokens the instant the connection drops, so cancelled responses cost only what was already streamed.
- **Pipeline failures surface fast.** A failed pipeline now flips to Error in the Ops dashboard within seconds instead of staying stuck in Processing for up to ten minutes. Any destination loader that fails — even with a JVM-level error — is reported immediately with the loader name in the message.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed.
- Existing MinIO pipelines work unchanged — `provider` defaults to `minio` and the global MinIO credentials are unchanged.
- For AWS S3, create a Platform-tab secret with `accessKey`, `secretKey`, `region` (and optionally `sessionToken`), then reference it by name from your pipeline.

---

## v1.7.9 — May 28, 2026

**Ask the Ops assistant about a failing pipeline without leaving the dashboard.**

See [archived v1.7.9 release notes](release-notes/v1.7.9.md).

---

See [archived release notes](release-notes/) for prior versions.
