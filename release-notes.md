# Release Notes

## v1.6.13 — April 24, 2026

**MCP tab and documentation sync.**

- **MCP tab now lists every agent tool.** The in-app MCP reference was missing `get_pipeline_status`, `create_tap_secret`, `delete_tap_secret`, `get_tap_ledger`, and `query_natural`. All five are now in the catalog and the Try-It playground, and `create_tap` exposes the `tap_type` parameter.
- **Recommended Agent Workflow rewritten to match the platform's actual flow.** The in-app workflow and the MCP docs had drifted — they started with `profile_data` (which the platform explicitly says not to use for pipeline generation), skipped the `persisted` / `persistedReason` check after `run_tap`, and didn't show the `publisherToken` poll that confirms records actually landed. All three are now canonical: check-before-create, verify-via-publisher-token, and tap credentials managed via `create_tap_secret`.
- **Documentation: updated agent workflow examples.** RAG over external documents is now shown as a document tap (`tap_type="document"` + `get_tap_ledger`), onboarding an external source uses `create_tap_secret` for credentials, and quality monitoring of scheduled taps shows the `get_tap_logs` → `get_pipeline_status(publisher_token=...)` pivot.

---

See [archived release notes](release-notes/) for prior versions.
