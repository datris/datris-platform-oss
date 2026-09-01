#!/usr/bin/env python3
"""Drift guard for the MCP tool catalog's three hand-maintained surfaces.

The single source of truth is mcp-server/server.py (_base_tools). Two other
surfaces must list exactly the same tools:

  - datrisserver/.../auth/MCPToolRoutes.scala — capability-based visibility.
    A tool missing here is hidden from every scoped key (fail-closed).
  - ui/src/app/mcp/mcp.component.ts — the MCP Connect tab's catalog.
    A tool missing here silently disappears from the UI (bit us in v1.22:
    get_dest_types / apply_dest_types shipped invisible to the tab).

Run from anywhere: paths resolve relative to this script. Exits non-zero,
naming every difference, when any surface drifts. Wired into the CI
ui-build job; run locally via `npm run check:mcp-catalog` (ui/) or directly.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER = ROOT / "mcp-server" / "server.py"
ROUTES = ROOT / "datrisserver" / "src" / "main" / "scala" / "ai" / "datris" / "auth" / "MCPToolRoutes.scala"
UI = ROOT / "ui" / "src" / "app" / "mcp" / "mcp.component.ts"


def server_tools() -> set:
    text = SERVER.read_text()
    # Tool definitions: Tool( followed by name="..." on the next line.
    names = re.findall(r'Tool\(\s*\n\s*name="([a-z_0-9]+)"', text)
    if not names:
        sys.exit(f"could not parse any Tool(name=...) entries from {SERVER}")
    return set(names)


def routes_tools() -> set:
    text = ROUTES.read_text()
    names = re.findall(r'"([a-z_0-9]+)"\s*->\s*(?:Mapped|Local)', text)
    if not names:
        sys.exit(f"could not parse any tool rows from {ROUTES}")
    return set(names)


def ui_tools() -> set:
    text = UI.read_text()
    # Tool entries (not parameters): name: '...' immediately followed by description:.
    names = re.findall(r"name: '([a-z_0-9]+)',\n\s+description:", text)
    if not names:
        sys.exit(f"could not parse any toolCatalog entries from {UI}")
    return set(names)


def main() -> int:
    server = server_tools()
    failures = []
    for label, path, got in (
        ("MCPToolRoutes.scala", ROUTES, routes_tools()),
        ("UI MCP tab catalog", UI, ui_tools()),
    ):
        missing = sorted(server - got)
        extra = sorted(got - server)
        if missing:
            failures.append(f"{label} is MISSING tools the MCP server defines: {', '.join(missing)}")
        if extra:
            failures.append(f"{label} lists tools the MCP server does not define: {', '.join(extra)}")
    if failures:
        print(f"MCP tool catalog drift ({len(server)} tools in mcp-server/server.py):", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        print("Fix the drifted surface(s); server.py is the source of truth.", file=sys.stderr)
        return 1
    print(f"MCP tool catalog in sync across server.py, MCPToolRoutes.scala and the UI ({len(server)} tools).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
