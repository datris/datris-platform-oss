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
    # Part 3 (plans/stories/catalog-scoped-user-access-metadata-read.md):
    # lineage and provenance now work for catalog-limited users, filtered.
    "get_lineage", "get_provenance",
)

# Part 3: table/collection listings and Unity Catalog browse cannot be
# catalog-filtered, so the server refuses them for catalog-limited users.
LISTING = (
    "list_postgres_databases", "list_postgres_schemas", "list_postgres_tables", "list_postgres_columns",
    "list_mongodb_databases", "list_mongodb_collections",
    "list_qdrant_collections", "list_weaviate_classes", "list_milvus_collections",
    "list_chroma_collections", "list_pgvector_collections", "browse_unity_catalog",
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


def test_listing_tools_say_the_server_refuses_them_for_catalog_limited_users():
    tools = _tools()
    missing = [n for n in LISTING if n not in tools]
    assert not missing, f"tools missing from _all_tools(): {missing}"
    problems = []
    for name in LISTING:
        d = _norm(tools[name].description)
        if "limited to specific catalogs" not in d:
            problems.append(f"{name}: does not name catalog-limited users")
        if "cannot list tables or collections" not in d:
            problems.append(f"{name}: does not say they cannot list tables or collections")
        if "server refuses" not in d:
            problems.append(f"{name}: does not say the server refuses this tool")
        if "find_data" not in d or "get_pipeline" not in d:
            problems.append(f"{name}: does not point at find_data / get_pipeline")
    assert not problems, "\n".join(problems)


# Story: plans/stories/catalog-scoped-user-access-query-stamps.md, Acceptance.
WAREHOUSE_QUERY = ("query_snowflake", "query_sqlserver", "query_databricks")


def test_warehouse_query_tools_say_a_sql_argument_is_refused_for_catalog_limited_users():
    tools = _tools()
    missing = [n for n in WAREHOUSE_QUERY + ("query_objectstore",) if n not in tools]
    assert not missing, f"tools missing from _all_tools(): {missing}"
    problems = []
    for name in WAREHOUSE_QUERY:
        d = _norm(tools[name].description)
        if "catalog access" not in d:
            problems.append(f"{name}: no 'catalog access'")
        if "limited to specific catalogs" not in d:
            problems.append(f"{name}: does not name catalog-limited users")
        if "previews the pipeline's own table" not in d:
            problems.append(f"{name}: does not say it only previews the pipeline's own table")
        if "refuses a `sql` argument" not in d and "refuses a sql argument" not in d:
            problems.append(f"{name}: does not say the server refuses a `sql` argument")
        if "provenance fields that identify the feeding tap" not in d:
            problems.append(f"{name}: does not say rows leave out the tap-identifying provenance fields")
    d = _norm(tools["query_objectstore"].description)
    if "catalog access" not in d:
        problems.append("query_objectstore: no 'catalog access'")
    if "provenance fields that identify the feeding tap" not in d:
        problems.append("query_objectstore: does not say rows leave out the tap-identifying provenance fields")
    if "refuses a `sql` argument" in d or "refuses a sql argument" in d:
        problems.append("query_objectstore: takes no sql, so it must not carry the sql refusal")
    assert not problems, "\n".join(problems)


# Story: plans/stories/catalog-scoped-user-access-warehouse-preview-target.md, Acceptance.
def test_pipeline_save_tool_says_an_existing_destination_is_refused_for_catalog_limited_users():
    d = _norm(_tools()["create_pipeline"].description)
    sentences = [s for s in d.replace("; ", ". ").split(". ") if "already exist" in s]
    assert sentences, "create_pipeline: no sentence about a destination that already exists"
    hit = [s for s in sentences if "destination" in s and "refuse" in s]
    assert hit, f"create_pipeline: does not say an existing destination is refused: {sentences}"
    assert "limited to specific catalogs" in d, "create_pipeline: does not name catalog-limited users"
    assert any("not exist yet" in s or "does not exist" in s for s in d.split(". ")), (
        "create_pipeline: does not say a new destination must not exist yet"
    )
    assert "install-wide" in d, "create_pipeline: does not point at a user with install-wide access"
