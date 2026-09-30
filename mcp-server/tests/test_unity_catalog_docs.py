"""Story: Unity Catalog 1: config + metadata push (Databricks)
(plans/stories/unity-catalog-1-metadata-push.md).

File-content checks that pin the docs-shaped Acceptance bullet: the Databricks
grant script gains `GRANT APPLY TAG ON SCHEMA`, databricks.mdx has a
`Unity Catalog metadata` heading, the pipeline-config reference resource the
MCP server and the in-platform Assistant both read mentions `unityCatalog` and
`APPLY TAG`, configuration-reference.mdx lists `DATRIS_UNITY_CATALOG_SYNC`, and
nothing under docs/ is a `.md` file."""
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS = os.path.join(REPO_ROOT, "docs")
DATABRICKS_MDX = os.path.join(DOCS, "destinations", "databricks.mdx")
CONFIG_REFERENCE_MDX = os.path.join(DOCS, "configuration-reference.mdx")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _grant_script(text):
    """The ```sql block that holds the catalog/schema grants (the one that
    grants USE CATALOG to the service principal)."""
    for block in re.findall(r"```sql\n(.*?)```", text, re.DOTALL):
        if "GRANT USE CATALOG" in block:
            return block
    raise AssertionError("databricks.mdx has no grant script (```sql block with GRANT USE CATALOG)")


# --------------------------------------------------------- grant script ---

def test_grant_script_contains_apply_tag_on_schema():
    script = _grant_script(_read(DATABRICKS_MDX))
    assert "GRANT APPLY TAG ON SCHEMA" in script, script
    # Same principal placeholder as the rest of the script.
    apply_tag = [l for l in script.splitlines() if "GRANT APPLY TAG ON SCHEMA" in l]
    assert any("<sp-application-id>" in l for l in apply_tag), apply_tag


# ------------------------------------------------ databricks.mdx heading ---

def test_databricks_page_has_unity_catalog_metadata_heading():
    headings = [l.strip() for l in _read(DATABRICKS_MDX).splitlines() if l.startswith("#")]
    assert any(re.match(r"^#+\s+Unity Catalog metadata\s*$", h) for h in headings), headings


def test_databricks_config_example_shows_unity_catalog_block():
    assert '"unityCatalog"' in _read(DATABRICKS_MDX)


# ---------------------------------------- PIPELINE_CONFIG_REFERENCE text ---

def test_pipeline_config_reference_mentions_unity_catalog_and_apply_tag():
    ref = server.PIPELINE_CONFIG_REFERENCE
    assert "unityCatalog" in ref
    assert "APPLY TAG" in ref


# ------------------------------------------- configuration-reference.mdx ---

def test_configuration_reference_lists_unity_catalog_sync_switch():
    text = _read(CONFIG_REFERENCE_MDX)
    rows = [l for l in text.splitlines() if l.startswith("| `DATRIS_UNITY_CATALOG_SYNC`")]
    assert rows, "no DATRIS_UNITY_CATALOG_SYNC row in configuration-reference.mdx"
    assert "`true`" in rows[0], rows[0]


# ------------------------------------------------------ no .md in docs/ ---

def test_no_markdown_files_tracked_under_docs():
    out = subprocess.run(
        ["git", "ls-files", "docs"], cwd=REPO_ROOT, capture_output=True, text=True, check=True
    ).stdout.split()
    md = [p for p in out if p.lower().endswith(".md")]
    assert md == [], md


def test_no_markdown_files_on_disk_under_docs():
    md = []
    for root, dirs, files in os.walk(DOCS):
        dirs[:] = [d for d in dirs if d not in ("node_modules", ".git", "config")]
        md += [os.path.relpath(os.path.join(root, f), REPO_ROOT) for f in files if f.lower().endswith(".md")]
    assert md == [], md


# ======================================================================
# Story: Unity Catalog 3: lineage publish
# (plans/stories/unity-catalog-3-lineage-publish.md), Acceptance bullet 4.
# ======================================================================

UNITY_CATALOG_MDX = os.path.join(DOCS, "destinations", "unity-catalog.mdx")
OPENAPI_YAML = os.path.join(DOCS, "openapi.yaml")


