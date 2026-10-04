# Connecting a project to Datris

## Install the platform

Docker is the only prerequisite.

```bash
curl -fsSL https://get.datris.ai/install.sh | sh
```

In a terminal, the installer prompts for AI provider keys (any one is enough) and starts the stack. With no terminal, as in an agent's shell tool, it asks nothing and uses the keys already in the environment, so export one first; see https://docs.datris.ai/install-for-agents. A minimal laptop install can skip the bundled embedding server; see https://docs.datris.ai/installation.

## Connect an MCP client

The Docker stack serves the MCP server over SSE on port 3000. The portable client shape uses the mcp-remote bridge, which reconnects when the server restarts:

```json
{
  "mcpServers": {
    "datris": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:3000/sse", "--transport", "sse-only"]
    }
  }
}
```

When API keys are enabled, add `"--header", "x-api-key:<value>"` to `args`. The Configuration tab's Connect Your Agent panel generates this snippet with the key filled in. Client-specific file locations: https://docs.datris.ai/configuring-claude and https://docs.datris.ai/configuring-openclaw.

## Recommended guardrails for a project agent

Off by default so existing installs are unchanged. In the stack's `.env`:

```bash
USE_USER_AUTH=true
USE_API_KEYS=true
USE_AGENT_POLICY=true
USE_AUDIT_LOG=true
```

Then recreate the Datris container:

```bash
docker compose up -d --force-recreate datris
```

Issue the agent its own key in Configuration, API-Keys, labelled after the agent. Prefer a scoped template (rag-builder, ops, reporting) over full-access. Set the policy so deletes and destination type migrations require approval. The recipe at https://docs.datris.ai/recipes/claude-code-postgres walks through all of this in about fifteen minutes.

## Checks to run on first contact

- `get_version` for the server version and budgets.
- `run_doctor` for anything misconfigured, including which embedding model is active.
- `get_agent_policy` for what will run unattended.
