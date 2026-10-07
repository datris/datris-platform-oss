"""Story: Early hint when Unity Catalog register mode targets Databricks
(plans/stories/uc-register-mode-databricks-hint.md).

POST /api/v1/pipeline now answers 200 with {"warnings": [...]}. The MCP
save sites (create_pipeline, set_catalog's pipeline path) pass a non-empty
`warnings` array through to the agent, add no key for an empty array or a
plain/empty body from an older server, and the hint wording never trips the
`error`/`exception` failure check. Docs: unity-catalog.mdx mentions the
save-time warning, openapi's POST /api/v1/pipeline 200 lists `warnings`,
pipeline-api.mdx shows the body, and the pipeline-config reference tells the
agent to relay them."""
import json
import os
import re
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS = os.path.join(REPO_ROOT, "docs")
UNITY_CATALOG_MDX = os.path.join(DOCS, "destinations", "unity-catalog.mdx")
PIPELINE_API_MDX = os.path.join(DOCS, "api-reference", "pipeline-api.mdx")
OPENAPI_YAML = os.path.join(DOCS, "openapi.yaml")

HOST = "dbc-a1b2c3d4-e5f6.cloud.databricks.com"

# The two hint texts from the story (UnityCatalogSaveHints), with the host filled in.
REGISTER_HINT = (
    f"Unity Catalog on Databricks ({HOST}) has no Iceberg REST register call, so this "
    "pipeline's table will not be registered; each run will say so and the load itself "
    "still succeeds. Set catalogMode managed so the table is created in Unity Catalog's "
    "managed storage, or use the databricks destination."
)
REST_HINT = (
    f"Unity Catalog on Databricks ({HOST}) creates a managed table at its own location when "
    "a table is created through its REST catalog; Datris refuses to write there, the run "
    "writes by path and the pipeline page shows refused. Set catalogMode managed instead, "
    "or use the databricks destination."
)


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


# ---------------------------------------------------------------- stubs ---

class _Server:
    """Stub the HTTP seams create_pipeline / set_catalog go through; the
    pipeline POST answers with `save_body`."""

    def __init__(self, save_body):
        self.save_body = save_body
        self.posted = None

    def upload_content(self, path, content_b64, filename, data=None):
        assert path == "/api/v1/pipeline/generate"
        return json.dumps({
            "name": data["pipeline"],
            "source": {"fileAttributes": {"csvAttributes": {"delimiter": ","}}},
        })

    def call(self, method, path, timeout=300, **kwargs):
        if method == "post" and path == "/api/v1/pipeline":
            self.posted = kwargs.get("json")
            return self.save_body
        if method == "get" and path == "/api/v1/pipeline":
            name = kwargs.get("params", {}).get("pipeline")
            return json.dumps({"name": name, "destination": {"objectStore": {"fileFormat": "iceberg"}}})
        return ""


def _install(monkeypatch, save_body):
    s = _Server(save_body)
    monkeypatch.setattr(server, "_upload_content", s.upload_content)
    monkeypatch.setattr(server, "_call", s.call)
    return s


def _create(monkeypatch, save_body):
    s = _install(monkeypatch, save_body)
    out = json.loads(server._dispatch("create_pipeline", {
        "pipeline": "hint_reg",
        "destination": "objectstore",
        "prefix": "hint_reg",
        "fileFormat": "iceberg",
        "unity_catalog": True,
        "unity_catalog_secret": "dbx_hint",
        "unity_catalog_catalog": "main",
        "unity_catalog_schema": "default",
        "filename": "orders.csv",
        "content_text": "id,amount\n1,10\n2,20\n",
    }))
    assert s.posted is not None, "pipeline config was never POSTed"
    return out


def _set_catalog(monkeypatch, save_body):
    _install(monkeypatch, save_body)
    return json.loads(server._dispatch("set_catalog", {"pipeline": "hint_reg", "catalog": "sales"}))


# ---------------------------------------------------- MCP passthrough ---

def test_create_pipeline_forwards_server_warnings(monkeypatch):
    out = _create(monkeypatch, json.dumps({"warnings": [REGISTER_HINT]}))
    assert "error" not in out, out
    assert out.get("status") == "Pipeline created", out
    assert out.get("warnings") == [REGISTER_HINT], out


def test_create_pipeline_forwards_rest_mode_warning(monkeypatch):
    out = _create(monkeypatch, json.dumps({"warnings": [REST_HINT]}))
    assert "error" not in out, out
    assert out.get("warnings") == [REST_HINT], out


