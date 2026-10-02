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
