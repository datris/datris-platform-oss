"""Story: field protection 9, one switch so no row value is sent to a model by
the config and code helpers (plans/stories/field-protection-9-ai-values-switch.md),
the MCP text half.

With DATRIS_AI_SAMPLE_VALUES=false the server profiles from statistics and
generates schemas from structure only, and the result carries
`valuesWithheld`. The MCP server adds no logic; the profile tool's description
has to say the server may withhold values and that the result then carries
`valuesWithheld`, so an agent does not read empty sampleValues as a bug.

The only MCP tool that reaches a profile / generate endpoint with a model call
is `profile_data` (`create_pipeline` posts to /pipeline/generate with
allStrings=true, which never calls a model). Should a dedicated generate tool
exist, it is held to the same text.

Pinned: `server._base_tools()` returns the tool list (same seam as
test_tap_secret_scope_text.py)."""
import os
import re
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _tools():
    return {t.name: t for t in server._base_tools()}


def _generate_tools():
    return sorted(n for n in _tools() if re.match(r"^generate_", n) and "schema" in n)


def _check(description):
    assert re.search(r"withh[eo]ld", description, re.IGNORECASE), description
    assert "valuesWithheld" in description, description


def test_profile_data_description_mentions_withheld_values():
    tools = _tools()
    assert "profile_data" in tools, "profile_data tool missing"
    _check(tools["profile_data"].description or "")


@pytest.mark.parametrize("tool_name", _generate_tools() or ["<none>"])
def test_generate_tool_descriptions_mention_withheld_values(tool_name):
    if tool_name == "<none>":
        pytest.skip("no generate-schema MCP tool exists")
    _check(_tools()[tool_name].description or "")
