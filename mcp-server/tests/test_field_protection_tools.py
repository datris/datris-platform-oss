"""Story: Field protection 2: docs, OpenAPI, MCP create_pipeline `protect`,
agent skill (plans/stories/field-protection-2-surfaces.md).

Pins the MCP side: create_pipeline takes an optional `protect` map (field
name -> policy). The tool merges each policy onto the matching generated
schema field (case-insensitive name match), posts no `protect` key when the
arg is absent, and returns an error naming any field that is not in the
schema without posting anything."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


# ---------------------------------------------------------------- helpers ---

GENERATED_FIELDS = [
    {"name": "mrn", "type": "string"},
    {"name": "email", "type": "string"},
    {"name": "ssn", "type": "string"},
]


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


class _Captured:
    """Stub the HTTP seams create_pipeline goes through: the generate call
    returns a schema with fields mrn/email/ssn, the save call is captured."""

    def __init__(self):
        self.posted = None
        self.post_count = 0

    def upload_content(self, path, content_b64, filename, data=None):
        assert path == "/api/v1/pipeline/generate"
        return json.dumps({
            "name": data["pipeline"],
            "source": {
                "fileAttributes": {"csvAttributes": {"delimiter": ","}},
                "schemaProperties": {"fields": [dict(f) for f in GENERATED_FIELDS]},
            },
        })

    def call(self, method, path, timeout=300, **kwargs):
        if method == "post" and path == "/api/v1/pipeline":
            self.post_count += 1
            self.posted = kwargs.get("json")
            return "Pipeline saved"
        if method == "get" and path == "/api/v1/pipeline":
            return json.dumps({"name": kwargs.get("params", {}).get("pipeline")})
        return ""


@pytest.fixture
def captured(monkeypatch):
    c = _Captured()
    monkeypatch.setattr(server, "_upload_content", c.upload_content)
    monkeypatch.setattr(server, "_call", c.call)
    return c


def _args(**extra):
    args = {
        "pipeline": "fp_mcp",
        "destination": "postgres",
        "table": "fp_mcp",
        "filename": "fp_mcp.csv",
        "content_text": "mrn,email,ssn\n1,a@x.com,111\n",
    }
    args.update(extra)
    return args


def _create(captured, **extra):
    out = json.loads(server._dispatch("create_pipeline", _args(**extra)))
    assert "error" not in out, out
    assert captured.posted is not None, "pipeline config was never POSTed"
    return captured.posted


def _fields(posted):
    return {f["name"]: f for f in posted["source"]["schemaProperties"]["fields"]}


# ------------------------------------------------------------------ schema ---

def test_create_pipeline_schema_has_optional_protect_object():
    tool = _tool("create_pipeline")
    props = tool.inputSchema["properties"]
    assert "protect" in props, sorted(props)
    arg = props["protect"]
    assert arg["type"] == "object", arg
    policy = arg["additionalProperties"]
    assert policy["type"] == "object", policy
    assert "method" in policy["properties"], policy
    assert policy.get("required") == ["method"], policy
    assert "OMIT BY DEFAULT" in arg["description"], arg["description"]
    assert "protect" not in tool.inputSchema.get("required", [])


# ---------------------------------------------------------------- dispatch ---

def test_protect_map_is_merged_onto_the_matching_schema_fields(captured):
    posted = _create(captured, protect={
        "mrn": {"method": "hmac"},
        "email": {"method": "mask", "preserve": "domain"},
    })
    fields = _fields(posted)
    assert fields["mrn"].get("protect") == {"method": "hmac"}, fields["mrn"]
    assert fields["email"].get("protect") == {"method": "mask", "preserve": "domain"}, fields["email"]
    assert "protect" not in fields["ssn"], fields["ssn"]
    # Field names and types are untouched.
    assert [f["name"] for f in posted["source"]["schemaProperties"]["fields"]] == ["mrn", "email", "ssn"]
    assert all(f["type"] == "string" for f in fields.values())


def test_absent_protect_posts_no_protect_key(captured):
    posted = _create(captured)
    for f in posted["source"]["schemaProperties"]["fields"]:
        assert "protect" not in f, f
    assert "protect" not in posted
    assert "protection" not in posted


def test_unknown_field_name_is_an_error_and_nothing_is_posted(captured):
    raw = server._dispatch("create_pipeline", _args(protect={
        "mrn": {"method": "hmac"},
        "patient_id": {"method": "drop"},
    }))
    assert server._is_error_payload(raw), raw
    out = json.loads(raw)
    assert "patient_id" in out["error"], out
    assert captured.post_count == 0, captured.posted
    assert captured.posted is None


def test_field_match_is_case_insensitive(captured):
    posted = _create(captured, protect={"MRN": {"method": "hmac"}, "Ssn": {"method": "drop"}})
    fields = _fields(posted)
    assert fields["mrn"].get("protect") == {"method": "hmac"}, fields["mrn"]
    assert fields["ssn"].get("protect") == {"method": "drop"}, fields["ssn"]
    # The schema's own spelling is kept; no duplicate field is added.
    assert sorted(fields) == ["email", "mrn", "ssn"], sorted(fields)


# ------------------------------------------------------------- JSON source ---

class _CapturedJson(_Captured):
    """The generate call returns a JSON-source schema: the single `_json` field."""

    def upload_content(self, path, content_b64, filename, data=None):
        assert path == "/api/v1/pipeline/generate"
        return json.dumps({
            "name": data["pipeline"],
            "source": {
                "fileAttributes": {"jsonAttributes": {"everyRowContainsObject": True}},
                "schemaProperties": {"fields": [{"name": "_json", "type": "string"}]},
            },
        })


@pytest.fixture
def captured_json(monkeypatch):
    c = _CapturedJson()
    monkeypatch.setattr(server, "_upload_content", c.upload_content)
    monkeypatch.setattr(server, "_call", c.call)
    return c


def _json_args(**extra):
    return _args(destination="mongodb", filename="x.json",
                 content_text='{"account_id": "a1", "amount": 3}\n', **extra)


def test_json_source_protect_adds_top_level_key_beside_json(captured_json):
    out = json.loads(server._dispatch("create_pipeline", _json_args(protect={"account_id": {"method": "hmac"}})))
    assert "error" not in out, out
    assert captured_json.posted["source"]["schemaProperties"]["fields"] == [
        {"name": "_json", "type": "string"},
        {"name": "account_id", "type": "string", "protect": {"method": "hmac"}},
    ]


@pytest.mark.parametrize("name", ["_json", "_XML"])
def test_protect_on_document_field_is_an_error_and_nothing_is_posted(captured_json, name):
    raw = server._dispatch("create_pipeline", _json_args(protect={name: {"method": "hmac"}}))
    assert server._is_error_payload(raw), raw
    assert name in json.loads(raw)["error"]
    assert captured_json.post_count == 0


# ===================================================== story 3: suggestions ---
# plans/stories/field-protection-3-classifier.md. suggest_field_protection takes
# `pipeline` (string) or `fields` (array of {name, type}), one required, POSTs to
# /api/v1/pipeline/protect/suggest and renders one line per field:
#   "mrn (string): hmac — stable identifier" / "visit_count (int): none"

SUGGEST_PATH = "/api/v1/pipeline/protect/suggest"

SUGGEST_RESPONSE = {
    "model": "stub-model",
    "fields": [
        {"name": "mrn", "type": "string", "current": None,
         "suggested": {"method": "hmac"}, "reason": "stable identifier"},
        {"name": "email", "type": "string", "current": None,
         "suggested": {"method": "mask", "preserve": "domain"}, "reason": "contact field"},
        {"name": "notes", "type": "string", "current": None,
         "suggested": {"method": "redact"}, "reason": "free text"},
        {"name": "visit_count", "type": "int", "current": None,
         "suggested": None, "reason": "a count"},
    ],
}


class _SuggestCaptured:
    def __init__(self):
        self.calls = []

    def call(self, method, path, timeout=300, **kwargs):
        self.calls.append((method, path, kwargs))
        if method == "post" and path == SUGGEST_PATH:
            return json.dumps(SUGGEST_RESPONSE)
        raise AssertionError(f"unexpected call {method} {path}")


@pytest.fixture
def suggest_captured(monkeypatch):
    c = _SuggestCaptured()
    monkeypatch.setattr(server, "_call", c.call)
    return c


def _posts(c):
    return [(m, p, kw) for (m, p, kw) in c.calls if m == "post" and p == SUGGEST_PATH]


def test_suggest_tool_is_registered_with_pipeline_and_fields_args():
    tool = _tool("suggest_field_protection")
    props = tool.inputSchema["properties"]
    assert props["pipeline"]["type"] == "string", props
    assert props["fields"]["type"] == "array", props


def test_suggest_by_pipeline_posts_the_pipeline_name(suggest_captured):
    server._dispatch("suggest_field_protection", {"pipeline": "fp_demo"})
    posts = _posts(suggest_captured)
    assert len(posts) == 1, suggest_captured.calls
    assert posts[0][2].get("json") == {"pipeline": "fp_demo"}, posts[0][2]


def test_suggest_by_fields_posts_the_fields(suggest_captured):
    fields = [{"name": "mrn", "type": "string"}, {"name": "visit_count", "type": "int"}]
    server._dispatch("suggest_field_protection", {"fields": fields})
    posts = _posts(suggest_captured)
    assert len(posts) == 1, suggest_captured.calls
    assert posts[0][2].get("json") == {"fields": fields}, posts[0][2]


def test_suggest_with_neither_argument_is_an_error(suggest_captured):
    raw = server._dispatch("suggest_field_protection", {})
    assert server._is_error_payload(raw), raw
    err = json.loads(raw)["error"]
    # The error tells the caller what to pass (not a generic "Unknown tool").
    assert "pipeline" in err and "fields" in err, err
    assert suggest_captured.calls == [], suggest_captured.calls


def test_suggest_rendered_text_has_one_line_per_field_with_its_method(suggest_captured):
    text = server._dispatch("suggest_field_protection", {"pipeline": "fp_demo"})
    assert not server._is_error_payload(text), text
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    expected = {"mrn": "hmac", "email": "mask", "notes": "redact", "visit_count": "none"}
    for name, method in expected.items():
        hits = [ln for ln in lines if ln.startswith(name + " (")]
        assert len(hits) == 1, f"expected one line for {name}, got {hits}\n{text}"
        assert method in hits[0], f"{name} line lacks {method}: {hits[0]}"
    assert any(ln.startswith("mrn (string): hmac") and "stable identifier" in ln for ln in lines), text
    assert any(ln.startswith("visit_count (int): none") for ln in lines), text
    assert any("domain" in ln for ln in lines if ln.startswith("email (")), text
