"""Story: field protection 8, a tap may only use a tap-typed secret
(plans/stories/field-protection-8-tap-secret-scope.md), the MCP text half.

The server now refuses a tap whose secretName is a platform secret (400). The
MCP server adds no client-side check; it only has to tell the agent up front:
the `secret_name` parameter of `create_tap` and `update_tap` must name a tap
secret (list_tap_secrets / create_tap_secret), and a platform secret is
refused by the server.

`update_tap` has no `secret_name` parameter today (the story assumes it
does); its case is checked only if the parameter exists, so the test pins
the rule without forcing a new parameter.

Pinned: `server._base_tools()` returns the tool list (same seam as
test_tap_test_limit.py)."""
import os
import re
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _secret_name_description(tool_name):
    for t in server._base_tools():
        if t.name == tool_name:
            props = (t.inputSchema or {}).get("properties", {})
            if "secret_name" not in props:
                if tool_name == "create_tap":
                    raise AssertionError("create_tap has no secret_name parameter")
                pytest.skip(f"{tool_name} has no secret_name parameter")
            return props["secret_name"].get("description", "")
    raise AssertionError(f"tool {tool_name} not in _base_tools()")


@pytest.mark.parametrize("tool_name", ["create_tap", "update_tap"])
def test_secret_name_description_requires_a_tap_secret(tool_name):
    d = _secret_name_description(tool_name)
    assert re.search(r"\btap secret\b", d, re.IGNORECASE), d
    assert "list_tap_secrets" in d or "create_tap_secret" in d, d


@pytest.mark.parametrize("tool_name", ["create_tap", "update_tap"])
def test_secret_name_description_says_platform_secrets_are_refused(tool_name):
    d = _secret_name_description(tool_name)
    assert re.search(r"platform secret", d, re.IGNORECASE), d
    assert re.search(r"refused|rejected", d, re.IGNORECASE), d
