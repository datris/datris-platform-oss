"""Story: MongoDB query: every query has a time limit, and an unlimited read
cannot exhaust the server's memory (plans/stories/mongodb-query-bounds.md),
review round 1.

The guidance agents read when they write a tap script that reads platform data
back must show the body the MongoDB query route actually reads (`filter`, not
`query`), state the MongoDB time limits and the result-size ceiling, and tell
the script to check the HTTP status before reading `results`, so a refused read
fails the run instead of loading nothing."""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _create_tap_description():
    for t in server._base_tools():
        if t.name == "create_tap":
            return t.description
    raise AssertionError("create_tap not in _base_tools()")


def _passages():
    return {
        "instructions": server._INSTRUCTIONS_TEMPLATE,
        "tap workflow reference": server.TAP_WORKFLOW_REFERENCE,
        "create_tap description": _create_tap_description(),
    }


# A `"query"` key shown in a body for /query/mongodb, or "query/database/collection".
_QUERY_FIELD = re.compile(r'query/mongodb[^\n]{0,80}?("query"\s*:|\bwith query/)')


def test_no_query_field_is_shown_for_the_mongodb_route():
    for where, text in _passages().items():
        assert "query/mongodb" in text, f"{where} no longer mentions /query/mongodb"
        assert not _QUERY_FIELD.search(text), f"{where} shows a 'query' field for /query/mongodb"
        tail = text[text.index("query/mongodb"):][:200]
        assert "filter" in tail, f"{where} does not show the filter field for /query/mongodb: {tail}"


def test_the_reference_states_the_mongodb_limits_and_the_status_check():
    ref = server.TAP_WORKFLOW_REFERENCE
    i = ref.index("A single MongoDB query")
    sentence = ref[i:i + 1200]
    assert re.search(r"\b30 seconds\b", sentence), sentence
    assert re.search(r"\b300 seconds\b", sentence), sentence
    assert re.search(r"size ceiling", sentence), sentence
    assert re.search(r"split [^.]*ranges with `filter`", sentence), sentence
    assert "400" in sentence, sentence
    assert re.search(r"must check the status", sentence), sentence
    assert re.search(r"range read may keep `\"limit\": -1`", sentence), sentence
    assert "$oid" in sentence and "$date" in sentence, sentence


def test_the_create_tap_description_tells_the_script_to_check_the_status():
    desc = _create_tap_description()
    tail = desc[desc.index("/query/mongodb"):][:300]
    assert re.search(r"check the HTTP status before reading results", tail), tail
