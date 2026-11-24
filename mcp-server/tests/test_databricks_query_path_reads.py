"""Story: Databricks query: a read from a file path, or from a stream's
stored state, is refused (plans/stories/databricks-path-reads-refused.md).

Pins the MCP half of Acceptance: the query_databricks tool description says a
read from a file path is refused, and (Files, mcp-server/server.py) names the
functions the story adds to the refused list, so an agent names a table
instead of a location."""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


def _sentences(desc):
    return [s for s in re.split(r"(?<=[.;])\s+", desc.replace("\n", " ")) if s.strip()]


def test_the_query_databricks_description_says_a_read_from_a_file_path_is_refused():
    desc = _tool("query_databricks").description
    sentences = [s for s in _sentences(desc) if re.search(r"file\s+path", s, re.IGNORECASE)]
    assert sentences, f"query_databricks description does not mention a read from a file path: {desc}"
    assert any(re.search(r"\b(refused|rejected)\b", s, re.IGNORECASE) for s in sentences), sentences


def test_the_query_databricks_description_names_the_added_refused_functions():
    desc = _tool("query_databricks").description
    refused = [s for s in _sentences(desc) if re.search(r"\b(refused|rejected)\b", s, re.IGNORECASE)]
    text = " ".join(refused)
    for fn in ("read_statestore", "read_state_metadata", "cloud_files_state", "list_secrets"):
        assert fn in text, f"{fn} not in a refused sentence of the query_databricks description: {refused}"
