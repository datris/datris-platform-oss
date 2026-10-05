"""Story: Field protection 2: docs, OpenAPI, MCP create_pipeline `protect`,
agent skill (plans/stories/field-protection-2-surfaces.md).

Pins the MCP side: create_pipeline takes an optional `protect` map (field
name -> policy). The tool merges each policy onto the matching generated
schema field (case-insensitive name match), posts no `protect` key when the
arg is absent, and returns an error naming any field that is not in the
schema without posting anything."""
import json
import re
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


# ======================================================= story 5: encrypt ---
# plans/stories/field-protection-5-encrypt-reveal.md. Calls server._base_tools()
# (via _tool), server._dispatch("create_pipeline", ...) and the registered
# server.list_tools() handler. `encrypt` is reversible on the server only, by an
# operator with protect:reveal; there is no MCP reveal tool.


def _method_enum():
    policy = _tool("create_pipeline").inputSchema["properties"]["protect"]["additionalProperties"]
    return policy["properties"]["method"]["enum"]


def test_protect_method_enum_accepts_encrypt():
    enum = _method_enum()
    assert "encrypt" in enum, enum
    for m in ("hmac", "mask", "redact", "drop"):
        assert m in enum, enum
    # fpe and tokenize are still not offered.
    assert "fpe" not in enum and "tokenize" not in enum, enum


def test_encrypt_policy_is_merged_onto_the_schema_field(captured):
    posted = _create(captured, protect={"email": {"method": "encrypt"}})
    assert _fields(posted)["email"].get("protect") == {"method": "encrypt"}


def test_list_tools_has_no_reveal_tool(monkeypatch):
    import asyncio
    monkeypatch.setattr(server, "_allowed_tool_names", lambda: None)
    tools = asyncio.run(server.list_tools())
    assert tools, "list_tools returned nothing"
    names = [t.name for t in tools]
    assert not [n for n in names if "reveal" in n.lower()], names
    assert not [t.name for t in server._base_tools() if "reveal" in t.name.lower()]
    for t in tools:
        props = (t.inputSchema or {}).get("properties", {})
        assert "/api/v1/protect/reveal" not in (t.description or ""), t.name
        assert "reveal" not in {k.lower() for k in props}, t.name


# ============================================ story 11: Safe Harbor preset ---
# plans/stories/field-protection-11-safe-harbor-preset-surfaces.md, the four
# "MCP pytest" Acceptance bullets. Server contract (story 10 as built):
# POST /api/v1/pipeline/protect/preset with {"preset", "fields"} or
# {"preset", "pipeline"} answers {"preset", "fields": [{"name", "class",
# "method", "preserve", "reason", "current"}], "unclassified": [names],
# "review": [notes]}; `method` is "none" when a clamp leaves nothing.
# suggest_field_protection gains `preset` (enum hipaa-safe-harbor);
# create_pipeline gains `protect_preset` (enum) and `protect_exempt` (array of
# field names) -> config["protection"] = {"preset", "presetExempt"}.

PRESET = "hipaa-safe-harbor"
PRESET_PATH = "/api/v1/pipeline/protect/preset"

AGE_NOTE = "Ages over 89 must be aggregated into a single 90 or older category."
ZIP_NOTE = "ZIP prefixes covering 20,000 people or fewer must be 000."
FREE_NOTE = "Free-text fields may hold identifiers: notes."

PRESET_RESPONSE = {
    "preset": PRESET,
    "fields": [
        {"name": "patient_name", "class": "name", "method": "redact", "preserve": None,
         "reason": "person name", "current": None},
        {"name": "mrn", "class": "mrn", "method": "hmac", "preserve": None,
         "reason": "medical record number", "current": None},
        {"name": "zip", "class": "geographic", "method": "mask", "preserve": "first3",
         "reason": "ZIP code", "current": None},
        {"name": "fax_no", "class": "fax", "method": "none", "preserve": None,
         "reason": "key field: only hmac allowed", "current": None},
    ],
    "unclassified": ["visit_count", "notes"],
    "review": [AGE_NOTE, ZIP_NOTE, FREE_NOTE],
}

PRESET_FIELDS = [
    {"name": "patient_name", "type": "string"},
    {"name": "mrn", "type": "string"},
    {"name": "zip", "type": "string"},
    {"name": "fax_no", "type": "string"},
    {"name": "visit_count", "type": "int"},
    {"name": "notes", "type": "string"},
]