@pytest.mark.parametrize("body", ["", "Pipeline saved", None, json.dumps({"warnings": []})])
def test_create_pipeline_plain_or_empty_body_yields_no_warnings_key(monkeypatch, body):
    out = _create(monkeypatch, body)
    assert "error" not in out, out
    assert out.get("status") == "Pipeline created", out
    assert "warnings" not in out, out


def test_set_catalog_forwards_server_warnings(monkeypatch):
    out = _set_catalog(monkeypatch, json.dumps({"warnings": [REGISTER_HINT]}))
    assert "error" not in out, out
    assert out.get("pipeline") == "hint_reg", out
    assert out.get("warnings") == [REGISTER_HINT], out


@pytest.mark.parametrize("body", ["", "Pipeline saved", json.dumps({"warnings": []})])
def test_set_catalog_plain_or_empty_body_yields_no_warnings_key(monkeypatch, body):
    out = _set_catalog(monkeypatch, body)
    assert "error" not in out, out
    assert "warnings" not in out, out


@pytest.mark.parametrize("hint", [REGISTER_HINT, REST_HINT])
def test_hint_wording_contains_no_error_or_exception_substrings(hint):
    assert "error" not in hint.lower(), hint
    assert "exception" not in hint.lower(), hint
    # The advice the story mandates.
    assert "Set catalogMode managed" in hint, hint
    assert "databricks destination" in hint, hint


# --------------------------------------------------------------- docs ---

def _paragraphs(text):
    return [p for p in re.split(r"\n\s*\n", text) if p.strip()]


def test_unity_catalog_mdx_mentions_save_time_warning():
    paras = [
        p for p in _paragraphs(_read(UNITY_CATALOG_MDX))
        if re.search(r"\bsav(e|ing)\b", p, re.IGNORECASE)
        and re.search(r"\bwarn(s|ing|ings)?\b", p, re.IGNORECASE)
        and "Databricks" in p
        and "managed" in p
    ]
    assert paras, (
        "unity-catalog.mdx needs a sentence that the pipeline save already warns when the "
        "credentials secret is a Databricks workspace, pointing at catalogMode managed"
    )


def test_openapi_post_pipeline_200_lists_warnings():
    import yaml

    spec = yaml.safe_load(_read(OPENAPI_YAML))
    resp = spec["paths"]["/api/v1/pipeline"]["post"]["responses"]
    ok = resp.get("200") or resp.get(200)
    assert ok, resp
    assert "content" in ok, f"POST /api/v1/pipeline 200 has no response body schema: {ok}"
    schema = ok["content"]["application/json"]["schema"]
    if "$ref" in schema:
        name = schema["$ref"].rsplit("/", 1)[-1]
        schema = spec["components"]["schemas"][name]
    warnings = schema["properties"]["warnings"]
    assert warnings["type"] == "array", warnings
    assert warnings["items"]["type"] == "string", warnings
    assert "advisory" in (ok.get("description", "") + json.dumps(schema)).lower(), ok


def test_pipeline_api_mdx_create_example_shows_warnings_body():
    text = _read(PIPELINE_API_MDX)
    start = text.index("POST /api/v1/pipeline\n")
    end = text.index("\n---", start)
    section = text[start:end]
    assert '"warnings"' in section, section[-600:]


def test_pipeline_config_reference_tells_agent_to_relay_warnings():
    ref = server.PIPELINE_CONFIG_REFERENCE
    paras = [p for p in _paragraphs(ref) if "Unity Catalog registration" in p]
    assert paras, "registration paragraph missing from PIPELINE_CONFIG_REFERENCE"
    para = paras[0]
    assert "`warnings`" in para, para[:400]
    assert re.search(r"relay", para, re.IGNORECASE), para[:400]


# ------------------------------------- "error" inside a warning's host ---

ERROR_HOST_HINT = REGISTER_HINT.replace(HOST, "dbc-error-team.cloud.databricks.com")


def test_create_pipeline_warning_with_error_in_host_is_still_success(monkeypatch):
    out = _create(monkeypatch, json.dumps({"warnings": [ERROR_HOST_HINT]}))
    assert "error" not in out, out
    assert out.get("status") == "Pipeline created", out
    assert out.get("warnings") == [ERROR_HOST_HINT], out


def test_set_catalog_warning_with_error_in_host_is_still_success(monkeypatch):
    out = _set_catalog(monkeypatch, json.dumps({"warnings": [ERROR_HOST_HINT]}))
    assert "error" not in out, out
    assert out.get("pipeline") == "hint_reg", out
    assert out.get("warnings") == [ERROR_HOST_HINT], out


@pytest.mark.parametrize("body", [
    json.dumps({"error": "Pipeline config is invalid"}),
    "java.lang.IllegalStateException: boom",
])
def test_non_warnings_failure_body_is_still_a_failure(monkeypatch, body):
    out = _create(monkeypatch, body)
    assert "error" in out, out
    assert out["error"].startswith("Failed to register pipeline"), out


