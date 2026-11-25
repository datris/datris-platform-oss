# Release Notes

## v1.62.0 — November 23, 2026

**Hand a tap to a new owner, safer handling of tap script files, and tighter boundaries for limited users and API keys.**

- **Change a tap's owner.** An administrator can hand a tap to another user or API key without recreating it. The tap's schedule, history and versions are untouched.
- **Deleting a tap only removes its own script.** A script file that another tap still uses, or that was placed by hand, is left in place and the result says so. Routine version cleanup follows the same rule.
- **Storing a script never replaces another tap's file.** An upload, a storage move or a catalog restore that would overwrite a script another tap runs from is refused, and nothing is written.
- **Approved agent actions run with the requester's access.** When an administrator approves an action an agent asked for, it runs with the access of whoever asked, not the approver's.
- **Generating a schema needs permission to change the pipeline.** It is also recorded in the audit log, and it will not overwrite a schema that is already in use.
- **Chats and agent tools follow the caller's access.** A chat ends when its user is removed. The MCP tool list shows what the key can use, and stays available through a brief outage.
- **Recovery reverts keep the tap's current catalog, owner and enabled state.**
- **Multi-tenant mode is retired.** The server will not start with it switched on, and says why.
- **`datris doctor` has three new checks.** One flags the multi-tenant setting before you upgrade. Two list taps whose owner's access or script references need attention before a later release starts enforcing them.
- **Security hardening.** Several fixes tighten sign-in sessions, approvals, script storage, and what limited users and API keys can read or change. We recommend upgrading, particularly if you keep tap scripts in a code repository.

**Upgrading:** update the CLI first (`pip install -U datris-mcp-server` or `brew upgrade datris`) and run `datris doctor --pre-upgrade`, then `docker compose pull && docker compose up -d`; the server, UI and MCP server images changed. Nothing is migrated. Things to check:

- **Multi-tenant setting.** If your `.env` turns it on, remove it before upgrading. The server stops at startup otherwise and changes nothing.
- **Going back to an earlier version signs everyone out.** Stop the old server before starting the new one.
- **Agent approvals.** If the user or key that requested an action has since been removed, approving it fails.
- **Read-only and reporting keys can no longer generate a schema.** Use a key that can update the pipeline.
- **Limited API keys and catalog changes.** Renaming a catalog, or moving a pipeline or tap out of one, is refused when the key's access does not cover where it would end up.
- **Limited users and keys, tap scripts in a code repository.** They can no longer store a script for a tap whose file was placed by hand or is shared with another tap. An administrator can give the tap its own file. `datris doctor` lists the taps affected.
- **Changed your repository's path prefix in the past?** Record the earlier prefix in the code repository settings, or taps stored under it are treated as hand-placed.
- **`datris doctor` in scripts.** The two new tap checks are warnings. If either lists a tap, a full run exits non-zero.
- **Tap deletes can return a warning.** If your automation treats any warning as a failure, check for the "script file was left in place" message.

Agents using the published skill get the updated guidance with skill 1.7.15.
