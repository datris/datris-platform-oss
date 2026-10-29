# Release Notes

## v1.53.0 — October 20, 2026

**The MCP server and CLI move to the current MCP SDK, with no change for the agents that use them.**

- **MCP server and CLI on the current MCP SDK.** The `datris-mcp-server` package and the MCP server image now run on the current major version of the MCP Python SDK. Agents see no difference: the same tools, resources, results, error messages and version, over stdio, SSE and streamable HTTP. `pip install datris-mcp-server` now works alongside other packages that need the current SDK. If you install the CLI into a shared Python environment that pins the previous SDK major version, install it in its own environment with `pipx` or `uvx` instead.
- **Oversized requests are refused at once.** A request to the MCP server over HTTP that is larger than the server's upload limit (about 1.3 GB with the default `DATRIS_MAX_UPLOAD_MB`) is now refused immediately with a clear error instead of being read in full. Anything that fit before still fits.
- **Refused tool calls show in the Agents tab.** A tool call turned away for invalid arguments now appears in the Agents tab activity as an error with the reason, where nothing was recorded before. The call never reaches the platform, as before.
- **Security update to a UI dependency.** The web UI picks up a security fix in one of its framework packages. No user-facing change.

**Upgrading:** `docker compose pull && docker compose up -d`; the UI and MCP server images changed. No configuration changes are required. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