class _PresetCaptured:
    def __init__(self):
        self.calls = []

    def call(self, method, path, timeout=300, **kwargs):
        self.calls.append((method, path, kwargs))
        if method == "post" and path == PRESET_PATH:
            return json.dumps(PRESET_RESPONSE)
        if method == "post" and path == SUGGEST_PATH:
            return json.dumps(SUGGEST_RESPONSE)
        raise AssertionError(f"unexpected call {method} {path}")


@pytest.fixture
def preset_captured(monkeypatch):
    c = _PresetCaptured()
    monkeypatch.setattr(server, "_call", c.call)
    return c


def test_suggest_tool_has_optional_preset_enum():
    tool = _tool("suggest_field_protection")
    props = tool.inputSchema["properties"]
    assert "preset" in props, sorted(props)
    assert props["preset"].get("enum") == [PRESET], props["preset"]
    assert "preset" not in tool.inputSchema.get("required", [])


def test_suggest_with_preset_posts_to_the_preset_endpoint_and_renders_classes_unclassified_and_review(preset_captured):
    text = server._dispatch("suggest_field_protection", {"fields": PRESET_FIELDS, "preset": PRESET})
    posts = [(p, kw) for (m, p, kw) in preset_captured.calls if m == "post"]
    assert [p for p, _ in posts] == [PRESET_PATH], preset_captured.calls
    assert posts[0][1].get("json") == {"preset": PRESET, "fields": PRESET_FIELDS}, posts[0][1]

    assert not server._is_error_payload(text), text
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]

    def line_for(name):
        hits = [ln for ln in lines if re.match(r"^[-*\s]*`?" + re.escape(name) + r"\b", ln)]
        assert len(hits) == 1, f"expected one line for {name}, got {hits}\n{text}"
        return hits[0]

    # Class, method and reason per field.
    pn = line_for("patient_name")
    assert "name" in pn.replace("patient_name", "", 1) and "redact" in pn and "person name" in pn, pn
    mrn = line_for("mrn")
    assert "hmac" in mrn and "medical record number" in mrn, mrn
    assert mrn.count("mrn") >= 2, f"class 'mrn' not shown beside the field: {mrn}"
    z = line_for("zip")
    assert "geographic" in z and "mask" in z and "first3" in z and "ZIP code" in z, z
    fx = line_for("fax_no")
    assert "fax" in fx.replace("fax_no", "", 1) and "none" in fx, fx

    # Unclassified names and every review note are listed.
    assert re.search(r"unclassified", text, re.IGNORECASE), text
    after_un = text[re.search(r"unclassified", text, re.IGNORECASE).start():]
    assert "visit_count" in after_un and "notes" in after_un, text
    assert re.search(r"review", text, re.IGNORECASE), text
    for note in (AGE_NOTE, ZIP_NOTE, FREE_NOTE):
        assert note in text, f"review note missing: {note}\n{text}"

    # Confirm-with-the-user footer last, telling the agent to show the review notes.
    footer = lines[-1]
    assert "user" in footer.lower(), footer
    assert text.rfind(FREE_NOTE) < text.rfind(footer), "footer must come after the review notes"
    assert re.search(r"review notes?", footer, re.IGNORECASE), f"footer must tell the agent to show the review notes: {footer}"


def test_suggest_with_preset_and_pipeline_posts_the_pipeline_name(preset_captured):
    server._dispatch("suggest_field_protection", {"pipeline": "fp_demo", "preset": PRESET})
    posts = [(p, kw) for (m, p, kw) in preset_captured.calls if m == "post"]
    assert [p for p, _ in posts] == [PRESET_PATH], preset_captured.calls
    assert posts[0][1].get("json") == {"preset": PRESET, "pipeline": "fp_demo"}, posts[0][1]


def test_suggest_without_preset_still_posts_to_suggest(preset_captured):
    server._dispatch("suggest_field_protection", {"fields": PRESET_FIELDS})
    paths = [p for (m, p, kw) in preset_captured.calls if m == "post"]
    assert paths == [SUGGEST_PATH], preset_captured.calls


def test_create_pipeline_schema_has_optional_protect_preset_and_exempt():
    tool = _tool("create_pipeline")
    props = tool.inputSchema["properties"]
    assert "protect_preset" in props, sorted(props)
    assert props["protect_preset"].get("enum") == [PRESET], props["protect_preset"]
    assert "protect_exempt" in props, sorted(props)
    ex = props["protect_exempt"]
    assert ex["type"] == "array", ex
    assert ex["items"]["type"] == "string", ex
    required = tool.inputSchema.get("required", [])
    assert "protect_preset" not in required and "protect_exempt" not in required, required


