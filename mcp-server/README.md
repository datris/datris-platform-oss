# MCP Server

MCP (Model Context Protocol) server for the Datris. Enables AI agents to interact with the pipeline natively.

See the full documentation at [docs/mcp.md](../docs/mcp.md).

## Quick Start

```bash
# Local (stdio mode for Claude Desktop / Claude Code)
pip install -r requirements.txt
python server.py

# Docker (starts automatically with docker-compose up)
docker-compose up --build
```