# ------------------------- CodeGen scripts (codegen-script-pinning story) ---
# POST /api/v1/pipeline also answers `codegenScripts`: per AI kind whether the
# script generated at save is ready or pending (with the reason, also named in
# `warnings`). create_pipeline passes both through; an older server's body
# without the key adds nothing.

PENDING_WARNING = (
    "CodeGen transformation script is pending: No AI provider key is configured. "
    "The pipeline is saved; its first run generates and stores the script."
)
CODEGEN_SCRIPTS = [
    {"kind": "dataQuality", "status": "ready", "generatedAt": "2026-10-06T00:00:00Z", "model": "m"},
    {"kind": "transformation", "status": "pending", "pendingReason": "No AI provider key is configured"},
]


def test_create_pipeline_forwards_codegen_scripts_and_pending_warning(monkeypatch):
    out = _create(monkeypatch, json.dumps({"warnings": [PENDING_WARNING], "codegenScripts": CODEGEN_SCRIPTS}))
    assert "error" not in out, out
    assert out.get("status") == "Pipeline created", out
    assert out.get("warnings") == [PENDING_WARNING], out
    assert out.get("codegenScripts") == CODEGEN_SCRIPTS, out


@pytest.mark.parametrize("body", [
    json.dumps({"warnings": []}),
    json.dumps({"warnings": [], "codegenScripts": []}),
    json.dumps({"warnings": [], "codegenScripts": "nope"}),
    "",
])
def test_create_pipeline_without_codegen_scripts_adds_no_key(monkeypatch, body):
    out = _create(monkeypatch, body)
    assert "error" not in out, out
    assert "codegenScripts" not in out, out


def test_create_pipeline_description_mentions_stored_codegen_scripts():
    tools = {t.name: t for t in server._all_tools()}
    text = tools["create_pipeline"].description
    assert "codegenScripts" in text, "create_pipeline description should name the codegenScripts result field"
    assert "pending" in text


def test_openapi_post_pipeline_200_lists_codegen_scripts():
    import yaml

    spec = yaml.safe_load(_read(OPENAPI_YAML))
    ok = spec["paths"]["/api/v1/pipeline"]["post"]["responses"]["200"]
    schema = ok["content"]["application/json"]["schema"]
    if "$ref" in schema:
        schema = spec["components"]["schemas"][schema["$ref"].rsplit("/", 1)[-1]]
    cg = schema["properties"]["codegenScripts"]
    assert cg["type"] == "array", cg
    assert "/api/v1/pipelines/{name}/codegen-scripts" in spec["paths"]
    assert "/api/v1/pipelines/{name}/codegen-scripts/{kind}/regenerate" in spec["paths"]


# ------------------- CodeGen scripts in the code repository (story 2) ---
# A save whose repository commit is rejected (the file was edited in the
# repository) answers 200 with a per-kind `warning`, also in `warnings`.

CONFLICT_WARNING = (
    "The CodeGen transformation script for pipeline orders was not replaced: 'taps/pipelines/orders/transformation.py' "
    "changed in the code repository since the recorded commit abc1234, and a hand edit is never overwritten. "
    "Adopt the repository version with POST /api/v1/pipelines/orders/codegen-scripts/transformation/pull (pull), "
    "or replace it with POST /api/v1/pipelines/orders/codegen-scripts/transformation/regenerate?overwrite=true."
)


def test_create_pipeline_forwards_repository_conflict_warning(monkeypatch):
    scripts = [{"kind": "transformation", "status": "ready", "warning": CONFLICT_WARNING}]
    out = _create(monkeypatch, json.dumps({"warnings": [CONFLICT_WARNING], "codegenScripts": scripts}))
    assert "error" not in out, out
    assert out.get("warnings") == [CONFLICT_WARNING], out
    assert out.get("codegenScripts") == scripts, out


def test_create_pipeline_description_mentions_repository_warning():
    tools = {t.name: t for t in server._all_tools()}
    text = tools["create_pipeline"].description
    assert "code repository" in text and "`warning`" in text


def test_openapi_lists_codegen_script_pull():
    import yaml

    spec = yaml.safe_load(_read(OPENAPI_YAML))
    assert "/api/v1/pipelines/{name}/codegen-scripts/{kind}/pull" in spec["paths"]
    params = {p["name"] for p in spec["paths"]["/api/v1/pipelines/{name}/codegen-scripts/{kind}/regenerate"]["post"].get("parameters", [])}
    assert {"storage", "overwrite"} <= params, params
