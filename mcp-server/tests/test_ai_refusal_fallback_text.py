"""Story: when the model declines, schema generation and profiling fall back
instead of failing (plans/stories/ai-refusal-fallback.md), the MCP text half.

A declined profile now returns statistics only with `aiDeclined: true`. The
`profile_data` description has to say so, so an agent reads an empty
qualityIssues list as a decline and not as a clean file.

Pinned: `server._base_tools()` returns the tool list (same seam as
test_ai_sample_values_text.py)."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def test_profile_data_description_mentions_ai_declined():
    tools = {t.name: t for t in server._base_tools()}
    assert "profile_data" in tools, "profile_data tool missing"
    description = tools["profile_data"].description or ""
    assert "aiDeclined" in description, description
    assert "statistics" in description.lower(), description
