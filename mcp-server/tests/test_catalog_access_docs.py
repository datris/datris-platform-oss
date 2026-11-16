"""Story: Catalog-scoped user access (UI, Assistant, MCP, docs)
(plans/stories/catalog-scoped-user-access-ui-assistant.md), Step 8.

Pins the catalog-access sentence in the MCP tool descriptions: list, get,
find, create, set_catalog, rename_catalog, search_* and query_* tools say that
results reflect the caller's catalog access and that a 404 for a name you were
given means it is not available to this user. The create tools also say a
create without `catalog` is filled in or refused with a list of allowed
catalogs.

Modelled on test_catalog_tools.py: reads `server._all_tools()`."""
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


NAMED = (
    "list_pipelines", "list_taps", "find_data", "get_pipeline", "get_tap",
    "create_pipeline", "create_tap", "set_catalog", "rename_catalog",
)
CREATE = ("create_pipeline", "create_tap")


def _tools():
    return {t.name: t for t in server._all_tools()}


def _covered():
    tools = _tools()
    names = [n for n in NAMED if n in tools]
    missing = [n for n in NAMED if n not in tools]
    assert not missing, f"tools missing from _all_tools(): {missing}"
    names += sorted(n for n in tools if n.startswith("search_") or n.startswith("query_"))
    return tools, names


def _norm(text):
    return " ".join((text or "").lower().split())


def test_list_create_set_catalog_search_and_query_tool_descriptions_mention_catalog_access_and_the_404_meaning():
    tools, names = _covered()
    assert any(n.startswith("search_") for n in names), "no search_* tools found"
    assert any(n.startswith("query_") for n in names), "no query_* tools found"
    problems = []
    for name in names:
        d = _norm(tools[name].description)
        if "catalog access" not in d:
            problems.append(f"{name}: no 'catalog access'")
        if "404" not in d:
            problems.append(f"{name}: no '404'")
        if "not available" not in d:
            problems.append(f"{name}: no 'not available' (meaning of a 404)")
    assert not problems, "\n".join(problems)


@pytest.mark.parametrize("name", CREATE)
def test_create_tool_description_says_missing_catalog_is_filled_in_or_refused_with_allowed_list(name):
    d = _norm(_tools()[name].description)
    assert "catalog access" in d, name
    assert "without" in d and "catalog" in d, f"{name}: does not describe a create without `catalog`"
    assert "allowed catalogs" in d, f"{name}: does not say the refusal lists allowed catalogs"
