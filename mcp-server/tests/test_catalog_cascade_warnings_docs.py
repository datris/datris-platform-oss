"""Story: Catalog cascade delete surfaces Unity Catalog warnings
(plans/stories/uc-catalog-cascade-delete-warnings.md).

File-content checks for the docs side of the cascade `warnings` field:
docs/openapi.yaml `CatalogDeleteResult` gains `warnings` (array of
`{pipeline, message}`, cascade only), the `DELETE /api/v1/catalog/{name}`
description mentions it, and docs/data-catalog.mdx says the cascade
response's `warnings` lists pipelines whose Unity Catalog table Datris did
not drop."""
import os
import re

import yaml

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS = os.path.join(REPO_ROOT, "docs")
OPENAPI_YAML = os.path.join(DOCS, "openapi.yaml")
DATA_CATALOG_MDX = os.path.join(DOCS, "data-catalog.mdx")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _spec():
    return yaml.safe_load(_read(OPENAPI_YAML))


def _resolve(spec, node):
    while isinstance(node, dict) and "$ref" in node:
        ref = node["$ref"]
        assert ref.startswith("#/"), ref
        cur = spec
        for part in ref[2:].split("/"):
            cur = cur[part]
        node = cur
    return node


def test_catalog_delete_result_has_warnings_array_of_pipeline_message():
    spec = _spec()
    result = spec["components"]["schemas"]["CatalogDeleteResult"]
    props = result.get("properties", {})
    assert "warnings" in props, "CatalogDeleteResult has no `warnings` property"
    warnings = props["warnings"]
    assert warnings.get("type") == "array", warnings
    item = _resolve(spec, warnings.get("items", {}))
    item_props = item.get("properties", {})
    assert set(item_props) >= {"pipeline", "message"}, item_props
    assert item_props["pipeline"].get("type") == "string"
    assert item_props["message"].get("type") == "string"


def test_catalog_delete_result_warnings_is_described_as_cascade_only():
    spec = _spec()
    result = spec["components"]["schemas"]["CatalogDeleteResult"]
    warnings = result["properties"]["warnings"]
    text = (warnings.get("description", "") + " " + result.get("description", "")).lower()
    assert "cascade" in text, "warnings description must say it is cascade only"
    assert "unity catalog" in text, "warnings description must name the Unity Catalog follow-up"


def test_catalog_delete_result_keeps_detached_deleted_failed():
    props = _spec()["components"]["schemas"]["CatalogDeleteResult"]["properties"]
    assert {"detached", "deleted", "failed"} <= set(props), props


def test_delete_catalog_operation_description_mentions_warnings():
    op = _spec()["paths"]["/api/v1/catalog/{name}"]["delete"]
    desc = op.get("description", "") + " " + op.get("summary", "")
    assert "warnings" in desc, "DELETE /api/v1/catalog/{name} description does not mention `warnings`"


def test_data_catalog_mdx_documents_cascade_warnings():
    text = _read(DATA_CATALOG_MDX)
    lines = [ln for ln in text.splitlines() if "mode=cascade&confirm=<name>" in ln]
    assert lines, "data-catalog.mdx lost the mode=cascade&confirm=<name> sentence"
    line = lines[0]
    tail = line[line.index("mode=cascade&confirm=<name>"):]
    assert "`warnings`" in tail, "the cascade bullet does not mention the response's `warnings`"
    after = tail[tail.index("`warnings`"):]
    assert "Unity Catalog" in after, "the warnings sentence must say it is about the Unity Catalog table"
    assert re.search(r"pipeline", after, re.IGNORECASE), "the warnings sentence must say it names the pipeline"
