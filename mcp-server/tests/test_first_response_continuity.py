"""Story: Assistant tool-call continuity across turns
(plans/stories/assistant-tool-call-continuity.md), Acceptance 10.

The MCP instructions' FIRST-RESPONSE RULE and its NEVER rule used to demand
`list_pipelines` + `list_taps` on every data-related ask, so a client that
replays earlier tool calls still re-listed every turn. Both are now scoped to
the first data-related ask of a conversation, with a reuse clause: results
already in the conversation are reused; re-list only after something was
created or changed.

Pure file-content checks, modelled on test_governance_wording.py."""
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _instructions():
    text = _read(SERVER_PY)
    i = text.index('_INSTRUCTIONS_TEMPLATE = """')
    j = text.find('\n"""', i + 1)
    return text[i:] if j < 0 else text[i:j]


def _first_response_rule():
    text = _instructions()
    i = text.index("FIRST-RESPONSE RULE")
    # The rule runs up to the next blank-line-separated ALL-CAPS heading or
    # the pipeline-config paragraph that follows it.
    j = text.find("A pipeline config has two required sections", i)
    return text[i:] if j < 0 else text[i:j]


def _never_first_rule():
    text = _instructions()
    i = text.index("NEVER rules:")
    lines = text[i:].splitlines()
    hits = [l for l in lines if "list_pipelines" in l and "list_taps" in l and "NEVER" in l]
    assert hits, "NEVER rules must still carry the list_pipelines/list_taps line"
    return hits[0]


_FIRST_OF_CONVERSATION = re.compile(r"first data-related ask (?:of|in) (?:a|the|this) conversation", re.IGNORECASE)
_REUSE = re.compile(r"already contains those results,? reuse them", re.IGNORECASE)
_RELIST = re.compile(r"only re-list after something was created or changed", re.IGNORECASE)


def test_first_response_rule_scoped_to_first_ask_of_conversation():
    rule = _first_response_rule()
    assert _FIRST_OF_CONVERSATION.search(rule), rule


def test_first_response_rule_has_reuse_clause():
    rule = _first_response_rule()
    assert _REUSE.search(rule), rule
    assert _RELIST.search(rule), rule


def test_first_response_rule_still_demands_the_listing():
    rule = _first_response_rule()
    assert "list_pipelines" in rule and "list_taps" in rule


def test_never_rule_has_reuse_clause():
    line = _never_first_rule()
    assert re.search(r"first data-related ask", line, re.IGNORECASE), line
    assert re.search(r"reuse", line, re.IGNORECASE), line