def _section(text, heading_re):
    """Body of the first heading matching heading_re, up to the next heading
    of the same or higher level."""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^(#+)\s+(.*?)\s*$", line)
        if m and re.match(heading_re, m.group(2)):
            level = len(m.group(1))
            body = []
            for nxt in lines[i + 1:]:
                n = re.match(r"^(#+)\s", nxt)
                if n and len(n.group(1)) <= level:
                    break
                body.append(nxt)
            return "\n".join(body)
    return None


def test_unity_catalog_page_has_lineage_section_naming_create_external_metadata():
    text = _read(UNITY_CATALOG_MDX)
    body = _section(text, r"^Lineage in Unity Catalog$")
    headings = [l.strip() for l in text.splitlines() if l.startswith("#")]
    assert body is not None, headings
    assert "CREATE EXTERNAL METADATA" in body, body


def test_databricks_knob_table_has_lineage_row():
    rows = [l for l in _read(DATABRICKS_MDX).splitlines() if l.startswith("| `lineage`")]
    assert rows, "no `lineage` row in the databricks.mdx unityCatalog knob table"
    assert "`true`" in rows[0], rows[0]


def test_pipeline_config_reference_mentions_lineage_and_create_external_metadata():
    ref = server.PIPELINE_CONFIG_REFERENCE
    assert re.search(r"\blineage\b", ref), "PIPELINE_CONFIG_REFERENCE does not mention the lineage knob"
    assert '"lineage"' in ref or "`lineage`" in ref or "lineage:" in ref or "lineage=" in ref, \
        "the lineage knob must be named as a key, not just the word"
    assert "CREATE EXTERNAL METADATA" in ref


def test_openapi_lists_last_lineage_at():
    text = _read(OPENAPI_YAML)
    assert "lastLineageAt" in text
    # It belongs to the unity-catalog state response.
    start = text.index("/api/v1/pipelines/{name}/unity-catalog:")
    nxt = re.search(r"\n  /api/", text[start + 1:])
    block = text[start: start + 1 + nxt.start()] if nxt else text[start:]
    assert "lastLineageAt" in block, "lastLineageAt must be in the /pipelines/{name}/unity-catalog schema"


# ======================================================================
# Story: Unity Catalog 4: Iceberg register spike
# (plans/stories/unity-catalog-4-iceberg-register.md), Acceptance bullet 4.
# ======================================================================

S3_MDX = os.path.join(DOCS, "destinations", "s3.mdx")
OBJECT_STORE_MDX = os.path.join(DOCS, "destinations", "object-store.mdx")


def test_unity_catalog_page_has_register_iceberg_tables_section():
    text = _read(UNITY_CATALOG_MDX)
    body = _section(text, r"^Register Iceberg tables$")
    headings = [l.strip() for l in text.splitlines() if l.startswith("#")]
    assert body is not None, headings
    assert "EXTERNAL USE SCHEMA" in body, body
    assert "external location" in body.lower(), body
    assert "icebergRestPath" in body, body
    assert "icebergRestPrefix" in body, body
    # Placed before "Browse what a secret can see".
    reg = next(i for i, h in enumerate(headings) if re.match(r"^#+\s+Register Iceberg tables$", h))
    browse = next(i for i, h in enumerate(headings) if re.match(r"^#+\s+Browse what a secret can see$", h))
    assert reg < browse, headings


def test_unity_catalog_page_no_longer_says_iceberg_tables_are_not_registered():
    text = _read(UNITY_CATALOG_MDX)
    assert "are not registered in Unity Catalog" not in text


def test_s3_and_object_store_pages_no_longer_say_not_registered():
    for path in (S3_MDX, OBJECT_STORE_MDX):
        text = _read(path)
        assert "not registered" not in text, path
        assert "/destinations/unity-catalog" in text, f"{path} must still point at the Unity Catalog page"


def test_openapi_lists_registered_metadata_location_in_unity_catalog_block():
    text = _read(OPENAPI_YAML)
    start = text.index("/api/v1/pipelines/{name}/unity-catalog:")
    nxt = re.search(r"\n  /api/", text[start + 1:])
    block = text[start: start + 1 + nxt.start()] if nxt else text[start:]
    for field in ("registeredMetadataLocation", "lastRegisterAt", "registerEnabled"):
        assert field in block, f"{field} must be in the /pipelines/{{name}}/unity-catalog schema"


def test_pipeline_config_reference_mentions_unity_catalog_catalog():
    ref = server.PIPELINE_CONFIG_REFERENCE
    assert "unityCatalog.catalog" in ref
    assert "credentialsSecret" in ref


def test_openapi_create_pipeline_request_lists_unity_catalog_block():
    import yaml

    spec = yaml.safe_load(_read(OPENAPI_YAML))
    props = spec["paths"]["/api/v1/pipeline"]["post"]["requestBody"]["content"]["application/json"]["schema"]["properties"]
    assert "unityCatalog" in props, sorted(props)
    uc = props["unityCatalog"]
    if "$ref" in uc:
        uc = spec["components"]["schemas"][uc["$ref"].rsplit("/", 1)[-1]]
    fields = uc.get("properties", {})
    for field in ("enabled", "credentialsSecret", "catalog", "schema", "register", "lineage"):
        assert field in fields, f"unityCatalog.{field} missing from the create-pipeline request schema: {sorted(fields)}"
    assert fields["schema"].get("default") == "default", fields["schema"]


# ======================================================================
# Story: Unity Catalog 5: Iceberg via RESTCatalog (`catalogMode: rest`)
# (plans/stories/unity-catalog-5-iceberg-restcatalog.md), Acceptance bullet 5.
# ======================================================================

def _unity_catalog_state_block(text):
    start = text.index("/api/v1/pipelines/{name}/unity-catalog:")
    nxt = re.search(r"\n  /api/", text[start + 1:])
    return text[start: start + 1 + nxt.start()] if nxt else text[start:]


def test_unity_catalog_page_has_keep_the_catalog_current_section():
    text = _read(UNITY_CATALOG_MDX)
    assert "catalogMode" in text
    headings = [l.strip() for l in text.splitlines() if l.startswith("#")]
    body = _section(text, r"^Keep the catalog current")
    assert body is not None, headings
    assert "catalogMode" in body and "rest" in body, body
    # Placed after "How runs behave".
    keep = next(i for i, h in enumerate(headings) if re.match(r"^#+\s+Keep the catalog current", h))
    runs = next(i for i, h in enumerate(headings) if re.match(r"^#+\s+How runs behave$", h))
    assert keep > runs, headings


def test_unity_catalog_page_no_longer_says_in_a_later_release():
    text = _read(UNITY_CATALOG_MDX)
    assert "in a later release" not in text


def test_openapi_unity_catalog_sync_has_catalog_mode_enum():
    import yaml

    spec = yaml.safe_load(_read(OPENAPI_YAML))
    fields = spec["components"]["schemas"]["UnityCatalogSync"]["properties"]
    assert "catalogMode" in fields, sorted(fields)
    assert fields["catalogMode"].get("type") == "string", fields["catalogMode"]
    assert fields["catalogMode"].get("enum") == ["register", "rest"], fields["catalogMode"]


def test_openapi_unity_catalog_state_lists_catalog_mode_and_rest_fields():
    import yaml

    spec = yaml.safe_load(_read(OPENAPI_YAML))
    get = spec["paths"]["/api/v1/pipelines/{name}/unity-catalog"]["get"]
    schema = get["responses"]["200"]["content"]["application/json"]["schema"]
    props = schema.get("properties", {})
    for field in ("catalogMode", "restMetadataLocation", "lastRestCommitAt", "restRefusedReason"):
        assert field in props, f"{field} must be in the /pipelines/{{name}}/unity-catalog schema: {sorted(props)}"
    register_enum = props["register"].get("enum", [])
    assert "rest" in register_enum and "refused" in register_enum, register_enum
    # Story-4 values are kept.
    for old in ("off", "never", "registered", "stale", "error"):
        assert old in register_enum, register_enum


def test_pipeline_config_reference_mentions_catalog_mode():
    ref = server.PIPELINE_CONFIG_REFERENCE
    assert "catalogMode" in ref
    assert "keeping it current is not done yet" not in ref


# ======================================================================
# Live Databricks probe (story 5 follow-up): Databricks Unity Catalog has no
# Iceberg REST register endpoint, so register mode cannot work there.
# ======================================================================

def test_unity_catalog_page_says_databricks_requires_rest():
    text = _read(UNITY_CATALOG_MDX)
    assert 'With Databricks, `catalogMode: "rest"` is required.' in text
    assert "does not implement the Iceberg REST `register` call" in text
    # Adopting an existing path table is impossible there; the way out is stated.
    assert "not possible on Databricks" in text
    assert "start from a new prefix" in text and "deleteBeforeWrite" in text
    # Register mode is still described as working where `register` exists.
    assert "Register mode works with catalogs that implement `register`" in text


def test_pipeline_config_reference_and_mcp_arg_say_databricks_requires_rest():
    ref = server.PIPELINE_CONFIG_REFERENCE
    assert "Databricks Unity Catalog has no Iceberg REST `register` call" in ref
    src = _read(os.path.join(REPO_ROOT, "mcp-server", "server.py"))
    assert "Databricks requires rest" in src
