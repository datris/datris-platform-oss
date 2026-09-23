"""Story: MCP serverInfo.version reports the Datris version
(plans/stories/cli-mcp-api-key-and-server-version.md) — the server half.

`initialize` returns `create_initialization_options().server_version` as
serverInfo.version; it must be the datris-mcp-server version, not the
version of the `mcp` SDK."""
import importlib.metadata
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

_PYPROJECT = os.path.join(os.path.dirname(__file__), "..", "pyproject.toml")


def _pyproject_version():
    with open(_PYPROJECT) as f:
        m = re.search(r'^version\s*=\s*"([^"]+)"', f.read(), re.MULTILINE)
    assert m, "no version line in pyproject.toml"
    return m.group(1)


# ------------------------------------------------------ Acceptance bullet 4 ---

def test_server_version_is_datris_not_sdk():
    reported = server.server.create_initialization_options().server_version
    assert reported == server._mcp_server_version()
    assert reported == _pyproject_version()
    assert reported != importlib.metadata.version("mcp")