def test_create_pipeline_with_protect_preset_posts_protection_preset_and_exempt(captured):
    posted = _create(captured,
                     protect={"mrn": {"method": "hmac"}, "email": {"method": "redact"}},
                     protect_preset=PRESET, protect_exempt=["ssn"])
    assert posted.get("protection") == {"preset": PRESET, "presetExempt": ["ssn"]}, posted.get("protection")
    fields = _fields(posted)
    assert fields["mrn"].get("protect") == {"method": "hmac"}
    assert "protect" not in fields["ssn"], fields["ssn"]


def test_create_pipeline_with_protect_preset_only_posts_the_preset(captured):
    posted = _create(captured, protect={"mrn": {"method": "hmac"}}, protect_preset=PRESET)
    protection = posted.get("protection")
    assert isinstance(protection, dict), posted
    assert protection.get("preset") == PRESET, protection
    assert protection.get("presetExempt", []) == [], protection
    assert set(protection) <= {"preset", "presetExempt"}, protection


def test_absent_preset_arguments_post_no_protection_block(captured):
    # The arguments exist on the tool but are optional ...
    props = _tool("create_pipeline").inputSchema["properties"]
    assert "protect_preset" in props and "protect_exempt" in props, sorted(props)
    # ... and leaving them out posts no protection block, with or without protect.
    posted = _create(captured)
    assert "protection" not in posted, posted
    captured.posted = None
    posted = _create(captured, protect={"mrn": {"method": "hmac"}})
    assert "protection" not in posted, posted
    assert "preset" not in json.dumps(posted), posted


class _RefusingSave(_Captured):
    REFUSAL = ("Preset hipaa-safe-harbor: field 'ssn' is classified as ssn and has no protect. "
               "Add protect to it or list it under protection.presetExempt")

    def call(self, method, path, timeout=300, **kwargs):
        if method == "post" and path == "/api/v1/pipeline":
            self.post_count += 1
            self.posted = kwargs.get("json")
            return json.dumps({"error": self.REFUSAL})
        return super().call(method, path, timeout=timeout, **kwargs)


def test_server_refusal_for_an_unprotected_identifier_passes_through(monkeypatch):
    c = _RefusingSave()
    monkeypatch.setattr(server, "_upload_content", c.upload_content)
    monkeypatch.setattr(server, "_call", c.call)
    raw = server._dispatch("create_pipeline", _args(protect={"mrn": {"method": "hmac"}}, protect_preset=PRESET))
    assert c.posted is not None and c.posted.get("protection", {}).get("preset") == PRESET, c.posted
    assert server._is_error_payload(raw), raw
    assert "'ssn'" in raw and "presetExempt" in raw, raw


def test_protect_preserve_enum_includes_first3():
    policy = _tool("create_pipeline").inputSchema["properties"]["protect"]["additionalProperties"]
    enum = policy["properties"]["preserve"]["enum"]
    assert "first3" in enum, enum
    for p in ("last4", "domain", "year"):
        assert p in enum, enum


# ---- review round: protect_exempt validation --------------------------------

def test_protect_exempt_naming_an_unknown_field_is_an_error_and_nothing_is_posted(captured):
    raw = server._dispatch("create_pipeline", _args(protect={"mrn": {"method": "hmac"}},
                                                    protect_preset=PRESET, protect_exempt=["phone"]))
    assert server._is_error_payload(raw), raw
    assert "phone" in raw and "protect_exempt" in raw, raw
    assert captured.post_count == 0


def test_protect_exempt_without_protect_preset_is_an_error(captured):
    raw = server._dispatch("create_pipeline", _args(protect_exempt=["ssn"]))
    assert server._is_error_payload(raw), raw
    assert "protect_preset" in raw, raw
    assert captured.post_count == 0


@pytest.mark.parametrize("text", ['["ssn", "email"]', "ssn, email"])
def test_protect_exempt_string_form_is_parsed(captured, text):
    posted = _create(captured, protect={"mrn": {"method": "hmac"}}, protect_preset=PRESET, protect_exempt=text)
    assert posted.get("protection") == {"preset": PRESET, "presetExempt": ["ssn", "email"]}, posted.get("protection")
